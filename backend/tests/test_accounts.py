"""Email/password accounts, conversation history and account deletion, through the HTTP API."""
from __future__ import annotations

import sqlite3

import pytest

from app import accounts
from app.api import FORGOT_PASSWORD_MESSAGE, hash_token
from app.db import SQLiteDB

from .conftest import register, top_up

PASSWORD = "correct horse battery"


def signup(client, email="asha@example.com", password=PASSWORD) -> dict:
    r = client.post("/v1/auth/signup", json={"email": email, "password": password})
    assert r.status_code == 200, r.text
    assert set(r.json()) == {"token"}
    return {"Authorization": f"Bearer {r.json()['token']}"}


def user_id(client, headers) -> str:
    token = headers["Authorization"].split(" ", 1)[1]
    return client.app.state.db.one("SELECT id FROM users WHERE token_hash=?", (hash_token(token),))["id"]


def ask(client, headers, message="What is 2+2?", conversation_id=None) -> dict:
    r = client.post("/v1/chat", headers=headers,
                    json={"tier": "fast", "message": message, "conversation_id": conversation_id})
    assert r.status_code == 200, r.text
    return r.json()


def count(client, table: str, user: str) -> int:
    db = client.app.state.db
    if table == "messages":
        sql = "SELECT COUNT(*) AS n FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE user_id=?)"
    elif table == "users":
        sql = "SELECT COUNT(*) AS n FROM users WHERE id=?"
    else:
        sql = f"SELECT COUNT(*) AS n FROM {table} WHERE user_id=?"
    return int(db.one(sql, (user,))["n"])


# ------------------------------------------------------------------ signup / login

def test_signup_creates_user_with_wallet_and_hashed_password(client):
    h = signup(client)
    assert client.get("/v1/wallet", headers=h).json() == {"balance_inr": 0.0}
    row = client.app.state.db.one("SELECT email, password_hash FROM users WHERE id=?", (user_id(client, h),))
    assert row["email"] == "asha@example.com"
    assert row["password_hash"].startswith("$argon2id$") and PASSWORD not in row["password_hash"]
    assert accounts.verify_password(row["password_hash"], PASSWORD)
    assert not accounts.verify_password(row["password_hash"], PASSWORD + "x")


def test_password_hashes_are_salted():
    assert accounts.hash_password(PASSWORD) != accounts.hash_password(PASSWORD)


def test_signed_up_account_can_top_up_and_chat(client):
    h = signup(client)
    top_up(client, h, 50)
    assert ask(client, h)["cost_inr"] > 0


def test_duplicate_signup_rejected_case_insensitively(client):
    signup(client, "asha@example.com")
    r = client.post("/v1/auth/signup", json={"email": "  ASHA@Example.com ", "password": "another password"})
    assert r.status_code == 409 and r.json()["error"] == "email_taken"
    assert client.app.state.db.one("SELECT COUNT(*) AS n FROM users")["n"] == 1


@pytest.mark.parametrize("email,password,code", [
    ("not-an-email", PASSWORD, "invalid_email"),
    ("a@b", PASSWORD, "invalid_email"),
    ("two words@example.com", PASSWORD, "invalid_email"),
    ("", PASSWORD, "invalid_email"),
    ("asha@example.com", "short", "weak_password"),
    ("asha@example.com", "x" * (accounts.MAX_PASSWORD_CHARS + 1), "weak_password"),
])
def test_signup_validation(client, email, password, code):
    r = client.post("/v1/auth/signup", json={"email": email, "password": password})
    assert r.status_code == 400 and r.json()["error"] == code
    assert client.app.state.db.one("SELECT COUNT(*) AS n FROM users")["n"] == 0


def test_signup_and_login_reject_malformed_bodies(client):
    for path in ("/v1/auth/signup", "/v1/auth/login"):
        assert client.post(path, json={"email": "a@b.co"}).status_code == 422
        assert client.post(path, json={"email": 5, "password": PASSWORD}).status_code == 422


def test_login_returns_a_working_token_and_signs_out_the_old_one(client):
    old = signup(client, "Ravi@Example.com")
    top_up(client, old, 30)
    r = client.post("/v1/auth/login", json={"email": "ravi@example.com", "password": PASSWORD})
    assert r.status_code == 200 and set(r.json()) == {"token"}
    new = {"Authorization": f"Bearer {r.json()['token']}"}
    assert client.get("/v1/wallet", headers=new).json() == {"balance_inr": 30.0}  # same account
    assert client.get("/v1/wallet", headers=old).status_code == 401  # one token per account


