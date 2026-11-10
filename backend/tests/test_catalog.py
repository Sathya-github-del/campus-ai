"""Tier-to-model selection from OpenRouter's live list."""
import json
from decimal import Decimal

from app.catalog import FALLBACK_MODELS, Catalog, ModelInfo, is_eligible, select_tiers
from app.config import Settings
from app.db import DB

from .conftest import LIVE_MODELS, model


def parse(raw):
    return [m for m in (ModelInfo.from_openrouter(d) for d in raw) if m]


def test_tiers_bucketed_by_price():
    tiers = select_tiers(parse(LIVE_MODELS), Settings())
    assert tiers["fast"].model.id == "google/flash-vision"      # cache discount + vision beat cheaper llama
    assert tiers["smart"].model.id == "openai/mid"               # vision beats newer text-only mistral
    assert tiers["expert"].model.id == "anthropic/claude-top"
    for choice in tiers.values():
        assert choice.vision_model is choice.model


def test_ineligible_models_never_selected():
    s = Settings()
    by_id = {m.id: m for m in parse(LIVE_MODELS)}
    for bad in ("openrouter/auto", "google/flash-vision:free", "openai/mid:batch", "randomco/cheapest",
                "google/image-maker", "openai/retiring"):
        assert not is_eligible(by_id[bad], s), bad
    chosen = {c.model.id for c in select_tiers(list(by_id.values()), s).values()}
    assert "openai/too-pricey" not in chosen  # above the Expert ceiling


def test_bands_are_configurable():
    s = Settings(fast_max_usd_per_m=Decimal("0.1"), smart_max_usd_per_m=Decimal("0.5"),
                 expert_max_usd_per_m=Decimal("2"))
    tiers = select_tiers(parse(LIVE_MODELS), s)
    assert tiers["smart"].model.id == "google/flash-vision"
    assert tiers["expert"].model.id == "openai/mid"


def test_text_only_pick_gets_a_vision_companion():
    raw = [model("deepseek/text-cached", "0.0000001", "0.0000002", created=9, cache_read="0.00000001"),
           model("qwen/vl", "0.0000001", "0.0000004", created=1, vision=True)]
    s = Settings(trusted_providers=())
    fast = select_tiers(parse(raw), s)["fast"]
    assert fast.model.id == "deepseek/text-cached"
    assert fast.model_for(needs_vision=True).id == "qwen/vl"
    assert fast.model_for(needs_vision=False).id == "deepseek/text-cached"


def test_pin_overrides_price_pick():
    s = Settings(model_pins={"fast": "meta-llama/llama-text"})
    assert select_tiers(parse(LIVE_MODELS), s)["fast"].model.id == "meta-llama/llama-text"


def test_empty_list_uses_fallback_defaults():
    tiers = select_tiers([], Settings(), quiet=True)
    for t in ("fast", "smart", "expert"):
        assert tiers[t].model.id == FALLBACK_MODELS[t].id
    # fallback tiers keep the intended price ordering
    assert (tiers["fast"].model.blended_usd_per_m < tiers["smart"].model.blended_usd_per_m
            < tiers["expert"].model.blended_usd_per_m)


async def test_refresh_persists_and_survives_outage(tmp_path, fake):
    db = DB(tmp_path / "c.db")
    cat = Catalog(Settings(), db)
    assert cat.source == "fallback"
    assert await cat.refresh(fake) is True
    assert cat.tier("expert").model.id == "anthropic/claude-top"
    fake.list_fail = True
    assert await cat.refresh(fake) is False
    assert cat.tier("expert").model.id == "anthropic/claude-top"  # previous choice kept
    # restart with no network: last good list comes back from SQLite, not the hardcoded fallback
    cat2 = Catalog(Settings(), db)
    assert cat2.load_saved() is True
    assert cat2.source == "saved" and cat2.tier("expert").model.id == "anthropic/claude-top"
    assert json.loads(db.kv_get(Catalog.KV_KEY))
