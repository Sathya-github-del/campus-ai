"""End-to-end API tests against the exact client contract (fake OpenRouter, real SQLite)."""
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from app.api import create_app
from app.config import ConfigError, Settings
from app.money import usd_to_units
from app.openrouter import UpstreamError
from app.payments import Checkout, PaymentProvider

from .conftest import PNG_B64, register, top_up


def set_balance(client, headers, paise):
    user = client.app.state.db.one("SELECT id FROM users")  # tests register one user unless noted
    client.app.state.db.x("UPDATE wallets SET balance_paise=?, credit_units=0 WHERE user_id=?", (paise, user["id"]))


# ------------------------------------------------------------------ auth / wallet / models

def test_register_and_empty_wallet(client):
    h = register(client)
    r = client.get("/v1/wallet", headers=h)
    assert r.status_code == 200 and r.json() == {"balance_inr": 0.0}


def test_register_without_body(client):
    assert set(client.post("/v1/auth/register").json()) == {"token"}


def test_auth_required(client):
    r = client.get("/v1/wallet")
    assert r.status_code == 401 and r.json()["error"] == "unauthorized"
    assert client.get("/v1/wallet", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_models_contract(client):
    r = client.get("/v1/models")
    assert r.status_code == 200
    tiers = r.json()["tiers"]
    assert [t["tier"] for t in tiers] == ["fast", "smart", "expert"]
    assert [t["label"] for t in tiers] == ["Fast", "Smart", "Expert"]
    for t in tiers:
        assert set(t) == {"tier", "label", "est_cost_per_message_inr"}
    costs = [t["est_cost_per_message_inr"] for t in tiers]
    assert 0 < costs[0] < costs[1] < costs[2]
    # expert: 300 in * $5/M + 400 out * $25/M = $0.0115 -> * 93 / 0.95 = Rs 1.1258
    assert costs[2] == pytest.approx(1.1258, abs=0.0001)


# ------------------------------------------------------------------ mock payment flow

def test_mock_topup_end_to_end(client):
    h = register(client)
    r = client.post("/v1/wallet/topup", json={"amount_inr": 100}, headers=h)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"checkout_url", "topup_id"}
    assert body["checkout_url"] == f"http://testserver/v1/dev/checkout/{body['topup_id']}"
    # nothing credited until payment is confirmed
    assert client.get("/v1/wallet", headers=h).json()["balance_inr"] == 0
    page = client.get(f"/v1/dev/checkout/{body['topup_id']}")
    assert page.status_code == 200 and "100.00" in page.text and "No real money" in page.text

    r = client.post("/v1/wallet/topup/confirm", json={"topup_id": body["topup_id"]})
    assert r.status_code == 200 and r.json() == {"balance_inr": 100.0}
    # webhook retries / double taps credit exactly once
    assert client.post("/v1/wallet/topup/confirm", json={"topup_id": body["topup_id"]}).json() == {"balance_inr": 100.0}
    assert client.get("/v1/wallet", headers=h).json() == {"balance_inr": 100.0}
    ledger = client.app.state.db.q("SELECT kind, amount_paise, balance_after_paise FROM ledger")
    assert ledger == [{"kind": "topup", "amount_paise": 10000, "balance_after_paise": 10000}]


@pytest.mark.parametrize("amount,code", [(19, 400), (19.99, 400), (20, 200), (20.5, 200), (20.555, 400),
                                         (2000, 200), (2000.01, 400), ("x", 422)])
def test_topup_amount_validation(client, amount, code):
    h = register(client)
    r = client.post("/v1/wallet/topup", json={"amount_inr": amount}, headers=h)
    assert r.status_code == code, r.text
    if code == 400:
        assert r.json()["error"] == "invalid_amount"


def test_wallet_cap(client, settings):
    h = register(client)
    settings.max_wallet_balance_inr = Decimal("150")
    assert client.post("/v1/wallet/topup", json={"amount_inr": 100}, headers=h).status_code == 200
    r = client.post("/v1/wallet/topup", json={"amount_inr": 100}, headers=h)  # 100 pending + 100 > 150
    assert r.status_code == 400 and r.json()["error"] == "wallet_limit"


def test_confirm_rejects_other_users_token_and_unknown_ids(client):
    h1, h2 = register(client), register(client)
    tid = client.post("/v1/wallet/topup", json={"amount_inr": 50}, headers=h1).json()["topup_id"]
    assert client.post("/v1/wallet/topup/confirm", json={"topup_id": tid}, headers=h2).status_code == 403
    assert client.post("/v1/wallet/topup/confirm", json={"topup_id": "tp_nope"}).status_code == 404
    assert client.post("/v1/wallet/topup/confirm", json={"topup_id": tid}, headers=h1).json() == {"balance_inr": 50.0}


