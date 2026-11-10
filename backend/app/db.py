"""SQLite persistence (stdlib only): users, wallet + ledger, top-ups, conversations, messages.

One connection guarded by a lock; every call is short. Writes that move money run inside
BEGIN IMMEDIATE transactions so they are atomic and serialized even across processes.
v1 only: move to Postgres before real scale (see README).
"""
from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

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


class DB:
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
