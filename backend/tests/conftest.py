"""Shared fixtures: a fake OpenRouter (no network) and an app on a temp SQLite file."""
from __future__ import annotations

import base64
import copy
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from app.api import create_app
from app.config import Settings
from app.openrouter import UpstreamError


def model(id, prompt, completion, *, created=1, vision=False, cache_read=None, cache_write=None,
          out=("text",), expiration=None, reasoning=None, context=200_000):
    pricing = {"prompt": str(prompt), "completion": str(completion)}
    if cache_read is not None:
        pricing["input_cache_read"] = str(cache_read)
    if cache_write is not None:
        pricing["input_cache_write"] = str(cache_write)
    d = {
        "id": id, "name": id, "created": created, "context_length": context,
        "architecture": {"input_modalities": ["text", "image"] if vision else ["text"],
                         "output_modalities": list(out)},
        "pricing": pricing,
        "top_provider": {"context_length": context, "max_completion_tokens": 64_000},
        "supported_parameters": ["max_tokens", "temperature"] + (["reasoning"] if reasoning else []),
        "expiration_date": expiration,
        "endpoints": ["/api/v1/chat/completions"],
    }
    if reasoning:
        d["reasoning"] = reasoning
    return d


# Prices are USD per token. Blended = (3*prompt + completion)/4 per 1M tokens.
LIVE_MODELS = [
    # fast band (<= $0.75/M blended)
    model("meta-llama/llama-text", "0.0000001", "0.0000003", created=50),                     # no cache, text only
    model("google/flash-vision", "0.0000001", "0.0000005", created=40, vision=True, cache_read="0.000000025"),
    model("google/flash-old", "0.0000001", "0.0000004", created=10, vision=True, cache_read="0.000000025"),
    # smart band (0.75 .. 4]
    model("openai/mid", "0.000001", "0.000004", created=30, vision=True, cache_read="0.00000025"),
    model("mistralai/mid-text", "0.0000008", "0.000002", created=99, cache_read="0.0000002"),
    # expert band (4 .. 25]
    model("anthropic/claude-top", "0.000005", "0.000025", created=20, vision=True, cache_read="0.0000005",
          cache_write="0.00000625", reasoning={"mandatory": True, "supported_efforts": ["high", "medium", "low"]}),
    # never eligible
    model("openrouter/auto", "-1", "-1", created=999),
    model("google/flash-vision:free", "0", "0", created=999, vision=True),
    model("openai/mid:batch", "0.0000005", "0.000002", created=999, cache_read="0.0000001"),
    model("randomco/cheapest", "0.00000001", "0.00000002", created=999, cache_read="0.000000001"),
    model("google/image-maker", "0.0000001", "0.0000004", created=999, out=("image", "text")),
    model("openai/retiring", "0.0000001", "0.0000004", created=999, cache_read="0.00000001", expiration="2026-12-01"),
    model("openai/too-pricey", "0.00002", "0.0001", created=999, cache_read="0.000002"),
]


class FakeOpenRouter:
    def __init__(self):
        self.models = copy.deepcopy(LIVE_MODELS)
        self.calls: list[dict] = []
        self.cost = Decimal("0.001")
        self.reply = "Step 1: ..."
        self.fail: UpstreamError | None = None
        self.list_fail = False

    async def list_models(self):
        if self.list_fail:
            raise RuntimeError("network down")
        return copy.deepcopy(self.models)

    async def chat(self, payload: dict) -> dict:
        self.calls.append(payload)
        if self.fail:
            raise self.fail
        return {
            "id": "gen-1", "model": payload["model"],
            "choices": [{"message": {"role": "assistant", "content": self.reply}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 1200, "completion_tokens": 300, "total_tokens": 1500,
                      "cost": float(self.cost),
                      "prompt_tokens_details": {"cached_tokens": 900, "cache_write_tokens": 0}},
        }


@pytest.fixture
def settings(tmp_path):
    return Settings(database_path=tmp_path / "test.db", dev_mode=True, payment_provider="mock",
                    public_base_url="http://testserver", usd_inr_rate=Decimal("93"), margin_pct=Decimal("5"),
                    chat_rate_limit_per_minute=1000, rate_limit_per_minute=1000)


@pytest.fixture
def fake():
    return FakeOpenRouter()


@pytest.fixture
def client(settings, fake):
    app = create_app(settings, client=fake)
    with TestClient(app) as c:
        yield c


def register(client) -> dict:
    r = client.post("/v1/auth/register", json={})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}"}


def top_up(client, headers, amount=100) -> float:
    r = client.post("/v1/wallet/topup", json={"amount_inr": amount}, headers=headers)
    assert r.status_code == 200, r.text
    r = client.post("/v1/wallet/topup/confirm", json={"topup_id": r.json()["topup_id"]})
    assert r.status_code == 200, r.text
    return r.json()["balance_inr"]


# 1x1 PNG
PNG_B64 = base64.b64encode(bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010806000000"
    "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082")).decode()
