"""Prompt-caching request layout and cost helpers."""
from decimal import Decimal

from app.catalog import ModelInfo
from app.chat import (SYSTEM_PROMPT, actual_cost_usd, build_messages, reasoning_param, worst_case_usd)

from .conftest import LIVE_MODELS


def strip_markers(obj):
    if isinstance(obj, dict):
        return {k: strip_markers(v) for k, v in obj.items() if k != "cache_control"}
    if isinstance(obj, list):
        return [strip_markers(v) for v in obj]
    return obj


HISTORY = [
    {"role": "user", "content": "What is a derivative?"},
    {"role": "assistant", "content": "It measures the rate of change."},
    {"role": "user", "content": "Example?", "image_base64": "QUJD", "image_mime": "image/png"},
]


def test_anthropic_gets_two_breakpoints_system_and_latest_user():
    msgs = build_messages(HISTORY, "anthropic/claude-top")
    assert msgs[0]["role"] == "system" and msgs[0]["content"][0]["text"] == SYSTEM_PROMPT
    assert msgs[0]["content"][0]["cache_control"] == {"type": "ephemeral"}
    marked = [m for m in msgs[1:] if isinstance(m["content"], list) and "cache_control" in m["content"][-1]]
    assert marked == [msgs[-1]]
    assert msgs[-1]["content"][0]["type"] == "image_url"  # image first, text last (carries the marker)
    assert msgs[-1]["content"][-1]["type"] == "text"


def test_one_hour_ttl_option():
    msgs = build_messages(HISTORY, "anthropic/claude-top", cache_ttl="1h")
    assert msgs[0]["content"][0]["cache_control"] == {"type": "ephemeral", "ttl": "1h"}


def test_automatic_cache_providers_get_no_markers():
    msgs = build_messages(HISTORY, "openai/mid")
    assert strip_markers(msgs) == msgs


def test_prefix_is_byte_stable_across_turns():
    """Turn N's request (minus breakpoint markers) must be an exact prefix of turn N+1's."""
    for model_id in ("anthropic/claude-top", "openai/mid", "google/flash-vision"):
        turn1 = strip_markers(build_messages(HISTORY[:1], model_id))
        turn2 = strip_markers(build_messages(HISTORY, model_id))
        assert turn2[: len(turn1)] == turn1


def test_worst_case_uses_cache_write_price_and_actual_uses_reported_cost():
    m = ModelInfo.from_openrouter(LIVE_MODELS[5])  # anthropic/claude-top
    assert worst_case_usd(m, 1000, 100, 0) == 1000 * Decimal("0.00000625") + 100 * Decimal("0.000025")
    assert actual_cost_usd({"cost": 0.0123}, m) == Decimal("0.0123")


def test_actual_cost_fallback_applies_cache_discount():
    m = ModelInfo.from_openrouter(LIVE_MODELS[5])
    usage = {"prompt_tokens": 1000, "completion_tokens": 100,
             "prompt_tokens_details": {"cached_tokens": 900, "cache_write_tokens": 0}}
    expected = 100 * m.prompt + 900 * m.cache_read + 100 * m.completion
    assert actual_cost_usd(usage, m) == expected
    assert expected < 1000 * m.prompt + 100 * m.completion  # caching made it cheaper


def test_reasoning_effort_selection():
    m = ModelInfo.from_openrouter(LIVE_MODELS[5])  # mandatory, efforts high/medium/low
    assert reasoning_param(m, ("none", "minimal", "low")) == {"effort": "low", "exclude": True}
    assert reasoning_param(m, ("medium", "low")) == {"effort": "medium", "exclude": True}
    assert reasoning_param(m, ("none",)) == {"effort": "low", "exclude": True}  # lowest offered
    plain = ModelInfo.from_openrouter(LIVE_MODELS[0])
    assert reasoning_param(plain, ("none",)) is None
