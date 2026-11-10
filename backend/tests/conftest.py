"""Shared fixtures: a fake OpenRouter (no network), an app on a temp SQLite file, and (opt-in, marker
`postgres`) a real Postgres: TEST_DATABASE_URL if set, otherwise a throwaway postgres:16 Docker container."""
from __future__ import annotations

import base64
import copy
import os
import shutil
import subprocess
import time
import uuid
from urllib.parse import urlsplit, urlunsplit
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
        self.finish_reason: str | None = "stop"
        self.native_finish_reason: str | None = None
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
            "choices": [{"message": {"role": "assistant", "content": self.reply},
                         "finish_reason": self.finish_reason, "native_finish_reason": self.native_finish_reason}],
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


# ------------------------------------------------------------------ Postgres (opt-in: pytest -m postgres)

PG_IMAGE = os.environ.get("TEST_PG_IMAGE", "postgres:16-alpine")


def _wait_for_pg(url: str, timeout: float = 60) -> None:
    import psycopg

    deadline = time.monotonic() + timeout
    while True:
        try:
            with psycopg.connect(url, connect_timeout=2) as conn:
                conn.execute("SELECT 1")
            return
        except psycopg.OperationalError:
            if time.monotonic() > deadline:
                raise
            time.sleep(0.3)


@pytest.fixture(scope="session")
def pg_admin_url():
    """A Postgres server for the whole test session (superuser URL to its `postgres` database)."""
    pytest.importorskip("psycopg", reason="pip install -r requirements.txt for the Postgres tests")
    url = os.environ.get("TEST_DATABASE_URL")
    if url:
        yield url
        return
    if not shutil.which("docker"):
        pytest.skip("Postgres tests need Docker or TEST_DATABASE_URL")
    name = f"campus-ai-test-pg-{uuid.uuid4().hex[:8]}"
    try:
        subprocess.run(["docker", "run", "-d", "--rm", "--name", name, "-e", "POSTGRES_PASSWORD=test",
                        "-p", "127.0.0.1::5432", PG_IMAGE], check=True, capture_output=True, text=True,
                       timeout=300)
    except (subprocess.SubprocessError, OSError) as e:
        pytest.skip(f"could not start a Postgres container: {getattr(e, 'stderr', '') or e}")
    try:
        port = subprocess.run(["docker", "port", name, "5432/tcp"], check=True, capture_output=True,
                              text=True).stdout.split()[0].rsplit(":", 1)[1]
        url = f"postgresql://postgres:test@127.0.0.1:{port}/postgres"
        _wait_for_pg(url)
        yield url
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)


@pytest.fixture
def pg_url(pg_admin_url):
    """A fresh, empty database per test (the app creates the schema itself, as in production)."""
    import psycopg

    dbname = f"t_{uuid.uuid4().hex[:12]}"
    with psycopg.connect(pg_admin_url, autocommit=True) as conn:
        conn.execute(f"CREATE DATABASE {dbname}")
    parts = urlsplit(pg_admin_url)
    yield urlunsplit(parts._replace(path=f"/{dbname}"))
    with psycopg.connect(pg_admin_url, autocommit=True) as conn:
        conn.execute(f"DROP DATABASE IF EXISTS {dbname} WITH (FORCE)")
