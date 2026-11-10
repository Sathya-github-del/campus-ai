"""Wallet: balance, worst-case holds for in-flight chats, metered charges, idempotent top-up credit.

Credits are closed-loop: they can only be spent on messages in this app. There is no transfer,
withdrawal or cash-out path anywhere in the codebase, by design (BLUEPRINT §5, RBI PPI note).

Concurrency (works the same on SQLite and Postgres, see db.py): every transaction that reads a balance
and then writes based on it starts by locking that user's wallet row (`self.db.for_update`: a no-op on
SQLite, where BEGIN IMMEDIATE already holds the global write lock; `FOR UPDATE` on Postgres). Keep that
as the first statement of any new money-moving transaction.
"""
from __future__ import annotations

import secrets
import time
from dataclasses import dataclass

from .db import Database, Tx
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


# Balance and unexpired holds in ONE statement, so they come from one snapshot even outside a
# transaction (on Postgres two separate autocommit reads could straddle a settle and overstate the
# spendable balance).
_AVAILABLE_SQL = ("SELECT balance_paise, (SELECT COALESCE(SUM(paise), 0) FROM holds "
                  "WHERE user_id=? AND created_at>=?) AS held FROM wallets WHERE user_id=?")


class Wallet:
    def __init__(self, db: Database, hold_ttl_seconds: float = 600):
        self.db = db
        self.hold_ttl = hold_ttl_seconds

    # --- reads ---
    @staticmethod
    def _spendable(row) -> int:
        if row is None:
            return 0
        # int(): Postgres SUM(bigint) is NUMERIC (Decimal in Python); keep wallet math in ints.
        return max(0, int(row[0]) - int(row[1]))

    def _available(self, c: Tx, user_id: str, now: float) -> int:
        return self._spendable(c.execute(_AVAILABLE_SQL, (user_id, now - self.hold_ttl, user_id)).fetchone())

    def available_paise(self, user_id: str) -> int:
        """Spendable balance: stored balance minus reservations of chats still in flight."""
        row = self.db.one(_AVAILABLE_SQL, (user_id, time.time() - self.hold_ttl, user_id))
        return self._spendable(None if row is None else (row["balance_paise"], row["held"]))

    def _lock_wallet(self, c: Tx, user_id: str):
        """First statement of every money transaction: serializes all money moves for this user."""
        return c.execute("SELECT balance_paise, credit_units FROM wallets WHERE user_id=?" + self.db.for_update,
                         (user_id,)).fetchone()

    # --- chat holds ---
    def reserve(self, user_id: str, paise: int) -> str | None:
        """Atomically reserve `paise` for one chat call; None if the wallet can't cover it."""
        if paise <= 0:
            raise ValueError("hold must be positive")
        now = time.time()
        with self.db.tx() as c:
            self._lock_wallet(c, user_id)
            if self.db.dialect == "postgres":
                # Housekeeping only (_available ignores expired holds anyway). SKIP LOCKED: never wait on
                # another user's in-flight settle, so this can't take part in a deadlock.
                c.execute("DELETE FROM holds WHERE id IN "
                          "(SELECT id FROM holds WHERE created_at<? FOR UPDATE SKIP LOCKED)", (now - self.hold_ttl,))
            else:
                c.execute("DELETE FROM holds WHERE created_at<?", (now - self.hold_ttl,))
            if self._available(c, user_id, now) < paise:
                return None
            hold_id = new_id("hold")
            c.execute("INSERT INTO holds(id, user_id, paise, created_at) VALUES(?,?,?,?)",
                      (hold_id, user_id, paise, now))
            return hold_id

    def release(self, hold_id: str) -> None:
        # Single statement, and releasing only ever increases what is spendable: no lock needed.
        self.db.x("DELETE FROM holds WHERE id=?", (hold_id,))

    def settle(self, c: Tx, user_id: str, hold_id: str, cost_units: int, ref: str,
               absorbed_units: int = 0) -> ChargeResult:
        """Release the hold and debit the metered cost. Call inside db.tx() so it commits together with
        the conversation rows it pays for."""
        now = time.time()
        row = self._lock_wallet(c, user_id)  # lock before reading: the UPDATE below writes absolute values
        c.execute("DELETE FROM holds WHERE id=?", (hold_id,))
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
            # Locked so two concurrent top-ups can't both pass the wallet-cap check.
            bal = self._lock_wallet(c, user_id)[0]
            pending = int(c.execute("SELECT COALESCE(SUM(amount_paise), 0) FROM topups WHERE user_id=? AND "
                                    "status='pending' AND created_at>=?", (user_id, now - 86400)).fetchone()[0])
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
            # Lock the top-up row: two concurrent deliveries of the same webhook must not both see
            # 'pending' and both credit the wallet.
            t = c.execute("SELECT user_id, amount_paise, status FROM topups WHERE id=?" + self.db.for_update,
                          (topup_id,)).fetchone()
            if t is None:
                raise TopupError("topup_not_found")
            user_id, expected, status = t
            if status == "paid":
                return user_id  # duplicate delivery: already credited
            if status != "pending":
                raise TopupError("topup_not_pending")
            if amount_paise != expected:
                raise TopupError("amount_mismatch")
            if c.execute("UPDATE topups SET status='paid', paid_at=?, provider_ref=COALESCE(?, provider_ref) "
                         "WHERE id=? AND status='pending'", (now, provider_ref, topup_id)).rowcount != 1:
                raise TopupError("topup_not_pending")  # defence in depth; the row lock makes this unreachable
            c.execute("UPDATE wallets SET balance_paise=balance_paise+? WHERE user_id=?", (expected, user_id))
            bal = c.execute("SELECT balance_paise FROM wallets WHERE user_id=?", (user_id,)).fetchone()[0]
            c.execute("INSERT INTO ledger(user_id, ts, kind, amount_paise, balance_after_paise, ref) "
                      "VALUES(?,?,?,?,?,?)", (user_id, now, "topup", expected, bal, topup_id))
            return user_id
