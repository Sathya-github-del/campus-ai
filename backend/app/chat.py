"""Chat: build the model request (with prompt caching), pre-authorize cost, call, meter, persist.

Prompt caching (BLUEPRINT §7 - architectural, not optional)
---------------------------------------------------------
Every turn resends the whole conversation, so the request is laid out to keep a byte-identical,
ever-growing prefix that providers can serve from cache at ~10-25% of the input price:
  * one fixed system prompt (never personalised, never timestamped)
  * history replayed verbatim from SQLite in the same shape every turn (user turns are always a list
    of parts: image first, then text; assistant turns are plain strings); nothing is ever rewritten,
    summarised or trimmed - a conversation that gets too long is refused (413), not truncated,
    because dropping old turns would move the prefix and miss the cache on every later turn
  * Anthropic models need explicit breakpoints: cache_control on the system block and on the newest
    user turn (2 of the 4 allowed). The next turn then reads everything up to the previous breakpoint.
    OpenAI, Gemini 2.5+, DeepSeek, Grok, etc. cache matching prefixes automatically.
  * session_id = conversation_id on every request, so OpenRouter routes every turn of a conversation to
    the same upstream provider (a cache only exists on the provider that wrote it).
  * tier selection prefers models that advertise a cache-read price (catalog._rank).
Cache usage comes back in usage.prompt_tokens_details.{cached_tokens,cache_write_tokens} and is stored
per message so the hit rate can be measured.
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import logging
import time
import weakref
from dataclasses import dataclass
from decimal import Decimal

from .catalog import Catalog, ModelInfo
from .config import Settings
from .db import Database
from .errors import APIError
from .money import UNITS_PER_PAISA, UNITS_PER_RUPEE, charge_multiplier, floor_div, paise_to_inr, \
    units_to_inr, units_to_paise_ceil, usd_to_units
from .openrouter import UpstreamError
from .wallet import Wallet, new_id

log = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "You are the study helper inside a learning app for students in India (school, college and "
    "competitive-exam preparation). Explain step by step and check understanding, so the student "
    "learns the method, not just the answer. Match the student's language: reply in English, Hindi or "
    "Hinglish as they write. Keep answers focused and reasonably short; offer to go deeper. "
    "When the student sends a photo of a problem, first restate what you read from it, then solve it. "
    "Use simple Markdown. Write maths in plain text (for example: x^2 + 3x - 4 = 0, sqrt(2), 3/4) "
    "rather than LaTeX. If something in a photo is unreadable, say so instead of guessing. "
    "Be honest when you are unsure."
)
IMAGE_ONLY_PROMPT = "Please help me with the problem in this photo."

_IMAGE_MAGIC = (
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"\x89PNG\r\n\x1a\n", "image/png"),
    (b"GIF87a", "image/gif"),
    (b"GIF89a", "image/gif"),
)
_EFFORT_ORDER = ("none", "minimal", "low", "medium", "high", "xhigh", "max")


# ---------------------------------------------------------------- input validation

def decode_image(raw: str, max_bytes: int) -> tuple[str, str]:
    """Validate a client image; return (canonical base64, mime). Accepts a bare string or a data URL."""
    s = raw.strip()
    if s.startswith("data:") and "," in s:
        s = s.split(",", 1)[1]
    if len(s) > (max_bytes * 4) // 3 + 8:
        raise APIError(413, "image_too_large", f"Photo is too large (max {max_bytes // 1_000_000} MB).")
    try:
        data = base64.b64decode("".join(s.split()), validate=True)
    except (binascii.Error, ValueError):
        raise APIError(400, "invalid_image", "image_base64 is not valid base64.")
    if not data or len(data) > max_bytes:
        raise APIError(413, "image_too_large", f"Photo is too large (max {max_bytes // 1_000_000} MB).")
    mime = next((m for magic, m in _IMAGE_MAGIC if data.startswith(magic)), None)
    if mime is None and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        mime = "image/webp"
    if mime is None:
        raise APIError(400, "invalid_image", "Photo must be a JPEG, PNG, WebP or GIF image.")
    return base64.b64encode(data).decode("ascii"), mime


def screen_input(text: str, has_image: bool) -> str | None:
    """Content-policy hook. Returns a user-facing rejection reason, or None to allow.

    Today this is a pass-through: OpenRouter and the underlying model providers already enforce their
    own usage policies (flagged requests come back as upstream 403 -> our 400 content_rejected, and
    are not charged). Add product-specific checks HERE, before any money is reserved or any model is
    called - e.g. an age-appropriate filter for school students, a moderation-model pre-check on
    text/photos, or exam-integrity rules for live tests.
    """
    return None


# ---------------------------------------------------------------- request building

def uses_explicit_cache_breakpoints(model_id: str) -> bool:
    """Anthropic needs cache_control breakpoints; the other major providers cache automatically.
    (Gemini also accepts breakpoints, but its implicit caching has free writes, so we rely on that.)"""
    return model_id.startswith("anthropic/")


def _user_parts(content: str, image_b64: str | None, image_mime: str | None) -> list[dict]:
    parts: list[dict] = []
    if image_b64:
        parts.append({"type": "image_url", "image_url": {"url": f"data:{image_mime};base64,{image_b64}"}})
    parts.append({"type": "text", "text": content})
    return parts


def build_messages(history: list[dict], model_id: str, cache_ttl: str = "5m") -> list[dict]:
    """history: stored rows (role, content, image_base64, image_mime), oldest first, ending with the
    new user turn. Output shape is deterministic so the prefix is identical from turn to turn."""
    explicit = uses_explicit_cache_breakpoints(model_id)
    marker = {"type": "ephemeral"} if cache_ttl == "5m" else {"type": "ephemeral", "ttl": cache_ttl}
    system_block = {"type": "text", "text": SYSTEM_PROMPT}
    if explicit:
        system_block["cache_control"] = dict(marker)
    messages: list[dict] = [{"role": "system", "content": [system_block]}]
    for row in history:
        if row["role"] == "user":
            messages.append({"role": "user",
                             "content": _user_parts(row["content"], row.get("image_base64"), row.get("image_mime"))})
        else:
            messages.append({"role": "assistant", "content": row["content"]})
    if explicit:
        last_user = next(m for m in reversed(messages) if m["role"] == "user")
        last_user["content"][-1]["cache_control"] = dict(marker)
    return messages


def estimate_tokens(text: str) -> int:
    """Conservative upper-ish estimate without a tokenizer: UTF-8 bytes / 3. English averages ~4 chars
    per token; Devanagari is 3 bytes per char at roughly a token per char."""
    return len(text.encode("utf-8")) // 3 + 4


def estimate_input_tokens(history: list[dict], image_tokens: int) -> int:
    total = estimate_tokens(SYSTEM_PROMPT)
    for row in history:
        total += estimate_tokens(row["content"])
        if row.get("image_base64"):
            total += image_tokens
    return total


def reasoning_param(model: ModelInfo, prefs: tuple[str, ...]) -> dict | None:
    if not model.reasoning_efforts:
        return {"exclude": True} if "reasoning" in model.supported_parameters else None
    effort = next((e for e in prefs if e in model.reasoning_efforts), None)
    if effort is None:  # none of our preferences: take the lowest the model offers
        known = [e for e in _EFFORT_ORDER if e in model.reasoning_efforts]
        effort = known[0] if known else model.reasoning_efforts[-1]
    return {"effort": effort, "exclude": True}


# ---------------------------------------------------------------- cost

def worst_case_usd(model: ModelInfo, input_tokens: int, output_tokens: int, n_images: int) -> Decimal:
    """Upper bound for one call: no cache discount, cache writes at their (higher) price."""
    return (input_tokens * model.worst_input_price + output_tokens * model.completion
            + n_images * model.image + model.request)


def actual_cost_usd(usage: dict, model: ModelInfo) -> Decimal:
    """Real cost of a call. OpenRouter reports it directly (usage.cost, in credits = USD, already net of
    cache discounts); if absent, recompute from token counts and the model's prices."""
    cost = usage.get("cost")
    if cost is not None:
        try:
            return max(Decimal(str(cost)), Decimal(0))
        except ArithmeticError:
            pass
    details = usage.get("prompt_tokens_details") or {}
    prompt = int(usage.get("prompt_tokens") or 0)
    cached = int(details.get("cached_tokens") or 0)
    written = int(details.get("cache_write_tokens") or 0)
    fresh = max(prompt - cached - written, 0)
    return (fresh * model.prompt + cached * (model.cache_read if model.cache_read is not None else model.prompt)
            + written * (model.cache_write if model.cache_write is not None else model.prompt)
            + int(usage.get("completion_tokens") or 0) * model.completion + model.request)