def test_webhook_path_checks_amount(client):
    h = register(client)
    tid = client.post("/v1/wallet/topup", json={"amount_inr": 30}, headers=h).json()["topup_id"]
    r = client.post("/v1/payments/webhook", json={"topup_id": tid, "amount_paise": 2000})
    assert r.status_code == 409 and r.json()["error"] == "amount_mismatch"
    assert client.post("/v1/payments/webhook", json={"topup_id": tid, "amount_paise": 3000}).status_code == 200
    assert client.get("/v1/wallet", headers=h).json() == {"balance_inr": 30.0}
    assert client.post("/v1/payments/webhook", content=b"garbage").status_code == 400


class StubRealProvider(PaymentProvider):
    name = "real"

    def create_checkout(self, topup_id, amount_paise, user_id):
        return Checkout("https://pay.example/x", "order_1")

    def parse_webhook(self, headers, body):
        raise NotImplementedError


def test_dev_endpoints_disabled_outside_dev_mode(tmp_path, fake):
    s = Settings(database_path=tmp_path / "p.db", dev_mode=False, payment_provider="razorpay")
    with TestClient(create_app(s, client=fake, provider=StubRealProvider())) as c:
        h = register(c)
        tid = c.post("/v1/wallet/topup", json={"amount_inr": 20}, headers=h).json()["topup_id"]
        assert c.post("/v1/wallet/topup/confirm", json={"topup_id": tid}).status_code == 404
        assert c.get(f"/v1/dev/checkout/{tid}").status_code == 404
        assert c.app.state.db.one("SELECT provider_ref FROM topups")["provider_ref"] == "order_1"


def test_mock_payments_refused_in_production():
    with pytest.raises(ConfigError):
        Settings(dev_mode=False, payment_provider="mock").validate()


# ------------------------------------------------------------------ chat: 402 / deduction

def test_chat_402_with_empty_wallet_never_calls_model(client, fake):
    h = register(client)
    for tier in ("fast", "smart", "expert"):
        r = client.post("/v1/chat", json={"tier": tier, "message": "hi", "conversation_id": None,
                                          "image_base64": None}, headers=h)
        assert r.status_code == 402
        body = r.json()
        assert body["error"] == "insufficient_balance" and body["tier"] == tier
        assert body["balance_inr"] == 0 and body["required_inr"] > 0
        assert "Top up" in body["message"]
    assert fake.calls == []


def test_chat_402_is_per_tier(client, fake):
    h = register(client)
    set_balance(client, h, 50)  # Rs 0.50: plenty for Fast, not enough for even a short Expert reply
    r = client.post("/v1/chat", json={"tier": "expert", "message": "hi"}, headers=h)
    assert r.status_code == 402 and fake.calls == []
    fake.cost = Decimal("0.00005")
    r = client.post("/v1/chat", json={"tier": "fast", "message": "hi"}, headers=h)
    assert r.status_code == 200, r.text
    assert len(fake.calls) == 1


def test_low_balance_gets_shorter_max_tokens_not_negative(client, fake, settings):
    h = register(client)
    set_balance(client, h, 200)  # Rs 2: can't cover a 1500-token Expert worst case, can cover 256+
    fake.cost = Decimal("0.0001")
    r = client.post("/v1/chat", json={"tier": "expert", "message": "hi"}, headers=h)
    assert r.status_code == 200, r.text
    max_tokens = fake.calls[0]["max_tokens"]
    assert settings.min_output_tokens <= max_tokens < settings.max_output_tokens
    assert r.json()["wallet_balance_inr"] >= 0


