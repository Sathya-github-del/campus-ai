"""Money correctness under concurrency, on both backends.

Each test runs on SQLite (default suite) and on Postgres (`pytest -m postgres`). Two kinds of test:
- lock tests hold the wallet/top-up lock open in one transaction and prove that the money operation in
  another thread waits for it (deterministic: shows the lock is actually taken);
- race tests start many threads at a barrier and check the end state is exactly right (no double spend,
  no lost update, no double credit, wallet cap respected).
"""
from __future__ import annotations

import sqlite3
import threading
import time
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.accounts import delete_account
from app.db import PostgresDB, SQLiteDB
from app.money import UNITS_PER_PAISA
from app.wallet import TopupError, Wallet, new_id

THREADS = 16


@pytest.fixture(params=["sqlite", pytest.param("postgres", marks=pytest.mark.postgres)])
def db(request, tmp_path):
    if request.param == "sqlite":
        d = SQLiteDB(tmp_path / "c.db")
    else:
        d = PostgresDB(request.getfixturevalue("pg_url"), pool_size=THREADS + 4)
    yield d
    d.close()


@pytest.fixture
def wallet(db):
    return Wallet(db, hold_ttl_seconds=600)


def make_user(db, balance_paise: int = 0) -> str:
    uid = new_id("u")
    with db.tx() as c:
        c.execute("INSERT INTO users(id, token_hash, created_at) VALUES(?,?,?)", (uid, new_id("t"), time.time()))
        c.execute("INSERT INTO wallets(user_id, balance_paise) VALUES(?,?)", (uid, balance_paise))
    return uid


def race(n: int, fn):
    """Run fn(i) in n threads released together; return the results in order."""
    barrier = threading.Barrier(n)

    def run(i):
        barrier.wait()
        return fn(i)

    with ThreadPoolExecutor(n) as ex:
        return list(ex.map(run, range(n)))


def integrity_errors(db) -> tuple[type[BaseException], ...]:
    if isinstance(db, PostgresDB):
        import psycopg

        return (psycopg.IntegrityError,)
    return (sqlite3.IntegrityError,)


def lock_wallet_row(c, db, uid):
    c.execute("SELECT balance_paise FROM wallets WHERE user_id=?" + db.for_update, (uid,)).fetchone()


def assert_waits_for(lock_cm, op, *, hold_for: float = 0.4):
    """While `lock_cm` (an open transaction holding a lock) is active, `op` in another thread must not
    finish; once it commits, `op` must complete. Returns op's result."""
    done = threading.Event()
    result = {}

    def run():
        try:
            result["value"] = op()
        except BaseException as e:  # surfaced below, in the test thread
            result["error"] = e
        done.set()

    with lock_cm:
        t = threading.Thread(target=run)
        t.start()
        assert not done.wait(hold_for), "money operation did not wait for the lock"
    assert done.wait(10), "money operation never completed after the lock was released"
    t.join()
    if "error" in result:
        raise result["error"]
    return result["value"]


# ------------------------------------------------------------------ the lock is really taken

def test_reserve_waits_for_wallet_lock(db, wallet):
    uid = make_user(db, 1000)

    @contextmanager
    def other_tx():
        with db.tx() as c:
            lock_wallet_row(c, db, uid)
            c.execute("UPDATE wallets SET balance_paise=100 WHERE user_id=?", (uid,))
            yield

    # The reservation must see the balance committed by the lock holder (100), not the stale 1000.
    assert assert_waits_for(other_tx(), lambda: wallet.reserve(uid, 500)) is None
    assert wallet.reserve(uid, 100) is not None


def test_settle_waits_for_wallet_lock(db, wallet):
    uid = make_user(db, 1000)
    hold = wallet.reserve(uid, 50)

    def settle():
        with db.tx() as c:
            return wallet.settle(c, uid, hold, 30 * UNITS_PER_PAISA, ref="x")

    @contextmanager
    def other_tx():
        with db.tx() as c:
            lock_wallet_row(c, db, uid)
            c.execute("UPDATE wallets SET balance_paise=balance_paise-200 WHERE user_id=?", (uid,))
            yield

    assert_waits_for(other_tx(), settle)
    # Neither write is lost: 1000 - 200 (concurrent writer) - 30 (charge)
    assert db.one("SELECT balance_paise FROM wallets WHERE user_id=?", (uid,))["balance_paise"] == 770


def test_delete_account_waits_for_wallet_lock_and_sees_committed_money(db, wallet):
    uid = make_user(db, 1000)

    @contextmanager
    def other_tx():  # a money move in flight: holds the wallet lock and writes a ledger row
        with db.tx() as c:
            lock_wallet_row(c, db, uid)
            c.execute("UPDATE wallets SET balance_paise=900 WHERE user_id=?", (uid,))
            c.execute("INSERT INTO ledger(user_id, ts, kind, amount_paise, balance_after_paise, ref) "
                      "VALUES(?,?,?,?,?,?)", (uid, time.time(), "chat", -100, 900, "conv_x"))
            yield

    assert_waits_for(other_tx(), lambda: delete_account(db, uid))
    # The row committed while deletion waited is deleted too (fresh snapshot after the lock).
    for table in ("ledger", "wallets", "holds", "topups", "conversations"):
        assert db.q(f"SELECT 1 FROM {table} WHERE user_id=?", (uid,)) == [], table
    assert db.q("SELECT 1 FROM users WHERE id=?", (uid,)) == []


