"""Wallet: balance, worst-case holds for in-flight chats, metered charges, idempotent top-up credit.

Credits are closed-loop: they can only be spent on messages in this app. There is no transfer,
withdrawal or cash-out path anywhere in the codebase, by design (BLUEPRINT §5, RBI PPI note).
"""
from __future__ import annotations

import secrets
import sqlite3
import time
from dataclasses import dataclass

from .db import DB
from .money import apply_charge


def new_id(prefix: str) -> str:
    return f"{prefix}_{secrets.token_hex(12)}"


class TopupError(ValueError):
    pass


@dataclass
class ChargeResult:
    paise_taken: int
    shortfall_units: int
    available_paise: int


class Wallet:
    def __init__(self, db: DB, hold_ttl_seconds: float = 600):
        self.db = db
        self.hold_ttl = hold_ttl_seconds

    # --- reads ---
    def _available(self, c: sqlite3.Connection, user_id: str, now: float) -> int:
        bal = c.execute("SELECT balance_paise FROM wallets WHERE user_id=?", (user_id,)).fetchone()
        if bal is None:
            return 0
        held = c.execute("SELECT COALESCE(SUM(paise), 0) FROM holds WHERE user_id=? AND created_at>=?",
                         (user_id, now - self.hold_ttl)).fetchone()[0]
        return max(0, bal[0] - held)

    def available_paise(self, user_id: str) -> int:
        """Spendable balance: stored balance minus reservations of chats still in flight."""
        with self.db.lock:
            return self._available(self.db.conn, user_id, time.time())

    # --- chat holds ---
    def reserve(self, user_id: str, paise: int) -> str | None:
        """Atomically reserve `paise` for one chat call; None if the wallet can't cover it."""
        if paise <= 0:
            raise ValueError("hold must be positive")
        now = time.time()
        with self.db.tx() as c:
            c.execute("DELETE FROM holds WHERE created_at<?", (now - self.hold_ttl,))
            if self._available(c, user_id, now) < paise:
                return None
            hold_id = new_id("hold")
            c.execute("INSERT INTO holds(id, user_id, paise, created_at) VALUES(?,?,?,?)",
                      (hold_id, user_id, paise, now))
            return hold_id

    def release(self, hold_id: str) -> None:
        self.db.x("DELETE FROM holds WHERE id=?", (hold_id,))

    def settle(self, c: sqlite3.Connection, user_id: str, hold_id: str, cost_units: int, ref: str,
               absorbed_units: int = 0) -> ChargeResult:
        """Release the hold and debit the metered cost. Call inside db.tx() so it commits together with
        the conversation rows it pays for."""
        now = time.time()
        c.execute("DELETE FROM holds WHERE id=?", (hold_id,))
        row = c.execute("SELECT balance_paise, credit_units FROM wallets WHERE user_id=?", (user_id,)).fetchone()
        balance, credit, taken, shortfall = apply_charge(row[0], row[1], cost_units)
        shortfall += absorbed_units  # cost the platform ate (recorded for reconciliation)
        c.execute("UPDATE wallets SET balance_paise=?, credit_units=? WHERE user_id=?", (balance, credit, user_id))
        c.execute("INSERT INTO ledger(user_id, ts, kind, amount_paise, cost_units, shortfall_units, "
                  "balance_after_paise, ref) VALUES(?,?,?,?,?,?,?,?)",
                  (user_id, now, "chat", -taken, cost_units, shortfall, balance, ref))
        return ChargeResult(taken, shortfall, self._available(c, user_id, now))

    # --- top-ups ---
    def create_topup(self, user_id: str, amount_paise: int, provider: str, max_balance_paise: int) -> str:
        now = time.time()
        with self.db.tx() as c:
            bal = c.execute("SELECT balance_paise FROM wallets WHERE user_id=?", (user_id,)).fetchone()[0]
            pending = c.execute("SELECT COALESCE(SUM(amount_paise), 0) FROM topups WHERE user_id=? AND "
                                "status='pending' AND created_at>=?", (user_id, now - 86400)).fetchone()[0]
            if bal + pending + amount_paise > max_balance_paise:
                raise TopupError("wallet_limit")
            topup_id = new_id("tp")
            c.execute("INSERT INTO topups(id, user_id, amount_paise, status, provider, created_at) "
                      "VALUES(?,?,?,?,?,?)", (topup_id, user_id, amount_paise, "pending", provider, now))
            return topup_id

    def set_provider_ref(self, topup_id: str, provider_ref: str) -> None:
        self.db.x("UPDATE topups SET provider_ref=? WHERE id=?", (provider_ref, topup_id))

    def get_topup(self, topup_id: str) -> dict | None:
        return self.db.one("SELECT * FROM topups WHERE id=?", (topup_id,))

    def apply_payment(self, topup_id: str, amount_paise: int, provider_ref: str | None) -> str:
        """Credit a paid top-up exactly once (payment webhooks are retried). Returns the user id."""
        now = time.time()
        with self.db.tx() as c:
            t = c.execute("SELECT user_id, amount_paise, status FROM topups WHERE id=?", (topup_id,)).fetchone()
            if t is None:
                raise TopupError("topup_not_found")
            user_id, expected, status = t
            if status == "paid":
                return user_id  # duplicate delivery: already credited
            if status != "pending":
                raise TopupError("topup_not_pending")
            if amount_paise != expected:
                raise TopupError("amount_mismatch")
            c.execute("UPDATE topups SET status='paid', paid_at=?, provider_ref=COALESCE(?, provider_ref) "
                      "WHERE id=? AND status='pending'", (now, provider_ref, topup_id))
            c.execute("UPDATE wallets SET balance_paise=balance_paise+? WHERE user_id=?", (expected, user_id))
            bal = c.execute("SELECT balance_paise FROM wallets WHERE user_id=?", (user_id,)).fetchone()[0]
            c.execute("INSERT INTO ledger(user_id, ts, kind, amount_paise, balance_after_paise, ref) "
                      "VALUES(?,?,?,?,?,?)", (user_id, now, "topup", expected, bal, topup_id))
            return user_id
