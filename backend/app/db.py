"""Persistence: users, wallet + ledger, top-ups, conversations, messages.

Two interchangeable backends behind one small interface (`q`, `one`, `x`, `tx`, `kv_get`, `kv_set`,
`close`, plus `dialect` / `for_update` for the few places that need row locking):

- SQLiteDB (stdlib only; the local-dev / test default, used when DATABASE_URL is unset).
  One connection guarded by a lock; every call is short. Writes that move money run inside
  BEGIN IMMEDIATE transactions so they are atomic and serialized even across processes.

- PostgresDB (psycopg 3 + psycopg_pool; used when DATABASE_URL is set; the production path, e.g. Neon).
  A small connection pool. Plain reads/writes run in autocommit, and `tx()` runs an explicit
  READ COMMITTED transaction. Money-moving code takes `SELECT ... FOR UPDATE` on the user's wallet
  row (or on the top-up row) first, which serializes every money operation for that user across
  threads, processes and instances. PostgresDB.tx() explains why this was chosen over SERIALIZABLE.

Calling code writes SQL once with `?` placeholders; PostgresDB translates them to psycopg's `%s`.
Rows support both `row[0]` and `row["col"]` on both backends; `q` / `one` return plain dicts.
"""
from __future__ import annotations

import logging
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator, Protocol

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  created_at REAL NOT NULL);

-- Closed-loop, non-transferable, non-cashable credit. There is deliberately no API that moves balance
-- between users or pays it out. balance_paise can never be negative (CHECK).
CREATE TABLE IF NOT EXISTS wallets (
  user_id TEXT PRIMARY KEY REFERENCES users(id),
  balance_paise INTEGER NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
  credit_units INTEGER NOT NULL DEFAULT 0 CHECK (credit_units >= 0 AND credit_units < 10000));

-- In-flight worst-case reservations for chat requests (released when the call settles or expires).
CREATE TABLE IF NOT EXISTS holds (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  paise INTEGER NOT NULL CHECK (paise > 0),
  created_at REAL NOT NULL);
CREATE INDEX IF NOT EXISTS holds_user ON holds(user_id);

-- Append-only money movements. amount_paise is signed (+topup, -chat).
CREATE TABLE IF NOT EXISTS ledger (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  ts REAL NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('topup', 'chat')),
  amount_paise INTEGER NOT NULL,
  cost_units INTEGER NOT NULL DEFAULT 0,
  shortfall_units INTEGER NOT NULL DEFAULT 0,
  balance_after_paise INTEGER NOT NULL,
  ref TEXT);
CREATE INDEX IF NOT EXISTS ledger_user ON ledger(user_id, ts);

CREATE TABLE IF NOT EXISTS topups (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
  status TEXT NOT NULL CHECK (status IN ('pending', 'paid', 'failed')),
  provider TEXT NOT NULL,
  provider_ref TEXT,
  created_at REAL NOT NULL,
  paid_at REAL);
CREATE INDEX IF NOT EXISTS topups_user ON topups(user_id, created_at);

CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL);
CREATE INDEX IF NOT EXISTS conversations_user ON conversations(user_id, updated_at);

-- Stored verbatim and replayed byte-for-byte each turn: a stable prefix is what makes prompt
-- caching hit. Never rewrite or summarize earlier rows of a conversation.
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id TEXT NOT NULL REFERENCES conversations(id),
  role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
  content TEXT NOT NULL,
  image_base64 TEXT,
  image_mime TEXT,
  created_at REAL NOT NULL,
  tier TEXT,
  model TEXT,
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  cached_tokens INTEGER,
  cache_write_tokens INTEGER,
  cost_usd TEXT,
  charge_units INTEGER);
CREATE INDEX IF NOT EXISTS messages_conv ON messages(conversation_id, id);

CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
"""

# The same schema as Postgres DDL. Keep the two in step: same tables, columns, CHECKs, FKs, indexes.
# Only dialect differences:
#   - SQLite INTEGER is 64-bit, so every money / units / id column is BIGINT (no silent int4 overflow).
#     Token counts stay INTEGER.
#   - REAL (an 8-byte float in SQLite) becomes DOUBLE PRECISION. Postgres REAL is 4 bytes and would
#     round epoch timestamps to ~2 minutes, breaking hold expiry and the pending-top-up window.
#   - INTEGER PRIMARY KEY AUTOINCREMENT becomes BIGINT GENERATED ALWAYS AS IDENTITY (monotonic, never
#     reused).
#   - SQLite's dynamic typing is replaced by the explicit types above.
SCHEMA_POSTGRES = """
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  created_at DOUBLE PRECISION NOT NULL);

-- Closed-loop, non-transferable, non-cashable credit. There is deliberately no API that moves balance
-- between users or pays it out. balance_paise can never be negative (CHECK).
CREATE TABLE IF NOT EXISTS wallets (
  user_id TEXT PRIMARY KEY REFERENCES users(id),
  balance_paise BIGINT NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
  credit_units BIGINT NOT NULL DEFAULT 0 CHECK (credit_units >= 0 AND credit_units < 10000));

-- In-flight worst-case reservations for chat requests (released when the call settles or expires).
CREATE TABLE IF NOT EXISTS holds (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  paise BIGINT NOT NULL CHECK (paise > 0),
  created_at DOUBLE PRECISION NOT NULL);
CREATE INDEX IF NOT EXISTS holds_user ON holds(user_id);

-- Append-only money movements. amount_paise is signed (+topup, -chat).
CREATE TABLE IF NOT EXISTS ledger (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id TEXT NOT NULL,
  ts DOUBLE PRECISION NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('topup', 'chat')),
  amount_paise BIGINT NOT NULL,
  cost_units BIGINT NOT NULL DEFAULT 0,
  shortfall_units BIGINT NOT NULL DEFAULT 0,
  balance_after_paise BIGINT NOT NULL,
  ref TEXT);
CREATE INDEX IF NOT EXISTS ledger_user ON ledger(user_id, ts);

CREATE TABLE IF NOT EXISTS topups (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  status TEXT NOT NULL CHECK (status IN ('pending', 'paid', 'failed')),
  provider TEXT NOT NULL,
  provider_ref TEXT,
  created_at DOUBLE PRECISION NOT NULL,
  paid_at DOUBLE PRECISION);
CREATE INDEX IF NOT EXISTS topups_user ON topups(user_id, created_at);

CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_at DOUBLE PRECISION NOT NULL,
  updated_at DOUBLE PRECISION NOT NULL);
CREATE INDEX IF NOT EXISTS conversations_user ON conversations(user_id, updated_at);

-- Stored verbatim and replayed byte-for-byte each turn: a stable prefix is what makes prompt
-- caching hit. Never rewrite or summarize earlier rows of a conversation.
CREATE TABLE IF NOT EXISTS messages (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES conversations(id),
  role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
  content TEXT NOT NULL,
  image_base64 TEXT,
  image_mime TEXT,
  created_at DOUBLE PRECISION NOT NULL,
  tier TEXT,
  model TEXT,
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  cached_tokens INTEGER,
  cache_write_tokens INTEGER,
  cost_usd TEXT,
  charge_units BIGINT);
CREATE INDEX IF NOT EXISTS messages_conv ON messages(conversation_id, id);

CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
"""

# Arbitrary constant. Serializes schema creation when several instances boot at once: concurrent
# CREATE TABLE IF NOT EXISTS can otherwise fail with a unique violation in pg_type.
_SCHEMA_LOCK_KEY = 0x63616D707573


class Cursor(Protocol):
    rowcount: int

    def fetchone(self) -> Any: ...

    def fetchall(self) -> list[Any]: ...


class Tx(Protocol):
    """What `tx()` yields on either backend: `execute(sql, args)` with `?` placeholders."""

    def execute(self, sql: str, args: Any = ...) -> Cursor: ...


class SQLiteDB:
    dialect = "sqlite"
    # BEGIN IMMEDIATE already holds the database-wide write lock for the whole transaction.
    for_update = ""

    def __init__(self, path: Path | str):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.conn = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None, timeout=30)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA foreign_keys=ON")
        self.conn.executescript(SCHEMA)
        if self.path != ":memory:":
            try:
                Path(self.path).chmod(0o600)
            except OSError:
                pass

    def q(self, sql: str, args=()) -> list[dict]:
        with self.lock:
            return [dict(r) for r in self.conn.execute(sql, args).fetchall()]

    def one(self, sql: str, args=()) -> dict | None:
        rows = self.q(sql, args)
        return rows[0] if rows else None

    def x(self, sql: str, args=()) -> int:
        with self.lock:
            return self.conn.execute(sql, args).rowcount

    @contextmanager
    def tx(self) -> Iterator[sqlite3.Connection]:
        """Atomic write transaction (BEGIN IMMEDIATE takes the write lock up front)."""
        with self.lock:
            self.conn.execute("BEGIN IMMEDIATE")
            try:
                yield self.conn
            except BaseException:
                self.conn.execute("ROLLBACK")
                raise
            else:
                self.conn.execute("COMMIT")

    def kv_get(self, k: str) -> str | None:
        row = self.one("SELECT v FROM kv WHERE k=?", (k,))
        return row["v"] if row else None

    def kv_set(self, k: str, v: str) -> None:
        self.x("INSERT INTO kv(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, v))

    def close(self) -> None:
        with self.lock:
            self.conn.close()


# Backwards-compatible name: `DB(path)` is the SQLite backend.
DB = SQLiteDB


def _pg_sql(sql: str) -> str:
    """`?` placeholders -> psycopg `%s`, with literal `%` escaped. The app's SQL never puts `?` or `%`
    inside string literals; keep it that way."""
    return sql.replace("%", "%%").replace("?", "%s")


class _PgRow(tuple):
    """Tuple row that also answers row["col"] and keys(), like sqlite3.Row, so calling code and
    dict(row) behave the same on both backends."""

    def __new__(cls, values, index: dict[str, int]):
        row = super().__new__(cls, values)
        row._index = index
        return row

    def __getitem__(self, key):
        if isinstance(key, str):
            return tuple.__getitem__(self, self._index[key])
        return tuple.__getitem__(self, key)

    def keys(self):
        return list(self._index)


def _pg_row_factory(cursor):
    index = {d.name: i for i, d in enumerate(cursor.description or ())}
    return lambda values: _PgRow(values, index)


class _PgTx:
    """What PostgresDB.tx() yields: execute() with `?` placeholders on the transaction's connection."""

    def __init__(self, conn):
        self._conn = conn

    def execute(self, sql: str, args=()):
        return self._conn.execute(_pg_sql(sql), args)