def test_delete_account_waits_for_conversation_lock(db, wallet):
    # Chat turns lock their conversation row before the wallet; deletion takes the same order.
    uid = make_user(db, 1000)
    now = time.time()
    db.x("INSERT INTO conversations(id, user_id, created_at, updated_at) VALUES(?,?,?,?)", ("conv_a", uid, now, now))

    @contextmanager
    def chat_tx():
        with db.tx() as c:
            c.execute("UPDATE conversations SET updated_at=? WHERE id=?", (now + 1, "conv_a"))
            c.execute("INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?,?,?,?)",
                      ("conv_a", "user", "hi", now))
            yield

    assert_waits_for(chat_tx(), lambda: delete_account(db, uid))
    assert db.q("SELECT 1 FROM messages WHERE conversation_id='conv_a'") == []
    assert db.q("SELECT 1 FROM conversations WHERE user_id=?", (uid,)) == []


def test_settle_after_account_deletion_rolls_back_cleanly(db, wallet):
    # A chat that was mid-call when the account was deleted must not leave money rows behind.
    uid = make_user(db, 1000)
    hold = wallet.reserve(uid, 50)
    delete_account(db, uid)
    with pytest.raises(Exception):
        with db.tx() as c:
            wallet.settle(c, uid, hold, 30 * UNITS_PER_PAISA, ref="x")
    assert db.q("SELECT 1 FROM ledger WHERE user_id=?", (uid,)) == []
    assert db.q("SELECT 1 FROM wallets WHERE user_id=?", (uid,)) == []


def test_duplicate_payment_waits_for_topup_lock(db, wallet):
    uid = make_user(db)
    tid = wallet.create_topup(uid, 2000, "mock", 10**9)

    @contextmanager
    def first_delivery():
        with db.tx() as c:
            c.execute("SELECT status FROM topups WHERE id=?" + db.for_update, (tid,)).fetchone()
            yield
            # The first delivery credits and commits while the duplicate is waiting.
            c.execute("UPDATE topups SET status='paid' WHERE id=?", (tid,))
            c.execute("UPDATE wallets SET balance_paise=balance_paise+2000 WHERE user_id=?", (uid,))

    assert assert_waits_for(first_delivery(), lambda: wallet.apply_payment(tid, 2000, None)) == uid
    assert wallet.available_paise(uid) == 2000  # credited once, not twice


# ------------------------------------------------------------------ races: exact end state

@pytest.mark.parametrize("round_", range(3))
def test_concurrent_reserves_never_overcommit(db, wallet, round_):
    uid = make_user(db, 1000)
    holds = race(THREADS, lambda i: wallet.reserve(uid, 100))
    granted = [h for h in holds if h]
    assert len(granted) == 10  # exactly balance / hold, never more
    assert db.one("SELECT COALESCE(SUM(paise), 0) AS s FROM holds WHERE user_id=?", (uid,))["s"] == 1000
    assert wallet.available_paise(uid) == 0


def test_concurrent_settles_lose_no_update(db, wallet):
    start = 100_000
    uid = make_user(db, start)
    costs = [12_345 + 1_111 * i for i in range(THREADS)]  # units; mixes sub-paisa credit and whole paise
    holds = [wallet.reserve(uid, 100) for _ in range(THREADS)]
    assert all(holds)

    def settle(i):
        with db.tx() as c:
            return wallet.settle(c, uid, holds[i], costs[i], ref=f"r{i}")

    results = race(THREADS, settle)
    w = db.one("SELECT balance_paise, credit_units FROM wallets WHERE user_id=?", (uid,))
    # Total value (paise * 10^4 + sub-paisa credit) drops by exactly the metered cost: nothing lost or doubled.
    assert w["balance_paise"] * UNITS_PER_PAISA + w["credit_units"] == start * UNITS_PER_PAISA - sum(costs)
    assert start - w["balance_paise"] == sum(r.paise_taken for r in results)
    ledger = db.q("SELECT amount_paise, balance_after_paise FROM ledger WHERE user_id=? AND kind='chat' "
                  "ORDER BY id", (uid,))
    assert len(ledger) == THREADS
    assert -sum(r["amount_paise"] for r in ledger) == start - w["balance_paise"]
    # Serialized: each ledger row's balance_after is the previous one minus this charge.
    bal = start
    for r in ledger:
        bal += r["amount_paise"]
        assert r["balance_after_paise"] == bal
    assert db.one("SELECT COUNT(*) AS n FROM holds WHERE user_id=?", (uid,))["n"] == 0