def test_login_failures_are_generic(client):
    signup(client, "asha@example.com")
    wrong = client.post("/v1/auth/login", json={"email": "asha@example.com", "password": PASSWORD + "!"})
    unknown = client.post("/v1/auth/login", json={"email": "nobody@example.com", "password": PASSWORD})
    too_long = client.post("/v1/auth/login", json={"email": "asha@example.com", "password": "x" * 300})
    for r in (wrong, unknown, too_long):
        assert r.status_code == 401
        assert r.json() == {"error": "invalid_credentials", "message": "Incorrect email or password."}


def test_anonymous_accounts_cannot_log_in(client):
    register(client)
    r = client.post("/v1/auth/login", json={"email": "", "password": PASSWORD})
    assert r.status_code == 401 and r.json()["error"] == "invalid_credentials"


def test_anonymous_register_still_works_alongside_signup(client):
    signup(client)
    h = register(client)
    assert client.get("/v1/wallet", headers=h).status_code == 200
    rows = client.app.state.db.q("SELECT email, password_hash FROM users ORDER BY email")
    assert [r["email"] for r in rows].count(None) == 1  # anonymous user: no email, no password


def test_login_is_rate_limited_per_email(client, settings):
    signup(client)
    for _ in range(settings.login_limit_per_15min_per_email):
        client.post("/v1/auth/login", json={"email": "asha@example.com", "password": "wrong password"})
    r = client.post("/v1/auth/login", json={"email": "asha@example.com", "password": PASSWORD})
    assert r.status_code == 429 and "Retry-After" in r.headers
    # other accounts aren't affected
    signup(client, "ravi@example.com")
    assert client.post("/v1/auth/login", json={"email": "ravi@example.com", "password": PASSWORD}).status_code == 200


def test_forgot_password_never_reveals_whether_an_email_exists(client):
    signup(client, "asha@example.com")
    known = client.post("/v1/auth/forgot-password", json={"email": "asha@example.com"})
    unknown = client.post("/v1/auth/forgot-password", json={"email": "nobody@example.com"})
    junk = client.post("/v1/auth/forgot-password", json={"email": "junk"})
    for r in (known, unknown, junk):
        assert r.status_code == 200 and r.json() == {"message": FORGOT_PASSWORD_MESSAGE}


# ------------------------------------------------------------------ history

def test_history_lists_only_the_callers_conversations_newest_first(client, fake):
    a, b = signup(client, "a@example.com"), signup(client, "b@example.com")
    top_up(client, a, 100)
    top_up(client, b, 100)
    assert client.get("/v1/history", headers=a).json() == {"conversations": []}

    first = ask(client, a, "Explain photosynthesis")
    ask(client, a, "And respiration?", first["conversation_id"])
    ask(client, a, "Compare the two", first["conversation_id"])
    second = ask(client, a, "x" * 500)
    theirs = ask(client, b, "B's private question")

    r = client.get("/v1/history", headers=a)
    assert r.status_code == 200
    convs = r.json()["conversations"]
    assert [c["conversation_id"] for c in convs] == [second["conversation_id"], first["conversation_id"]]
    assert all(set(c) == {"conversation_id", "first_question", "created_at", "updated_at", "questions",
                          "cost_inr"} for c in convs)
    newest, older = convs
    assert older["first_question"] == "Explain photosynthesis" and older["questions"] == 3
    assert newest["questions"] == 1
    assert len(newest["first_question"]) == accounts.FIRST_QUESTION_CHARS and newest["first_question"].endswith("…")
    assert older["created_at"].endswith("Z") and older["updated_at"] >= older["created_at"]
    # total cost = what the three chat responses reported (to display rounding of 0.0001 each)
    assert older["cost_inr"] > 0 and abs(older["cost_inr"] - 3 * first["cost_inr"]) <= 0.0003

    other = client.get("/v1/history", headers=b).json()["conversations"]
    assert [c["conversation_id"] for c in other] == [theirs["conversation_id"]]


def test_history_limit_and_auth(client):
    h = signup(client)
    top_up(client, h, 100)
    for i in range(3):
        ask(client, h, f"question {i}")
    assert len(client.get("/v1/history?limit=2", headers=h).json()["conversations"]) == 2
    assert client.get("/v1/history?limit=0", headers=h).status_code == 422
    assert client.get("/v1/history").status_code == 401
    assert client.delete("/v1/history/conv_x").status_code == 401