def test_chat_deducts_metered_cost(client, fake):
    h = register(client)
    assert top_up(client, h, 100) == 100.0
    fake.cost = Decimal("0.001")  # OpenRouter usage.cost, USD
    r = client.post("/v1/chat", json={"tier": "expert", "message": "Explain photosynthesis",
                                      "conversation_id": None, "image_base64": None}, headers=h)
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == {"reply", "conversation_id", "cost_inr", "wallet_balance_inr", "truncated"}
    assert body["truncated"] is False
    units = usd_to_units(Decimal("0.001"), Decimal("93"), Decimal("5"))  # 0.093/0.95 = Rs 0.0979
    assert units == 97895
    assert body["cost_inr"] == 0.0979
    assert body["reply"] == "Step 1: ..."
    # 9.79 paise -> 10 whole paise taken, 0.21 paise kept as prepaid credit for the next message
    assert body["wallet_balance_inr"] == 99.90
    assert client.get("/v1/wallet", headers=h).json() == {"balance_inr": 99.90}
    w = client.app.state.db.one("SELECT balance_paise, credit_units FROM wallets")
    assert w == {"balance_paise": 9990, "credit_units": 100_000 - 97895}
    assert client.app.state.db.q("SELECT COUNT(*) n FROM holds")[0]["n"] == 0
    msg = client.app.state.db.one("SELECT cached_tokens, cost_usd, charge_units FROM messages WHERE role='assistant'")
    assert msg == {"cached_tokens": 900, "cost_usd": "0.001", "charge_units": 97895}


def test_charge_never_exceeds_preauthorised_hold(client, fake):
    h = register(client)
    top_up(client, h, 20)
    fake.cost = Decimal("5")  # absurd upstream cost, far above the worst-case estimate
    r = client.post("/v1/chat", json={"tier": "fast", "message": "hi"}, headers=h)
    assert r.status_code == 200
    assert 0 < r.json()["cost_inr"] < 1  # capped at the Fast-tier hold, not Rs 489
    row = client.app.state.db.one("SELECT shortfall_units FROM ledger WHERE kind='chat'")
    assert row["shortfall_units"] > 0  # recorded as platform loss


def test_upstream_failure_is_free_and_releases_hold(client, fake):
    h = register(client)
    top_up(client, h, 20)
    for status, ours, code in ((402, 503, "service_unavailable"), (403, 400, "content_rejected"),
                               (429, 503, "model_busy"), (500, 502, "upstream_error"), (504, 504, "model_timeout")):
        fake.fail = UpstreamError(status, "boom")
        r = client.post("/v1/chat", json={"tier": "smart", "message": "hi"}, headers=h)
        assert r.status_code == ours and r.json()["error"] == code
        assert client.get("/v1/wallet", headers=h).json() == {"balance_inr": 20.0}
    assert client.app.state.db.q("SELECT COUNT(*) n FROM messages")[0]["n"] == 0


# ------------------------------------------------------------------ chat: conversation + caching

def test_conversation_history_and_cache_layout(client, fake):
    h = register(client)
    top_up(client, h, 100)
    r1 = client.post("/v1/chat", json={"tier": "expert", "message": "What is 2+2?"}, headers=h).json()
    conv = r1["conversation_id"]
    fake.reply = "Second answer"
    r2 = client.post("/v1/chat", json={"tier": "expert", "message": "And 3+3?", "conversation_id": conv}, headers=h)
    assert r2.status_code == 200 and r2.json()["conversation_id"] == conv
    p1, p2 = fake.calls
    assert p1["session_id"] == p2["session_id"] == conv  # sticky provider routing for cache hits
    assert p2["model"] == "anthropic/claude-top"
    roles = [m["role"] for m in p2["messages"]]
    assert roles == ["system", "user", "assistant", "user"]
    assert p2["messages"][2]["content"] == "Step 1: ..."
    assert p2["messages"][0]["content"][0]["cache_control"] == {"type": "ephemeral"}
    assert "cache_control" in p2["messages"][3]["content"][-1]
    assert "cache_control" not in p2["messages"][1]["content"][-1]  # moved to the newest turn
    assert p2["reasoning"] == {"effort": "medium", "exclude": True}
    assert p2["user"].startswith("u_")


def test_other_users_conversation_is_not_found(client, fake):
    h1, h2 = register(client), register(client)
    for h in (h1, h2):
        top_up(client, h, 20)
    conv = client.post("/v1/chat", json={"tier": "fast", "message": "hi"}, headers=h1).json()["conversation_id"]
    r = client.post("/v1/chat", json={"tier": "fast", "message": "hi", "conversation_id": conv}, headers=h2)
    assert r.status_code == 404 and r.json()["error"] == "conversation_not_found"