class PostgresDB:
    dialect = "postgres"
    # Row lock for read-check-write on money rows inside tx(). See tx().
    for_update = " FOR UPDATE"

    def __init__(self, url: str, *, pool_size: int = 10, timeout: float = 30.0):
        try:
            import psycopg
            from psycopg_pool import ConnectionPool
        except ImportError as e:  # only needed when DATABASE_URL is set
            raise RuntimeError("DATABASE_URL is set but psycopg / psycopg_pool are not installed: "
                               "pip install -r requirements.txt") from e

        def configure(conn) -> None:
            conn.autocommit = True  # plain q/one/x: one statement = one implicit transaction
            # Pin the level tx() relies on instead of inheriting a server or role default.
            conn.isolation_level = psycopg.IsolationLevel.READ_COMMITTED
            conn.row_factory = _pg_row_factory

        self.pool = ConnectionPool(
            url,
            min_size=1,
            max_size=max(1, pool_size),
            timeout=timeout,
            # No server-side prepared statements, so Neon's "-pooler" endpoint (PgBouncer in
            # transaction mode) is safe as well as a direct connection.
            kwargs={"prepare_threshold": None},
            configure=configure,
            # Neon suspends idle compute and drops its connections: check each connection before
            # handing it out, and recycle idle ones before that happens.
            check=ConnectionPool.check_connection,
            max_idle=240,
            open=False,
            name="campus-ai",
        )
        try:
            self.pool.open(wait=True, timeout=timeout)  # fail fast at startup if the DB is unreachable
            with self.pool.connection() as conn:
                with conn.transaction():
                    conn.execute("SELECT pg_advisory_xact_lock(%s)", (_SCHEMA_LOCK_KEY,))
                    conn.execute(SCHEMA_POSTGRES)
        except BaseException:
            self.pool.close()
            raise

    def q(self, sql: str, args=()) -> list[dict]:
        with self.pool.connection() as conn:
            return [dict(r) for r in conn.execute(_pg_sql(sql), args).fetchall()]

    def one(self, sql: str, args=()) -> dict | None:
        rows = self.q(sql, args)
        return rows[0] if rows else None

    def x(self, sql: str, args=()) -> int:
        with self.pool.connection() as conn:
            return conn.execute(_pg_sql(sql), args).rowcount

    @contextmanager
    def tx(self) -> Iterator[_PgTx]:
        """Atomic transaction on one pooled connection: COMMIT on success, ROLLBACK on any exception.

        Isolation choice (money-correctness critical). SQLite's BEGIN IMMEDIATE serializes all writers
        globally. Postgres gets the same guarantee where it matters, per wallet, from explicit row locks:

        - Isolation is READ COMMITTED (pinned on every connection). Every money-moving transaction in
          wallet.py first runs `SELECT ... FROM wallets WHERE user_id=? FOR UPDATE` (apply_payment
          first locks the top-up row, then the wallet row). A second transaction for the same user
          blocks on that lock until the first commits or rolls back.
        - Under READ COMMITTED each later statement takes a fresh snapshot, so once the lock is granted
          the balance, held amounts and pending top-ups it reads include everything the previous holder
          committed. The read-check-write in reserve() cannot double-spend, and the absolute
          `UPDATE wallets SET balance_paise=?` in settle() cannot lose an update.
        - Every wallet invariant is per user (no API moves money between users), so per-wallet
          serialization is as strong as SQLite's global write lock for correctness, without making
          each user wait for every other user.
        - Lock order is always conversation row (chat) or top-up row (payment), then the wallet row,
          then hold rows. Expired-hold cleanup uses SKIP LOCKED and never waits. With no ordering
          cycles these paths cannot deadlock each other.

        SERIALIZABLE was not chosen. It is also safe, but it reports conflicts as serialization
        failures that the caller must retry, and this context-manager API cannot re-run its body.
        Row locks wait instead of failing and behave deterministically. The balance_paise >= 0 CHECK
        stays as a backstop.
        """
        with self.pool.connection() as conn:
            with conn.transaction():
                yield _PgTx(conn)

    def kv_get(self, k: str) -> str | None:
        row = self.one("SELECT v FROM kv WHERE k=?", (k,))
        return row["v"] if row else None

    def kv_set(self, k: str, v: str) -> None:
        self.x("INSERT INTO kv(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, v))

    def close(self) -> None:
        self.pool.close()


Database = SQLiteDB | PostgresDB


def open_db(database_url: str = "", database_path: Path | str = "data/campus_ai.db",
            *, pool_size: int = 10) -> Database:
    """DATABASE_URL set -> Postgres. Otherwise the SQLite file at DATABASE_PATH (local-dev default)."""
    if database_url:
        return PostgresDB(database_url, pool_size=pool_size)
    return SQLiteDB(database_path)
