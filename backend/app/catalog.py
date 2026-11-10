"""Model catalog: map OpenRouter's live model list onto the Fast / Smart / Expert tiers by price.

No model ID is hardcoded for normal operation. On startup and every MODELS_REFRESH_SECONDS we fetch
GET /api/v1/models, keep the models that suit an interactive student chat app, bucket them by blended
price per token into the three tier bands, and pick one model per tier. If OpenRouter is unreachable we
use the last list that worked (persisted in SQLite), and only on a cold start with no network do we
fall back to FALLBACK_MODELS below (a dated price snapshot; replaced on the first successful refresh).
"""
from __future__ import annotations

import json
import logging
import time
from dataclasses import asdict, dataclass, field
from decimal import Decimal, InvalidOperation

from .config import TIERS, Settings

log = logging.getLogger(__name__)

TIER_LABELS = {"fast": "Fast", "smart": "Smart", "expert": "Expert"}
MIN_CONTEXT = 16_000
CHAT_ENDPOINT = "/api/v1/chat/completions"


def _price(raw) -> Decimal | None:
    if raw is None or raw == "":
        return None
    try:
        d = Decimal(str(raw))
    except InvalidOperation:
        return None
    return d if d.is_finite() else None


@dataclass(frozen=True)
class ModelInfo:
    id: str
    name: str
    created: int
    prompt: Decimal  # USD per input token
    completion: Decimal  # USD per output token
    cache_read: Decimal | None = None  # USD per cached input token read
    cache_write: Decimal | None = None  # USD per input token written to cache (explicit-cache providers)
    image: Decimal = Decimal(0)  # USD per input image, if the model prices images separately
    request: Decimal = Decimal(0)  # USD flat per request
    context_length: int = 0
    max_completion_tokens: int | None = None
    vision: bool = False
    reasoning_mandatory: bool = False
    reasoning_efforts: tuple[str, ...] = ()  # efforts the model accepts via the `reasoning` parameter
    supported_parameters: frozenset[str] = field(default_factory=frozenset)
    text_chat: bool = True  # text in, text-only out, served on /chat/completions
    expiring: bool = False
    variable_price: bool = False

    @property
    def author(self) -> str:
        return self.id.split("/", 1)[0].lstrip("~").lower()

    @property
    def blended_usd_per_m(self) -> Decimal:
        """3:1 input:output blend (chat requests resend history, so input dominates)."""
        return (self.prompt * 3 + self.completion) / 4 * 1_000_000

    @property
    def has_cache_discount(self) -> bool:
        return self.cache_read is not None and self.cache_read < self.prompt

    @property
    def reasoning_controllable(self) -> bool:
        """False only when the model always reasons and we cannot turn the effort down."""
        return not self.reasoning_mandatory or bool(self.reasoning_efforts)

    @property
    def worst_input_price(self) -> Decimal:
        """Per-token input price upper bound: cache *writes* can cost more than plain input (1.25x)."""
        return max(self.prompt, self.cache_write or Decimal(0))

    @classmethod
    def from_openrouter(cls, d: dict) -> "ModelInfo | None":
        try:
            p = d.get("pricing") or {}
            prompt, completion = _price(p.get("prompt")), _price(p.get("completion"))
            if prompt is None or completion is None:
                return None
            arch = d.get("architecture") or {}
            ins = arch.get("input_modalities") or []
            outs = arch.get("output_modalities") or []
            endpoints = d.get("endpoints")
            top = d.get("top_provider") or {}
            return cls(
                id=str(d["id"]),
                name=str(d.get("name") or d["id"]),
                created=int(d.get("created") or 0),
                prompt=prompt,
                completion=completion,
                cache_read=_price(p.get("input_cache_read")),
                cache_write=_price(p.get("input_cache_write")),
                image=_price(p.get("image")) or Decimal(0),
                request=_price(p.get("request")) or Decimal(0),
                context_length=int(d.get("context_length") or top.get("context_length") or 0),
                max_completion_tokens=top.get("max_completion_tokens") or None,
                vision="image" in ins,
                reasoning_mandatory=bool((d.get("reasoning") or {}).get("mandatory")),
                reasoning_efforts=tuple((d.get("reasoning") or {}).get("supported_efforts") or ()),
                supported_parameters=frozenset(d.get("supported_parameters") or ()),
                text_chat="text" in ins and list(outs) == ["text"]
                and (endpoints is None or CHAT_ENDPOINT in endpoints),
                expiring=bool(d.get("expiration_date")),
                variable_price=prompt < 0 or completion < 0,
            )
        except (KeyError, TypeError, ValueError):
            return None

    def to_json(self) -> dict:
        out = asdict(self)
        for k, v in out.items():
            if isinstance(v, Decimal):
                out[k] = str(v)
            elif isinstance(v, frozenset):
                out[k] = sorted(v)
            elif isinstance(v, tuple):
                out[k] = list(v)
        return out

    @classmethod
    def from_json(cls, d: dict) -> "ModelInfo":
        d = dict(d)
        for k in ("prompt", "completion", "image", "request"):
            d[k] = Decimal(d[k])
        for k in ("cache_read", "cache_write"):
            d[k] = Decimal(d[k]) if d.get(k) is not None else None
        d["supported_parameters"] = frozenset(d.get("supported_parameters") or ())
        d["reasoning_efforts"] = tuple(d.get("reasoning_efforts") or ())
        return cls(**d)