def estimate_message_inr(model: ModelInfo, settings: Settings) -> float:
    """Typical message (GET /v1/models): standalone question, no cache, user-visible price."""
    usd = (settings.est_prompt_tokens * model.prompt + settings.est_completion_tokens * model.completion
           + model.request)
    return units_to_inr(usd_to_units(usd, settings.usd_inr_rate, settings.margin_pct))


def _reply_text(body: dict) -> str:
    try:
        content = body["choices"][0]["message"].get("content")
    except (KeyError, IndexError, TypeError, AttributeError):
        raise APIError(502, "upstream_error", "The AI service returned an unexpected response.")
    if isinstance(content, list):
        content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
    return (content or "").strip()


# finish_reason values meaning "stopped at the max_tokens limit". OpenRouter normalises finish_reason
# to "length"; native_finish_reason is the provider's raw value (Anthropic "max_tokens", Google
# "MAX_TOKENS", OpenAI "length", Responses-style "max_output_tokens").
_LENGTH_REASONS = frozenset({"length", "max_tokens", "max_output_tokens"})


def _was_truncated(body: dict) -> bool:
    """True only when the upstream explicitly says the reply hit the token limit. Anything missing,
    malformed or ambiguous is False: never claim a truncation the provider didn't report."""
    try:
        choice = body["choices"][0]
    except (KeyError, IndexError, TypeError):
        return False
    if not isinstance(choice, dict):
        return False
    reason = choice.get("finish_reason")
    if isinstance(reason, str) and reason:
        return reason.lower() == "length"
    # Normalised value absent: fall back to the provider's raw reason.
    native = choice.get("native_finish_reason")
    return isinstance(native, str) and native.lower() in _LENGTH_REASONS