def test_image_is_passed_to_vision_model(client, fake):
    h = register(client)
    top_up(client, h, 20)
    fake.cost = Decimal("0.00005")
    r = client.post("/v1/chat", json={"tier": "fast", "message": "", "image_base64": PNG_B64}, headers=h)
    assert r.status_code == 200, r.text
    call = fake.calls[0]
    assert call["model"] == "google/flash-vision"
    parts = call["messages"][-1]["content"]
    assert parts[0] == {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{PNG_B64}"}}
    assert parts[1]["text"]  # photo-only message gets a default prompt


def test_bad_image_rejected_before_any_charge(client, fake):
    h = register(client)
    top_up(client, h, 20)
    r = client.post("/v1/chat", json={"tier": "fast", "message": "x", "image_base64": "not base64!!"}, headers=h)
    assert r.status_code == 400 and r.json()["error"] == "invalid_image"
    r = client.post("/v1/chat", json={"tier": "fast", "message": "x", "image_base64": "aGVsbG8="}, headers=h)
    assert r.status_code == 400  # valid base64, not an image
    assert fake.calls == []


def test_validation_errors(client):
    h = register(client)
    r = client.post("/v1/chat", json={"tier": "ultra", "message": "hi"}, headers=h)
    assert r.status_code == 422 and r.json()["error"] == "invalid_request"
    r = client.post("/v1/chat", json={"tier": "fast", "message": "   "}, headers=h)
    assert r.status_code == 400 and r.json()["error"] == "empty_message"


def test_too_long_conversation_refused_not_truncated(client, fake, settings):
    h = register(client)
    top_up(client, h, 20)
    settings.max_input_tokens = 300
    r = client.post("/v1/chat", json={"tier": "fast", "message": "word " * 400}, headers=h)
    assert r.status_code == 413 and r.json()["error"] == "conversation_too_long"
    assert fake.calls == []


# ------------------------------------------------------------------ abuse limits

def test_chat_rate_limit(client, fake, settings):
    h = register(client)
    top_up(client, h, 20)
    fake.cost = Decimal("0.00001")
    settings.chat_rate_limit_per_minute = 2  # limiters are built at app creation: use a fresh app
    with TestClient(create_app(settings, client=fake)) as c:
        h2 = {"Authorization": h["Authorization"]}
        codes = [c.post("/v1/chat", json={"tier": "fast", "message": "hi"}, headers=h2).status_code for _ in range(3)]
        assert codes == [200, 200, 429]
        r = c.post("/v1/chat", json={"tier": "fast", "message": "hi"}, headers=h2)
        assert r.json()["error"] == "rate_limited" and int(r.headers["Retry-After"]) >= 1


def test_wallet_holds_prevent_double_spend(client):
    h = register(client)
    set_balance(client, h, 100)
    w = client.app.state.wallet
    uid = client.app.state.db.one("SELECT id FROM users")["id"]
    first = w.reserve(uid, 60)
    assert first is not None
    assert w.reserve(uid, 60) is None  # second concurrent request can't overcommit the balance
    assert client.get("/v1/wallet", headers=h).json() == {"balance_inr": 0.40}
    w.release(first)
    assert client.get("/v1/wallet", headers=h).json() == {"balance_inr": 1.0}


def test_normal_finish_is_not_truncated(client, fake):
    h = register(client)
    top_up(client, h, 100)
    fake.finish_reason = "stop"
    r = client.post("/v1/chat", json={"tier": "fast", "message": "What is 2+2?"}, headers=h)
    assert r.status_code == 200, r.text
    assert r.json()["truncated"] is False


def test_length_limited_reply_is_truncated(client, fake):
    h = register(client)
    top_up(client, h, 100)
    fake.reply = r"... \frac{933.6}{20} \approx"
    fake.finish_reason = "length"
    fake.native_finish_reason = "max_tokens"
    r = client.post("/v1/chat", json={"tier": "expert", "message": "Solve step by step"}, headers=h)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["truncated"] is True
    assert body["reply"].endswith(r"\approx")
    assert body["cost_inr"] > 0  # still billed for what was generated


@pytest.mark.parametrize("finish, native, expected", [
    (None, None, False),             # signal missing: never claim truncation
    ("", None, False),
    ("content_filter", None, False),
    ("error", "max_tokens", False),  # normalised reason wins over the raw one
    ("LENGTH", None, True),
    (None, "max_tokens", True),      # normalised reason missing: fall back to the provider's raw value
    (None, "MAX_TOKENS", True),
    (None, "end_turn", False),
])
def test_truncated_flag_edge_cases(client, fake, finish, native, expected):
    h = register(client)
    top_up(client, h, 100)
    fake.finish_reason, fake.native_finish_reason = finish, native
    r = client.post("/v1/chat", json={"tier": "fast", "message": "hi"}, headers=h)
    assert r.status_code == 200, r.text
    assert r.json()["truncated"] is expected
