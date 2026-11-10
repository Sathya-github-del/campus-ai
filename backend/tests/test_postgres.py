"""The money-path API tests from test_api.py, re-run unchanged against a real Postgres.

Importing a test function here makes pytest collect it in this module too, where the `settings` fixture
below (pointing the app at a fresh Postgres database) overrides the SQLite one from conftest.py.
Opt-in: `pytest -m postgres` (needs Docker, or TEST_DATABASE_URL pointing at a disposable server).
"""
from decimal import Decimal

import pytest

from app.config import Settings
from app.db import PostgresDB

from .conftest import register
from .test_api import (  # noqa: F401  (collected as tests in this module)
    test_auth_required,
    test_charge_never_exceeds_preauthorised_hold,
    test_chat_402_is_per_tier,
    test_chat_402_with_empty_wallet_never_calls_model,
    test_chat_deducts_metered_cost,
    test_chat_rate_limit,
    test_confirm_rejects_other_users_token_and_unknown_ids,
    test_conversation_history_and_cache_layout,
    test_image_is_passed_to_vision_model,
    test_low_balance_gets_shorter_max_tokens_not_negative,
    test_mock_topup_end_to_end,
    test_other_users_conversation_is_not_found,
    test_register_and_empty_wallet,
    test_topup_amount_validation,
    test_upstream_failure_is_free_and_releases_hold,
    test_wallet_cap,
    test_wallet_holds_prevent_double_spend,
    test_webhook_path_checks_amount,
)

pytestmark = pytest.mark.postgres


@pytest.fixture
def settings(tmp_path, pg_url):
    # Same as conftest's SQLite settings, plus DATABASE_URL. database_path points at a file that must
    # never be created: if it is, the app silently used SQLite.
    return Settings(database_url=pg_url, database_path=tmp_path / "must-not-exist.db", dev_mode=True,
                    payment_provider="mock", public_base_url="http://testserver", usd_inr_rate=Decimal("93"),
                    margin_pct=Decimal("5"), chat_rate_limit_per_minute=1000, rate_limit_per_minute=1000)


def test_app_really_runs_on_postgres(client, settings):
    assert isinstance(client.app.state.db, PostgresDB)
    register(client)
    assert client.app.state.db.one("SELECT COUNT(*) AS n FROM users")["n"] == 1
    assert not settings.database_path.exists()


def test_catalog_kv_roundtrip_on_postgres(pg_url):
    db = PostgresDB(pg_url, pool_size=2)
    try:
        assert db.kv_get("k") is None
        db.kv_set("k", "50% off?")  # literal % and ? in a parameter value must survive translation
        db.kv_set("k", "v2")
        assert db.kv_get("k") == "v2"
    finally:
        db.close()


def test_schema_creation_is_idempotent(pg_url):
    # A second instance booting against an existing schema (e.g. a redeploy) must not fail.
    PostgresDB(pg_url, pool_size=1).close()
    PostgresDB(pg_url, pool_size=1).close()


def test_tx_is_read_committed_even_if_server_default_differs(pg_url):
    # wallet.py's row-lock design assumes READ COMMITTED (fresh snapshot per statement after the lock);
    # it must not silently inherit a different server/role default.
    import psycopg

    with psycopg.connect(pg_url, autocommit=True) as conn:
        dbname = conn.info.dbname
        conn.execute(f"ALTER DATABASE {dbname} SET default_transaction_isolation = 'repeatable read'")
    db = PostgresDB(pg_url, pool_size=1)
    try:
        with db.tx() as c:
            assert c.execute("SHOW transaction_isolation").fetchone()[0] == "read committed"
    finally:
        db.close()
