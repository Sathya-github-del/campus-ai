"""Configuration: environment variables only (see .env.example). Validated once at startup."""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path

log = logging.getLogger(__name__)

TIERS = ("fast", "smart", "expert")


class ConfigError(ValueError):
    pass


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default).strip()


def _dec(name: str, default: str) -> Decimal:
    raw = _env(name, default)
    try:
        return Decimal(raw)
    except InvalidOperation as e:
        raise ConfigError(f"{name} must be a number, got {raw!r}") from e


def _int(name: str, default: int) -> int:
    raw = _env(name, str(default))
    try:
        return int(raw)
    except ValueError as e:
        raise ConfigError(f"{name} must be an integer, got {raw!r}") from e


def _bool(name: str, default: bool) -> bool:
    return _env(name, "true" if default else "false").lower() in ("1", "true", "yes", "on")


@dataclass
class Settings:
    openrouter_api_key: str = ""
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    # Sent as HTTP-Referer / X-Title so OpenRouter attributes traffic to this app.
    app_url: str = ""
    app_name: str = "campus-ai"
    upstream_timeout_seconds: float = 90.0

    # --- money ---
    # Rupees per US dollar. OpenRouter bills in USD; every user-visible charge is converted with this.
    usd_inr_rate: Decimal = Decimal("93")
    # Fee recovery as a percent OF THE USER-VISIBLE CHARGE: charge = real_cost / (1 - MARGIN_PCT/100).
    # Default 17.65 = the fee stack between the student's rupee and OpenRouter credit (BLUEPRINT §7:
    # Play 11% -> Razorpay 2.36% -> OpenRouter 5.5%, Rs20 -> Rs16.47). A student who spends the whole
    # wallet breaks even; profit comes from unspent balance (breakage), as modelled in BLUEPRINT §12.
    # It is NOT a per-message markup; don't raise it above the fee stack. See NOTES.md §1.
    margin_pct: Decimal = Decimal("17.65")
    # The §7 fee stack. Used only to warn when MARGIN_PCT deviates from it.
    platform_fee_pct: Decimal = Decimal("17.65")
    min_topup_inr: Decimal = Decimal("20")
    max_topup_inr: Decimal = Decimal("2000")
    max_wallet_balance_inr: Decimal = Decimal("10000")

    # --- storage / runtime ---
    # Postgres connection string (production, e.g. Neon). When set it wins over database_path.
    database_url: str = ""
    # SQLite file used when database_url is empty (local dev and tests).
    database_path: Path = Path("data/campus_ai.db")
    # Max Postgres connections this process opens (ignored for SQLite).
    db_pool_size: int = 10
    dev_mode: bool = True
    payment_provider: str = "mock"
    public_base_url: str = "http://localhost:8000"

    # --- abuse limits (per token unless noted) ---
    rate_limit_per_minute: int = 60
    chat_rate_limit_per_minute: int = 12
    register_limit_per_hour_per_ip: int = 20

    # --- model catalog / tiers ---
    models_refresh_seconds: int = 6 * 3600
    # Tier price bands, in blended USD per 1M tokens (3 parts input : 1 part output).
    fast_max_usd_per_m: Decimal = Decimal("0.75")
    smart_max_usd_per_m: Decimal = Decimal("4")
    expert_max_usd_per_m: Decimal = Decimal("25")
    # Only pick models from these OpenRouter author prefixes (empty = any author).
    trusted_providers: tuple[str, ...] = (
        "anthropic", "openai", "google", "meta-llama", "mistralai", "qwen", "deepseek", "x-ai",
    )
    # Optional hard pins, e.g. MODEL_FAST=google/gemini-3.5-flash-lite (still priced from the live list).
    model_pins: dict[str, str] = field(default_factory=dict)

    # --- per-message limits / estimates ---
    max_output_tokens: int = 5000
    min_output_tokens: int = 256
    max_input_tokens: int = 32000
    max_message_chars: int = 8000
    max_image_bytes: int = 5_000_000
    image_token_estimate: int = 1600
    # "Typical message" used for the per-message estimate shown in GET /v1/models (BLUEPRINT §7 numbers
    # plus the system prompt).
    est_prompt_tokens: int = 300
    est_completion_tokens: int = 400
    # Reasoning effort per tier, as a preference list; the first effort the model supports is sent.
    # Reasoning tokens are billed as output, so Fast defaults to none. REASONING_EFFORT_FAST=none,low
    reasoning_effort: dict[str, tuple[str, ...]] = field(default_factory=lambda: {
        "fast": ("none", "minimal", "low"),
        "smart": ("low", "minimal", "none"),
        "expert": ("medium", "low"),
    })
    # Anthropic prompt-cache TTL: "5m" (write = 1.25x input) or "1h" (write = 2x input).
    anthropic_cache_ttl: str = "5m"

    @classmethod
    def from_env(cls, load_dotenv_file: bool = True) -> "Settings":
        if load_dotenv_file:
            try:
                from dotenv import load_dotenv

                load_dotenv()
            except ImportError:  # optional
                pass
        trusted = _env("TRUSTED_PROVIDERS", ",".join(cls.trusted_providers))
        s = cls(
            openrouter_api_key=_env("OPENROUTER_API_KEY", ""),
            openrouter_base_url=_env("OPENROUTER_BASE_URL", cls.openrouter_base_url).rstrip("/"),
            app_url=_env("APP_URL", ""),
            app_name=_env("APP_NAME", cls.app_name),
            upstream_timeout_seconds=float(_dec("UPSTREAM_TIMEOUT_SECONDS", "90")),
            usd_inr_rate=_dec("USD_INR_RATE", "93"),
            margin_pct=_dec("MARGIN_PCT", "17.65"),
            platform_fee_pct=_dec("PLATFORM_FEE_PCT", "17.65"),
            min_topup_inr=_dec("MIN_TOPUP_INR", "20"),
            max_topup_inr=_dec("MAX_TOPUP_INR", "2000"),
            max_wallet_balance_inr=_dec("MAX_WALLET_BALANCE_INR", "10000"),
            database_url=_env("DATABASE_URL", ""),
            database_path=Path(_env("DATABASE_PATH", "data/campus_ai.db")),
            db_pool_size=_int("DB_POOL_SIZE", 10),
            dev_mode=_bool("DEV_MODE", True),
            payment_provider=_env("PAYMENT_PROVIDER", "mock").lower(),
            public_base_url=_env("PUBLIC_BASE_URL", cls.public_base_url).rstrip("/"),
            rate_limit_per_minute=_int("RATE_LIMIT_PER_MINUTE", 60),
            chat_rate_limit_per_minute=_int("CHAT_RATE_LIMIT_PER_MINUTE", 12),
            register_limit_per_hour_per_ip=_int("REGISTER_LIMIT_PER_HOUR_PER_IP", 20),
            models_refresh_seconds=_int("MODELS_REFRESH_SECONDS", 6 * 3600),
            fast_max_usd_per_m=_dec("FAST_MAX_USD_PER_M", "0.75"),
            smart_max_usd_per_m=_dec("SMART_MAX_USD_PER_M", "4"),
            expert_max_usd_per_m=_dec("EXPERT_MAX_USD_PER_M", "25"),
            trusted_providers=tuple(p.strip().lower() for p in trusted.split(",") if p.strip()),
            model_pins={t: _env(f"MODEL_{t.upper()}", "") for t in TIERS if _env(f"MODEL_{t.upper()}", "")},
            max_output_tokens=_int("MAX_OUTPUT_TOKENS", 5000),
            min_output_tokens=_int("MIN_OUTPUT_TOKENS", 256),
            max_input_tokens=_int("MAX_INPUT_TOKENS", 32000),
            max_message_chars=_int("MAX_MESSAGE_CHARS", 8000),
            max_image_bytes=_int("MAX_IMAGE_BYTES", 5_000_000),
            image_token_estimate=_int("IMAGE_TOKEN_ESTIMATE", 1600),
            est_prompt_tokens=_int("EST_PROMPT_TOKENS", 300),
            est_completion_tokens=_int("EST_COMPLETION_TOKENS", 400),
            anthropic_cache_ttl=_env("ANTHROPIC_CACHE_TTL", "5m"),
        )
        for t in TIERS:
            raw = _env(f"REASONING_EFFORT_{t.upper()}", "")
            if raw:
                s.reasoning_effort[t] = tuple(e.strip().lower() for e in raw.split(",") if e.strip())
        s.validate()
        if os.environ.get("RENDER") and not s.database_url:
            # Render sets RENDER=true. Its free-tier disk is wiped on every deploy/restart, so a SQLite
            # file there would silently lose every wallet balance.
            raise ConfigError("Running on Render without DATABASE_URL: wallets would be stored on an ephemeral "
                              "disk and lost on restart. Set DATABASE_URL to your Neon connection string.")
        return s

    def validate(self) -> None:
        if not (Decimal(0) <= self.margin_pct < Decimal(100)):
            raise ConfigError("MARGIN_PCT must be >= 0 and < 100")
        if self.usd_inr_rate <= 0:
            raise ConfigError("USD_INR_RATE must be positive")
        if self.min_topup_inr < 1 or self.max_topup_inr < self.min_topup_inr:
            raise ConfigError("MIN_TOPUP_INR / MAX_TOPUP_INR are inconsistent")
        if not (0 < self.fast_max_usd_per_m < self.smart_max_usd_per_m < self.expert_max_usd_per_m):
            raise ConfigError("tier bands must satisfy 0 < FAST_MAX < SMART_MAX < EXPERT_MAX")
        if not (0 < self.min_output_tokens <= self.max_output_tokens):
            raise ConfigError("MIN_OUTPUT_TOKENS must be > 0 and <= MAX_OUTPUT_TOKENS")
        if self.anthropic_cache_ttl not in ("5m", "1h"):
            raise ConfigError("ANTHROPIC_CACHE_TTL must be 5m or 1h")
        if self.database_url and not self.database_url.startswith(("postgresql://", "postgres://")):
            raise ConfigError("DATABASE_URL must be a postgresql:// connection string")
        if self.db_pool_size < 1:
            raise ConfigError("DB_POOL_SIZE must be >= 1")
        if self.payment_provider not in ("mock", "razorpay"):
            raise ConfigError("PAYMENT_PROVIDER must be mock or razorpay")
        if self.payment_provider == "mock" and not self.dev_mode:
            raise ConfigError("PAYMENT_PROVIDER=mock credits wallets for free; it is only allowed with DEV_MODE=true")
        if self.margin_pct < self.platform_fee_pct:
            log.warning(
                "MARGIN_PCT=%s is below the fee stack (%s%%, BLUEPRINT §7): every wallet rupee a student "
                "spends costs more than it brought in, so a student who spends their full balance is a loss "
                "and BLUEPRINT §12's margin no longer holds. See NOTES.md §1.",
                self.margin_pct, self.platform_fee_pct)
        elif self.margin_pct > self.platform_fee_pct:
            log.warning(
                "MARGIN_PCT=%s is above the fee stack (%s%%, BLUEPRINT §7): that adds a per-message markup "
                "beyond BLUEPRINT §12's model (charge at cost + fees, profit from breakage). See NOTES.md §1.",
                self.margin_pct, self.platform_fee_pct)
        if not self.openrouter_api_key:
            log.warning("OPENROUTER_API_KEY is not set: /v1/chat will fail upstream")

    @property
    def tier_bands(self) -> dict[str, tuple[Decimal, Decimal]]:
        return {
            "fast": (Decimal(0), self.fast_max_usd_per_m),
            "smart": (self.fast_max_usd_per_m, self.smart_max_usd_per_m),
            "expert": (self.smart_max_usd_per_m, self.expert_max_usd_per_m),
        }