def test_delete_history_removes_conversation_and_messages_only(client):
    h = signup(client)
    top_up(client, h, 100)
    keep, drop = ask(client, h, "keep me"), ask(client, h, "delete me")
    balance = client.get("/v1/wallet", headers=h).json()

    r = client.delete(f"/v1/history/{drop['conversation_id']}", headers=h)
    assert r.status_code == 204 and r.content == b""
    ids = [c["conversation_id"] for c in client.get("/v1/history", headers=h).json()["conversations"]]
    assert ids == [keep["conversation_id"]]
    db = client.app.state.db
    assert db.one("SELECT COUNT(*) AS n FROM messages WHERE conversation_id=?", (drop["conversation_id"],))["n"] == 0
    assert client.get("/v1/wallet", headers=h).json() == balance  # money is untouched
    # the deleted conversation can't be continued, and deleting it again is a 404
    r = client.post("/v1/chat", headers=h, json={"tier": "fast", "message": "hi",
                                                 "conversation_id": drop["conversation_id"]})
    assert r.status_code == 404
    assert client.delete(f"/v1/history/{drop['conversation_id']}", headers=h).status_code == 404


def test_delete_history_enforces_ownership(client):
    owner, other = signup(client, "a@example.com"), signup(client, "b@example.com")
    top_up(client, owner, 100)
    conv = ask(client, owner)["conversation_id"]
    for cid in (conv, "conv_does_not_exist", "x" * 100):
        r = client.delete(f"/v1/history/{cid}", headers=other)
        assert r.status_code == 404 and r.json()["error"] == "conversation_not_found"
    assert [c["conversation_id"] for c in client.get("/v1/history", headers=owner).json()["conversations"]] == [conv]


# ------------------------------------------------------------------ account deletion

def test_delete_account_removes_everything_and_forfeits_balance(client):
    victim, bystander = signup(client, "a@example.com"), signup(client, "b@example.com")
    top_up(client, victim, 100)
    top_up(client, bystander, 100)
    ask(client, victim)
    ask(client, bystander)
    client.post("/v1/wallet/topup", json={"amount_inr": 20}, headers=victim)  # a pending top-up too
    vid, bid = user_id(client, victim), user_id(client, bystander)
    tables = ("users", "wallets", "conversations", "messages", "ledger", "topups")
    before = {t: count(client, t, bid) for t in tables}
    assert all(count(client, t, vid) > 0 for t in tables)

    r = client.delete("/v1/account", headers=victim)
    assert r.status_code == 204 and r.content == b""
    assert all(count(client, t, vid) == 0 for t in tables + ("holds",))
    assert client.get("/v1/wallet", headers=victim).status_code == 401
    assert client.delete("/v1/account", headers=victim).status_code == 401
    # nobody else is touched
    assert {t: count(client, t, bid) for t in tables} == before
    assert client.get("/v1/wallet", headers=bystander).json()["balance_inr"] < 100
    # the email is free again; the new account starts empty (balance was forfeited, not carried over)
    h = signup(client, "a@example.com")
    assert client.get("/v1/wallet", headers=h).json() == {"balance_inr": 0.0}
    assert client.post("/v1/auth/login", json={"email": "a@example.com", "password": PASSWORD}).status_code == 200


def test_anonymous_account_can_be_deleted(client):
    h = register(client)
    assert client.delete("/v1/account", headers=h).status_code == 204
    assert client.app.state.db.one("SELECT COUNT(*) AS n FROM users")["n"] == 0


def test_delete_account_requires_auth(client):
    signup(client)
    assert client.delete("/v1/account").status_code == 401
    assert client.delete("/v1/account", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.app.state.db.one("SELECT COUNT(*) AS n FROM users")["n"] == 1


# ------------------------------------------------------------------ schema migration

OLD_USERS_SQLITE = "CREATE TABLE users (id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, created_at REAL NOT NULL)"


def test_sqlite_migration_adds_account_columns_to_an_old_database(tmp_path):
    path = tmp_path / "old.db"
    conn = sqlite3.connect(path)
    conn.execute(OLD_USERS_SQLITE)
    conn.execute("INSERT INTO users VALUES ('u_old', 'h', 1.0)")
    conn.commit()
    conn.close()

    db = SQLiteDB(path)
    try:
        cols = {r["name"] for r in db.q("PRAGMA table_info(users)")}
        assert {"email", "password_hash"} <= cols
        assert db.one("SELECT email FROM users WHERE id='u_old'") == {"email": None}  # data kept
        db.x("UPDATE users SET email='a@example.com' WHERE id='u_old'")
        with pytest.raises(sqlite3.IntegrityError):  # unique index exists
            db.x("INSERT INTO users(id, token_hash, created_at, email) VALUES('u2', 'h2', 1, 'a@example.com')")
        db.x("INSERT INTO users(id, token_hash, created_at) VALUES('u3', 'h3', 1)")  # NULLs don't collide
        db.x("INSERT INTO users(id, token_hash, created_at) VALUES('u4', 'h4', 1)")
    finally:
        db.close()
    SQLiteDB(path).close()  # second start: nothing left to migrate, no error