def test_concurrent_reserve_and_settle_keep_balance_non_negative(db, wallet):
    uid = make_user(db, 500)

    def chat(i):
        hold = wallet.reserve(uid, 100)
        if hold is None:
            return 0
        with db.tx() as c:
            return wallet.settle(c, uid, hold, 100 * UNITS_PER_PAISA, ref=f"r{i}").paise_taken

    taken = race(THREADS, chat)
    assert sum(taken) <= 500
    w = db.one("SELECT balance_paise FROM wallets WHERE user_id=?", (uid,))
    assert w["balance_paise"] == 500 - sum(taken) >= 0


def test_duplicate_webhook_deliveries_credit_once(db, wallet):
    uid = make_user(db)
    tid = wallet.create_topup(uid, 5000, "mock", 10**9)
    assert race(THREADS, lambda i: wallet.apply_payment(tid, 5000, f"pay_{i}")) == [uid] * THREADS
    assert wallet.available_paise(uid) == 5000
    assert db.q("SELECT amount_paise FROM ledger WHERE user_id=?", (uid,)) == [{"amount_paise": 5000}]


def test_concurrent_topups_respect_wallet_cap(db, wallet):
    uid = make_user(db)

    def create(i):
        try:
            return wallet.create_topup(uid, 300, "mock", max_balance_paise=1000)
        except TopupError:
            return None

    created = [t for t in race(THREADS, create) if t]
    assert len(created) == 3  # 3 * 300 <= 1000 < 4 * 300


# ------------------------------------------------------------------ schema constraints (the backstop)

def test_balance_can_never_go_negative(db):
    uid = make_user(db, 10)
    with pytest.raises(integrity_errors(db)):
        db.x("UPDATE wallets SET balance_paise=-1 WHERE user_id=?", (uid,))
    with pytest.raises(integrity_errors(db)):
        with db.tx() as c:
            c.execute("UPDATE wallets SET balance_paise=balance_paise-11 WHERE user_id=?", (uid,))
    assert db.one("SELECT balance_paise FROM wallets WHERE user_id=?", (uid,))["balance_paise"] == 10


def test_credit_units_bounds(db):
    uid = make_user(db)
    db.x("UPDATE wallets SET credit_units=9999 WHERE user_id=?", (uid,))
    for bad in (10_000, -1):
        with pytest.raises(integrity_errors(db)):
            db.x("UPDATE wallets SET credit_units=? WHERE user_id=?", (bad, uid))


def test_other_checks_and_foreign_keys(db):
    uid = make_user(db)
    errs = integrity_errors(db)
    with pytest.raises(errs):
        db.x("INSERT INTO wallets(user_id) VALUES(?)", ("u_nobody",))  # FK -> users
    with pytest.raises(errs):
        db.x("INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?,?,?,?)",
             ("conv_nope", "user", "hi", time.time()))  # FK -> conversations
    with pytest.raises(errs):
        db.x("INSERT INTO holds(id, user_id, paise, created_at) VALUES(?,?,?,?)", ("h", uid, 0, time.time()))
    with pytest.raises(errs):
        db.x("INSERT INTO topups(id, user_id, amount_paise, status, provider, created_at) VALUES(?,?,?,?,?,?)",
             ("t", uid, 100, "refunded", "mock", time.time()))
    with pytest.raises(errs):
        db.x("INSERT INTO ledger(user_id, ts, kind, amount_paise, balance_after_paise) VALUES(?,?,?,?,?)",
             (uid, time.time(), "transfer", 1, 1))  # closed loop: no other kinds of money movement
    db.x("INSERT INTO users(id, token_hash, created_at) VALUES(?,?,?)", ("u2", "dup", 1.0))
    with pytest.raises(errs):
        db.x("INSERT INTO users(id, token_hash, created_at) VALUES(?,?,?)", ("u3", "dup", 1.0))  # UNIQUE


def test_failed_transaction_rolls_back_everything(db, wallet):
    uid = make_user(db, 1000)
    hold = wallet.reserve(uid, 100)
    with pytest.raises(RuntimeError):
        with db.tx() as c:
            wallet.settle(c, uid, hold, 50 * UNITS_PER_PAISA, ref="x")
            raise RuntimeError("crash after charging, before commit")
    assert db.one("SELECT balance_paise FROM wallets WHERE user_id=?", (uid,))["balance_paise"] == 1000
    assert db.q("SELECT id FROM ledger WHERE user_id=?", (uid,)) == []
    assert wallet.available_paise(uid) == 900  # hold still there (released by the caller's error path)


def test_timestamps_keep_full_precision(db, wallet):
    # Postgres REAL (4-byte) would round epoch seconds to ~2 minutes and break hold expiry.
    uid = make_user(db, 1000)
    wallet.reserve(uid, 10)
    created = db.one("SELECT created_at FROM holds WHERE user_id=?", (uid,))["created_at"]
    assert abs(created - time.time()) < 5