def _map_upstream(e: UpstreamError) -> APIError:
    if e.status == 402:
        log.critical("OpenRouter account is out of credit: %s", e.message)
        return APIError(503, "service_unavailable", "The AI service is temporarily unavailable. You were not charged.")
    if e.status == 403:
        return APIError(400, "content_rejected", "This request was blocked by the AI provider's safety policy. "
                                                 "You were not charged.")
    if e.status == 429:
        return APIError(503, "model_busy", "The AI model is busy right now. Please try again. You were not charged.")
    if e.status in (408, 504):
        return APIError(504, "model_timeout", "The AI model took too long to answer. You were not charged.")
    log.error("upstream error %s: %s", e.status, e.message)
    return APIError(502, "upstream_error", "The AI service had a problem. You were not charged.")


# ---------------------------------------------------------------- orchestration

@dataclass
class ChatRequestData:
    tier: str
    message: str
    conversation_id: str | None
    image_base64: str | None


class ChatService:
    def __init__(self, settings: Settings, db: Database, wallet: Wallet, catalog: Catalog, client):
        self.s = settings
        self.db = db
        self.wallet = wallet
        self.catalog = catalog
        self.client = client
        self._locks: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()

    def _lock(self, key: str) -> asyncio.Lock:
        lock = self._locks.get(key)
        if lock is None:
            lock = asyncio.Lock()
            self._locks[key] = lock
        return lock

    def _history(self, user_id: str, conversation_id: str | None) -> list[dict]:
        if conversation_id is None:
            return []
        conv = self.db.one("SELECT user_id FROM conversations WHERE id=?", (conversation_id,))
        if conv is None or conv["user_id"] != user_id:
            raise APIError(404, "conversation_not_found", "Conversation not found. Start a new one.")
        return self.db.q("SELECT role, content, image_base64, image_mime FROM messages "
                         "WHERE conversation_id=? ORDER BY id", (conversation_id,))

    async def handle(self, user_id: str, req: ChatRequestData) -> dict:
        s = self.s
        text = (req.message or "").strip()
        if len(text) > s.max_message_chars:
            raise APIError(413, "message_too_long", f"Message is too long (max {s.max_message_chars} characters).")
        image_b64 = image_mime = None
        if req.image_base64:
            image_b64, image_mime = decode_image(req.image_base64, s.max_image_bytes)
            text = text or IMAGE_ONLY_PROMPT
        if not text:
            raise APIError(400, "empty_message", "Type a question or attach a photo.")
        reason = screen_input(text, image_b64 is not None)
        if reason:
            raise APIError(400, "content_rejected", reason)

        lock_key = req.conversation_id or new_id("new")
        async with self._lock(lock_key):
            return await self._handle_locked(user_id, req.tier, text, image_b64, image_mime, req.conversation_id)

    async def _handle_locked(self, user_id: str, tier: str, text: str, image_b64: str | None,
                             image_mime: str | None, conversation_id: str | None) -> dict:
        s = self.s
        history = self._history(user_id, conversation_id)
        new_turn = {"role": "user", "content": text, "image_base64": image_b64, "image_mime": image_mime}
        turns = history + [new_turn]
        n_images = sum(1 for t in turns if t.get("image_base64"))

        choice = self.catalog.tier(tier)
        model = choice.model_for(needs_vision=n_images > 0)
        if model is None:
            raise APIError(400, "image_not_supported", f"Photos are not available on the {tier} tier right now.")

        input_tokens = estimate_input_tokens(turns, s.image_token_estimate)
        max_out = min(s.max_output_tokens, model.max_completion_tokens or s.max_output_tokens)
        if input_tokens > s.max_input_tokens or (model.context_length and
                                                 input_tokens + s.min_output_tokens > model.context_length):
            raise APIError(413, "conversation_too_long",
                           "This conversation is too long. Please start a new conversation.")

        # --- pre-authorisation: never call the model unless the wallet covers the worst case ---
        available_paise = self.wallet.available_paise(user_id)
        available_units = available_paise * UNITS_PER_PAISA
        mult = charge_multiplier(s.usd_inr_rate, s.margin_pct)

        def worst_units(out_tokens: int) -> int:
            return usd_to_units(worst_case_usd(model, input_tokens, out_tokens, n_images), s.usd_inr_rate, s.margin_pct)

        if worst_units(max_out) > available_units:
            # Not enough for a full-length answer: allow a shorter one if it still fits a useful minimum.
            fixed_usd = worst_case_usd(model, input_tokens, 0, n_images)
            budget_usd = Decimal(available_units) / UNITS_PER_RUPEE / mult
            affordable = floor_div(budget_usd - fixed_usd, model.completion) if budget_usd > fixed_usd else 0
            max_out = min(max_out, affordable)
            while max_out >= s.min_output_tokens and worst_units(max_out) > available_units:
                max_out -= 1  # absorb Decimal rounding at the edge
            if max_out < s.min_output_tokens:
                required = units_to_paise_ceil(worst_units(s.min_output_tokens))
                raise APIError(
                    402, "insufficient_balance",
                    f"Your balance (₹{paise_to_inr(available_paise):.2f}) is too low for one {tier.capitalize()} "
                    f"message (needs up to ₹{paise_to_inr(required):.2f}). Top up or switch to a cheaper tier.",
                    balance_inr=paise_to_inr(available_paise), required_inr=paise_to_inr(required), tier=tier)

        hold_units = worst_units(max_out)
        hold_paise = max(1, units_to_paise_ceil(hold_units))
        hold_id = self.wallet.reserve(user_id, hold_paise)
        if hold_id is None:  # balance changed under us (another request in flight)
            raise APIError(402, "insufficient_balance", "Your balance is too low for this message. Top up to continue.",
                           balance_inr=paise_to_inr(self.wallet.available_paise(user_id)),
                           required_inr=paise_to_inr(hold_paise), tier=tier)

        conv_id = conversation_id or new_id("conv")
        payload = {
            "model": model.id,
            "messages": build_messages(turns, model.id, s.anthropic_cache_ttl),
            "max_tokens": max_out,
            "session_id": conv_id,  # sticky provider routing => cache hits across turns
            "user": user_id,  # opaque random id; lets OpenRouter/providers attribute abuse per end user
        }
        reasoning = reasoning_param(model, s.reasoning_effort.get(tier, ()))
        if reasoning is not None:
            payload["reasoning"] = reasoning

        try:
            body = await self.client.chat(payload)
            reply = _reply_text(body)
            truncated = _was_truncated(body)
        except UpstreamError as e:
            self.wallet.release(hold_id)
            raise _map_upstream(e)
        except BaseException:
            self.wallet.release(hold_id)
            raise

        usage = body.get("usage") or {}
        if truncated:
            log.info("reply hit max_tokens=%d on %s (conversation %s)", max_out, model.id, conv_id)
        cost_usd = actual_cost_usd(usage, model)
        cost_units = usd_to_units(cost_usd, s.usd_inr_rate, s.margin_pct)
        # Never bill above what was pre-authorised; an estimate miss is the platform's loss, not the user's.
        billable = min(cost_units, hold_paise * UNITS_PER_PAISA)
        if cost_units > billable:
            log.warning("actual cost exceeded hold for %s (%d > %d units); absorbing", model.id, cost_units, billable)
        details = usage.get("prompt_tokens_details") or {}
        now = time.time()
        with self.db.tx() as c:
            if conversation_id is None:
                c.execute("INSERT INTO conversations(id, user_id, created_at, updated_at) VALUES(?,?,?,?)",
                          (conv_id, user_id, now, now))
            else:
                c.execute("UPDATE conversations SET updated_at=? WHERE id=?", (now, conv_id))
            c.execute("INSERT INTO messages(conversation_id, role, content, image_base64, image_mime, created_at, tier) "
                      "VALUES(?,?,?,?,?,?,?)", (conv_id, "user", text, image_b64, image_mime, now, tier))
            c.execute("INSERT INTO messages(conversation_id, role, content, created_at, tier, model, prompt_tokens, "
                      "completion_tokens, cached_tokens, cache_write_tokens, cost_usd, charge_units) "
                      "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                      (conv_id, "assistant", reply, now, tier, body.get("model") or model.id,
                       usage.get("prompt_tokens"), usage.get("completion_tokens"), details.get("cached_tokens"),
                       details.get("cache_write_tokens"), str(cost_usd), billable))
            result = self.wallet.settle(c, user_id, hold_id, billable, ref=conv_id,
                                        absorbed_units=cost_units - billable)
        return {
            "reply": reply,
            "conversation_id": conv_id,
            "cost_inr": units_to_inr(billable),
            "wallet_balance_inr": paise_to_inr(result.available_paise),
            "truncated": truncated,
        }
