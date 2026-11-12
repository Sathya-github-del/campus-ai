"""Accounts: user creation (anonymous and email/password), password hashing, conversation history,
and account deletion.

Tokens: every account, anonymous or email, authenticates the same way. A random bearer token is issued
and only its SHA-256 is stored in users.token_hash (api.resolve_user looks it up). An account holds one
token at a time, so a successful login issues a new token and the previous one stops working (signing
in on a new phone signs the old one out). See NOTES.md §3.

Passwords: argon2id (argon2-cffi) with OWASP's low-memory profile (19 MiB, 2 passes, 1 lane), not the
library default of 64 MiB: Render's free instance has 512 MB and a few logins at once must not exhaust
it. The parameters are stored inside each hash, so they can be raised later; login re-hashes old hashes
transparently.
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

from .db import Database, Tx
from .wallet import new_id

_hasher = PasswordHasher(time_cost=2, memory_cost=19 * 1024, parallelism=1)

MIN_PASSWORD_CHARS = 8
MAX_PASSWORD_CHARS = 256  # bounds hashing work per request
MAX_EMAIL_CHARS = 254
# Deliberately loose: one @, something on each side, a dot in the domain, no spaces. Whether the
# address really exists can only be proven by sending it mail, which isn't built yet.
_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

# Verified against when an email is unknown, so a login for a non-existent account takes as long as a
# wrong password for a real one (no timing signal that the email exists).
_DUMMY_HASH = _hasher.hash("timing-equaliser-not-a-real-password")


def normalise_email(raw: str) -> str:
    return raw.strip().lower()


def valid_email(email: str) -> bool:
    return len(email) <= MAX_EMAIL_CHARS and bool(_EMAIL_RE.match(email))


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password_hash: str | None, password: str) -> bool:
    """Constant-work check. A missing hash (unknown email) still runs one verification."""
    try:
        return _hasher.verify(password_hash or _DUMMY_HASH, password) and password_hash is not None
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def needs_rehash(password_hash: str) -> bool:
    return _hasher.check_needs_rehash(password_hash)


def create_user(c: Tx, token_hash: str, *, email: str | None = None, password_hash: str | None = None) -> str:
    """Insert a user and their (empty) wallet in the caller's transaction. Used by both anonymous
    registration and email signup, so every account gets a wallet the same way."""
    user_id = new_id("u")
    c.execute("INSERT INTO users(id, token_hash, created_at, email, password_hash) VALUES(?,?,?,?,?)",
              (user_id, token_hash, time.time(), email, password_hash))
    c.execute("INSERT INTO wallets(user_id) VALUES(?)", (user_id,))
    return user_id


# ---------------------------------------------------------------- history

FIRST_QUESTION_CHARS = 140


@dataclass
class ConversationSummary:
    conversation_id: str
    first_question: str
    created_at: float
    updated_at: float
    questions: int
    cost_units: int


def list_conversations(db: Database, user_id: str, limit: int) -> list[ConversationSummary]:
    """The user's conversations, most recently active first. cost_units is the sum of what each answer
    was billed (messages.charge_units), the same figure each chat response reported as cost_inr."""
    rows = db.q(
        "SELECT c.id, c.created_at, c.updated_at, "
        "(SELECT SUBSTR(m.content, 1, ?) FROM messages m WHERE m.conversation_id=c.id AND m.role='user' "
        " ORDER BY m.id LIMIT 1) AS first_question, "
        "(SELECT COUNT(*) FROM messages m WHERE m.conversation_id=c.id AND m.role='user') AS questions, "
        "(SELECT COALESCE(SUM(m.charge_units), 0) FROM messages m WHERE m.conversation_id=c.id "
        " AND m.role='assistant') AS cost_units "
        "FROM conversations c WHERE c.user_id=? ORDER BY c.updated_at DESC, c.id DESC LIMIT ?",
        (FIRST_QUESTION_CHARS + 1, user_id, limit))
    out = []
    for r in rows:
        text = r["first_question"] or ""
        if len(text) > FIRST_QUESTION_CHARS:
            text = text[:FIRST_QUESTION_CHARS - 1].rstrip() + "…"
        # int(): Postgres SUM/COUNT come back as Decimal / int8.
        out.append(ConversationSummary(r["id"], text, float(r["created_at"]), float(r["updated_at"]),
                                       int(r["questions"]), int(r["cost_units"])))
    return out


@dataclass
class ConversationMessage:
    role: str
    content: str
    tier: str | None
    cost_units: int  # 0 for user turns


def get_conversation(db: Database, user_id: str, conversation_id: str) -> list[ConversationMessage] | None:
    """Every turn of one conversation in order, or None if it doesn't exist or isn't user_id's."""
    owner = db.one("SELECT user_id FROM conversations WHERE id=?", (conversation_id,))
    if owner is None or owner["user_id"] != user_id:
        return None
    rows = db.q("SELECT role, content, tier, COALESCE(charge_units, 0) AS charge_units FROM messages "
                "WHERE conversation_id=? ORDER BY id", (conversation_id,))
    return [ConversationMessage(r["role"], r["content"], r["tier"], int(r["charge_units"])) for r in rows]


def delete_conversation(db: Database, user_id: str, conversation_id: str) -> bool:
    """Delete one conversation and its messages if it belongs to user_id. False if it doesn't exist or
    isn't theirs (the caller answers 404 either way). Ledger rows that reference it are kept: they are
    the money record, and the wallet balance is unaffected."""
    with db.tx() as c:
        row = c.execute("SELECT user_id FROM conversations WHERE id=?" + db.for_update,
                        (conversation_id,)).fetchone()
        if row is None or row[0] != user_id:
            return False
        c.execute("DELETE FROM messages WHERE conversation_id=?", (conversation_id,))
        c.execute("DELETE FROM conversations WHERE id=?", (conversation_id,))
        return True


# ---------------------------------------------------------------- account deletion

def delete_account(db: Database, user_id: str) -> None:
    """Delete the user and everything tied to them: conversations + messages, holds, top-ups, ledger,
    wallet, user row. Any remaining balance is forfeited: credits are closed-loop and there is no
    payout path (BLUEPRINT §5, §9).

    Locking follows the order documented in PostgresDB.tx(): the user's conversation rows, then their
    top-up rows, then the wallet row. All deletes run after the wallet lock, so (READ COMMITTED) they
    see every money move that committed before it. A chat or payment still in flight for this user
    either finished first or, once it gets the lock, finds the wallet gone and rolls back."""
    fu = db.for_update
    with db.tx() as c:
        c.execute("SELECT id FROM conversations WHERE user_id=? ORDER BY id" + fu, (user_id,)).fetchall()
        c.execute("SELECT id FROM topups WHERE user_id=? ORDER BY id" + fu, (user_id,)).fetchall()
        c.execute("SELECT user_id FROM wallets WHERE user_id=?" + fu, (user_id,)).fetchone()
        c.execute("DELETE FROM messages WHERE conversation_id IN "
                  "(SELECT id FROM conversations WHERE user_id=?)", (user_id,))
        c.execute("DELETE FROM conversations WHERE user_id=?", (user_id,))
        c.execute("DELETE FROM holds WHERE user_id=?", (user_id,))
        c.execute("DELETE FROM ledger WHERE user_id=?", (user_id,))
        c.execute("DELETE FROM topups WHERE user_id=?", (user_id,))
        c.execute("DELETE FROM wallets WHERE user_id=?", (user_id,))
        c.execute("DELETE FROM users WHERE id=?", (user_id,))