# Cold-start fallback ONLY (no network and no previously saved list). Prices snapshotted from
# openrouter.ai/api/v1/models on 2026-11-10; these IDs will go stale, which is fine because they are
# replaced by the live list on the first successful refresh.
FALLBACK_MODELS: dict[str, ModelInfo] = {
    "fast": ModelInfo(
        id="anthropic/claude-haiku-5.5", name="fallback fast", created=0,
        prompt=Decimal("0.0000001"), completion=Decimal("0.0000005"), cache_read=Decimal("0.00000001"),
        cache_write=Decimal("0.000000125"), context_length=1_000_000, vision=True,
        reasoning_efforts=("high", "medium", "low", "none")),
    "smart": ModelInfo(
        id="openai/gpt-6.1-terra", name="fallback smart", created=0,
        prompt=Decimal("0.000001"), completion=Decimal("0.000005"), cache_read=Decimal("0.00000005"),
        cache_write=Decimal("0.00000125"), context_length=400_000, vision=True),
    "expert": ModelInfo(
        id="anthropic/claude-opus-5.5", name="fallback expert", created=0,
        prompt=Decimal("0.000004"), completion=Decimal("0.00002"), cache_read=Decimal("0.0000002"),
        cache_write=Decimal("0.000005"), context_length=1_000_000, vision=True, reasoning_mandatory=True,
        reasoning_efforts=("high", "medium", "low")),
}


def is_eligible(m: ModelInfo, settings: Settings) -> bool:
    """Suitable for an interactive, text-answer student chat at a stable, known price."""
    if m.variable_price or m.prompt <= 0 or m.completion <= 0:
        return False  # routers with dynamic pricing, and free models (rate-limited, may log prompts)
    if ":" in m.id or m.id.startswith("~") or m.author == "openrouter":
        return False  # :free / :batch / :online variants, moving "~latest" aliases, meta-routers
    if not m.text_chat or m.expiring or m.context_length < MIN_CONTEXT:
        return False
    if settings.trusted_providers and m.author not in settings.trusted_providers:
        return False
    return True


def _rank(m: ModelInfo) -> tuple:
    # Preference inside a price band, most important first:
    #  1. has a cache-read discount (prompt caching is a core requirement, BLUEPRINT §7)
    #  2. accepts images (homework-photo feature works without switching model)
    #  3. reasoning can be turned down (uncontrolled reasoning burns billed output tokens and latency)
    #  4. newest
    return (m.has_cache_discount, m.vision, m.reasoning_controllable, m.created, m.id)


@dataclass(frozen=True)
class TierChoice:
    tier: str
    model: ModelInfo
    vision_model: ModelInfo | None  # same as model when it accepts images

    def model_for(self, needs_vision: bool) -> ModelInfo | None:
        return self.vision_model if needs_vision else self.model


def select_tiers(models: list[ModelInfo], settings: Settings, quiet: bool = False) -> dict[str, TierChoice]:
    by_id = {m.id: m for m in models}
    eligible = [m for m in models if is_eligible(m, settings)]
    choices: dict[str, TierChoice] = {}
    for tier in TIERS:
        lo, hi = settings.tier_bands[tier]
        band = sorted((m for m in eligible if lo < m.blended_usd_per_m <= hi), key=_rank, reverse=True)
        pinned = settings.model_pins.get(tier)
        if pinned and pinned in by_id:
            model = by_id[pinned]
        elif pinned:
            log.warning("MODEL_%s=%s is not in OpenRouter's list; selecting by price instead", tier.upper(), pinned)
            model = band[0] if band else None
        else:
            model = band[0] if band else None
        if model is None:
            fb = FALLBACK_MODELS[tier]
            model = by_id.get(fb.id, fb)
            if not quiet:
                log.warning("no eligible model in the %s price band; using fallback %s", tier, model.id)
        vision = model if model.vision else next((m for m in band if m.vision), None)
        if vision is None:
            fb = FALLBACK_MODELS[tier]
            vision = by_id.get(fb.id, fb) if fb.vision else None
        choices[tier] = TierChoice(tier, model, vision)
    return choices


class Catalog:
    """Holds the current tier choices. Thread/async safe by whole-object replacement."""

    KV_KEY = "openrouter_models"

    def __init__(self, settings: Settings, db=None):
        self.settings = settings
        self.db = db
        self.source = "fallback"
        self.updated_at = 0.0
        self.tiers = select_tiers([], settings, quiet=True)

    def load(self, models: list[ModelInfo], source: str) -> None:
        self.tiers = select_tiers(models, self.settings)
        self.source = source
        self.updated_at = time.time()
        log.info("tiers (%s): %s", source, ", ".join(f"{t}={c.model.id}" for t, c in self.tiers.items()))

    def load_raw(self, raw: list[dict], source: str = "live") -> int:
        models = [m for m in (ModelInfo.from_openrouter(d) for d in raw) if m is not None]
        if not models:
            raise ValueError("OpenRouter returned no usable models")
        self.load(models, source)
        if self.db is not None:
            self.db.kv_set(self.KV_KEY, json.dumps([m.to_json() for m in models]))
        return len(models)

    def load_saved(self) -> bool:
        if self.db is None:
            return False
        raw = self.db.kv_get(self.KV_KEY)
        if not raw:
            return False
        try:
            models = [ModelInfo.from_json(d) for d in json.loads(raw)]
        except (ValueError, TypeError, KeyError):
            log.warning("saved model list is unreadable; ignoring it")
            return False
        self.load(models, "saved")
        return True

    async def refresh(self, client) -> bool:
        try:
            n = self.load_raw(await client.list_models(), "live")
            log.info("model catalog refreshed: %d models", n)
            return True
        except Exception as e:  # network, JSON, empty list: keep serving the previous choice
            log.warning("model catalog refresh failed (%s); keeping %s tiers", e, self.source)
            return False

    def tier(self, name: str) -> TierChoice:
        return self.tiers[name]
