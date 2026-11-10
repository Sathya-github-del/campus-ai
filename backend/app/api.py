"""HTTP API (FastAPI). The request/response shapes here are the contract the Android client is built
against - do not add, rename or remove response fields without updating the client.

Run:  uvicorn --factory app.api:create_app      (see run.sh)
"""
from __future__ import annotations

import asyncio
import contextlib
import hashlib
import html
import logging
import secrets
from datetime import datetime, timezone
from decimal import Decimal
from typing import Literal

from fastapi import Body, Depends, FastAPI, Query, Request, Response
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, ConfigDict, Field

from . import accounts
from .catalog import TIER_LABELS, Catalog
from .chat import ChatRequestData, ChatService, estimate_message_inr
from .config import TIERS, Settings
from .db import Database, integrity_errors, open_db
from .errors import APIError, install_handlers
from .money import AmountError, inr_to_paise, paise_to_inr, units_to_inr
from .openrouter import OpenRouterClient
from .payments import MockPaymentProvider, PaymentProvider, WebhookError, build_provider
from .ratelimit import RateLimiter
from .wallet import TopupError, Wallet

log = logging.getLogger(__name__)

Tier = Literal["fast", "smart", "expert"]


# ---------------------------------------------------------------- contract models

class TokenOut(BaseModel):
    token: str


class CredentialsIn(BaseModel):
    # Lengths are checked in the handlers (clear 400s); these caps only bound request size.
    email: str = Field(max_length=1000)
    password: str = Field(max_length=1000)


class ForgotPasswordIn(BaseModel):
    email: str = Field(max_length=1000)


class MessageOut(BaseModel):
    message: str


class ConversationOut(BaseModel):
    conversation_id: str
    first_question: str
    created_at: str
    updated_at: str
    questions: int
    cost_inr: float


class HistoryOut(BaseModel):
    conversations: list[ConversationOut]


class WalletOut(BaseModel):
    balance_inr: float


class TopupIn(BaseModel):
    amount_inr: Decimal = Field(allow_inf_nan=False)


class TopupOut(BaseModel):
    checkout_url: str
    topup_id: str


class ConfirmIn(BaseModel):
    topup_id: str = Field(min_length=1, max_length=64)


class TierOut(BaseModel):
    tier: Tier
    label: str
    est_cost_per_message_inr: float


class ModelsOut(BaseModel):
    tiers: list[TierOut]


class ChatIn(BaseModel):
    model_config = ConfigDict(extra="ignore")
    tier: Tier
    message: str = ""
    conversation_id: str | None = Field(default=None, max_length=64)
    image_base64: str | None = None


class ChatOut(BaseModel):
    reply: str
    conversation_id: str
    cost_inr: float
    wallet_balance_inr: float
    truncated: bool = False


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_token() -> tuple[str, str]:
    """(token for the client, hash to store). The only way tokens are minted, for every auth route."""
    token = secrets.token_urlsafe(32)
    return token, hash_token(token)


def iso_utc(ts: float) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


FORGOT_PASSWORD_MESSAGE = ("If an account exists for that email, we've sent it a link to reset your password. "
                           "Check your inbox and spam folder.")


def create_app(settings: Settings | None = None, *, client=None, provider: PaymentProvider | None = None,
               db: Database | None = None, refresh_models: bool = True) -> FastAPI:
    if not logging.getLogger().handlers:
        logging.basicConfig(level=logging.INFO, format="%(levelname)s:     %(name)s: %(message)s")
    settings = settings or Settings.from_env()
    owns_db = db is None
    if db is None:
        db = open_db(settings.database_url, settings.database_path, pool_size=settings.db_pool_size)
    client = client or OpenRouterClient(settings)
    provider = provider or build_provider(settings)
    wallet = Wallet(db, hold_ttl_seconds=settings.upstream_timeout_seconds * 2 + 60)
    catalog = Catalog(settings, db)
    catalog.load_saved()
    chat = ChatService(settings, db, wallet, catalog, client)

    general_limit = RateLimiter(settings.rate_limit_per_minute, 60)
    chat_limit = RateLimiter(settings.chat_rate_limit_per_minute, 60)
    register_limit = RateLimiter(settings.register_limit_per_hour_per_ip, 3600)
    login_email_limit = RateLimiter(settings.login_limit_per_15min_per_email, 900)
    login_ip_limit = RateLimiter(settings.login_limit_per_15min_per_ip, 900)
    forgot_limit = RateLimiter(settings.register_limit_per_hour_per_ip, 3600)
    dev_payments = settings.dev_mode and isinstance(provider, MockPaymentProvider)

    @contextlib.asynccontextmanager
    async def lifespan(_: FastAPI):
        task = None
        if refresh_models:
            await catalog.refresh(client)

            async def loop():
                while True:
                    await asyncio.sleep(settings.models_refresh_seconds)
                    await catalog.refresh(client)

            task = asyncio.create_task(loop())
        yield
        if task:
            task.cancel()
        if hasattr(client, "aclose"):
            await client.aclose()
        if owns_db:
            db.close()

    app = FastAPI(title="Straight Answer backend (campus-ai)", version="0.2.0", lifespan=lifespan)
    install_handlers(app)
    app.state.settings, app.state.db, app.state.wallet, app.state.catalog = settings, db, wallet, catalog

    def limited(limiter: RateLimiter, key: str) -> None:
        wait = limiter.check(key)
        if wait:
            raise APIError(429, "rate_limited", f"Too many requests. Try again in {wait} seconds.",
                           headers={"Retry-After": str(wait)})

    def resolve_user(request: Request) -> str | None:
        auth = request.headers.get("authorization", "")
        scheme, _, token = auth.partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            return None
        row = db.one("SELECT id FROM users WHERE token_hash=?", (hash_token(token.strip()),))
        return row["id"] if row else None

    def current_user(request: Request) -> str:
        user_id = resolve_user(request)
        if user_id is None:
            raise APIError(401, "unauthorized", "You're signed out. Please sign in again.",
                           headers={"WWW-Authenticate": "Bearer"})
        limited(general_limit, user_id)
        return user_id

    # ------------------------------------------------------------ auth
    def client_ip(request: Request) -> str:
        return request.client.host if request.client else "unknown"

    @app.post("/v1/auth/register", response_model=TokenOut)
    def register(request: Request, _body: dict | None = Body(default=None)):
        """Anonymous account (no email): kept for quick testing."""
        limited(register_limit, f"ip:{client_ip(request)}")
        token, token_hash = new_token()
        with db.tx() as c:
            accounts.create_user(c, token_hash)
        return {"token": token}

    @app.post("/v1/auth/signup", response_model=TokenOut)
    def signup(body: CredentialsIn, request: Request):
        email = accounts.normalise_email(body.email)
        if not accounts.valid_email(email):
            raise APIError(400, "invalid_email", "Enter a valid email address.")
        if len(body.password) < accounts.MIN_PASSWORD_CHARS:
            raise APIError(400, "weak_password",
                           f"Use a password with at least {accounts.MIN_PASSWORD_CHARS} characters.")
        if len(body.password) > accounts.MAX_PASSWORD_CHARS:
            raise APIError(400, "weak_password",
                           f"Use a password with at most {accounts.MAX_PASSWORD_CHARS} characters.")
        limited(register_limit, f"ip:{client_ip(request)}")
        taken = APIError(409, "email_taken", "An account with this email already exists. Sign in instead.")
        if db.one("SELECT 1 FROM users WHERE email=?", (email,)):
            raise taken
        password_hash = accounts.hash_password(body.password)  # slow on purpose: outside any transaction
        token, token_hash = new_token()
        try:
            with db.tx() as c:
                accounts.create_user(c, token_hash, email=email, password_hash=password_hash)
        except integrity_errors():  # lost a race with a simultaneous signup (unique index on email)
            raise taken
        return {"token": token}

    @app.post("/v1/auth/login", response_model=TokenOut)
    def login(body: CredentialsIn, request: Request):
        email = accounts.normalise_email(body.email)
        limited(login_ip_limit, f"ip:{client_ip(request)}")
        limited(login_email_limit, f"email:{email}")
        row = db.one("SELECT id, password_hash FROM users WHERE email=?", (email,))
        # Same work and the same answer whether the email is unknown or the password is wrong
        # (verify_password runs a dummy verification when there is no hash). An over-long password
        # can't match any stored one, so it's refused without hashing it.
        ok = (len(body.password) <= accounts.MAX_PASSWORD_CHARS
              and accounts.verify_password(row["password_hash"] if row else None, body.password))
        if not ok or row is None:
            raise APIError(401, "invalid_credentials", "Incorrect email or password.")
        token, token_hash = new_token()
        if accounts.needs_rehash(row["password_hash"]):
            db.x("UPDATE users SET token_hash=?, password_hash=? WHERE id=?",
                 (token_hash, accounts.hash_password(body.password), row["id"]))
        else:
            # One token per account (see accounts.py): this signs out any other device.
            db.x("UPDATE users SET token_hash=? WHERE id=?", (token_hash, row["id"]))
        return {"token": token}

    @app.post("/v1/auth/forgot-password", response_model=MessageOut)
    def forgot_password(body: ForgotPasswordIn, request: Request):
        """STUB: email delivery isn't built (NOTES.md §3). Always gives the same answer, so it can't be
        used to find out which emails have accounts."""
        limited(forgot_limit, f"ip:{client_ip(request)}")
        return {"message": FORGOT_PASSWORD_MESSAGE}

    # ------------------------------------------------------------ account
    @app.delete("/v1/account", status_code=204, response_class=Response)
    def delete_account(user_id: str = Depends(current_user)):
        """Deletes everything tied to the account. Remaining balance is forfeited (closed-loop credit,
        no payout path; BLUEPRINT §5, §9)."""
        accounts.delete_account(db, user_id)
        return Response(status_code=204)

    # ------------------------------------------------------------ history
    @app.get("/v1/history", response_model=HistoryOut)
    def history(limit: int = Query(50, ge=1, le=200), user_id: str = Depends(current_user)):
        return {"conversations": [
            {"conversation_id": c.conversation_id, "first_question": c.first_question,
             "created_at": iso_utc(c.created_at), "updated_at": iso_utc(c.updated_at),
             "questions": c.questions, "cost_inr": units_to_inr(c.cost_units)}
            for c in accounts.list_conversations(db, user_id, limit)]}

    @app.delete("/v1/history/{conversation_id}", status_code=204, response_class=Response)
    async def delete_history(conversation_id: str, user_id: str = Depends(current_user)):
        if len(conversation_id) > 64:
            raise APIError(404, "conversation_not_found", "Conversation not found.")
        # Wait for an answer still being generated in this conversation to be saved and charged first.
        async with chat.conversation_lock(conversation_id):
            if not accounts.delete_conversation(db, user_id, conversation_id):
                raise APIError(404, "conversation_not_found", "Conversation not found.")
        return Response(status_code=204)

    # ------------------------------------------------------------ wallet
    @app.get("/v1/wallet", response_model=WalletOut)
    def get_wallet(user_id: str = Depends(current_user)):
        return {"balance_inr": paise_to_inr(wallet.available_paise(user_id))}

    @app.post("/v1/wallet/topup", response_model=TopupOut)
    def topup(body: TopupIn, user_id: str = Depends(current_user)):
        try:
            paise = inr_to_paise(body.amount_inr)
        except AmountError as e:
            raise APIError(400, "invalid_amount", str(e))
        lo, hi = inr_to_paise(settings.min_topup_inr), inr_to_paise(settings.max_topup_inr)
        if paise < lo:
            raise APIError(400, "invalid_amount", f"Minimum top-up is ₹{settings.min_topup_inr}.",
                           min_inr=float(settings.min_topup_inr))
        if paise > hi:
            raise APIError(400, "invalid_amount", f"Maximum top-up is ₹{settings.max_topup_inr}.",
                           max_inr=float(settings.max_topup_inr))
        try:
            topup_id = wallet.create_topup(user_id, paise, provider.name,
                                           inr_to_paise(settings.max_wallet_balance_inr))
        except TopupError:
            raise APIError(400, "wallet_limit",
                           f"Your wallet can hold at most ₹{settings.max_wallet_balance_inr}.")
        checkout = provider.create_checkout(topup_id, paise, user_id)
        if checkout.provider_ref:
            wallet.set_provider_ref(topup_id, checkout.provider_ref)
        return {"checkout_url": checkout.checkout_url, "topup_id": topup_id}

    def _apply(event) -> str:
        try:
            return wallet.apply_payment(event.topup_id, event.amount_paise, event.provider_ref)
        except TopupError as e:
            code = str(e)
            raise APIError(404 if code == "topup_not_found" else 409, code, {
                "topup_not_found": "Top-up not found.",
                "topup_not_pending": "This top-up can no longer be paid.",
                "amount_mismatch": "Paid amount does not match the top-up.",
            }.get(code, code))

    @app.post("/v1/wallet/topup/confirm", response_model=WalletOut)
    def confirm_topup(body: ConfirmIn, request: Request):
        """DEV ONLY: simulates the payment provider's success webhook for a mock checkout."""
        if not dev_payments:
            raise APIError(404, "not_found", "Not found.")
        topup = wallet.get_topup(body.topup_id)
        if topup is None:
            raise APIError(404, "topup_not_found", "Top-up not found.")
        caller = resolve_user(request)
        if request.headers.get("authorization") and caller != topup["user_id"]:
            raise APIError(403, "forbidden", "This top-up belongs to another account.")
        user_id = _apply(provider.simulate_success(topup))
        return {"balance_inr": paise_to_inr(wallet.available_paise(user_id))}

    @app.post("/v1/payments/webhook")
    async def payment_webhook(request: Request):
        """Provider -> us. The provider implementation authenticates the call (signature) first."""
        raw = await request.body()
        try:
            event = provider.parse_webhook(request.headers, raw)
        except WebhookError as e:
            log.warning("rejected payment webhook: %s", e)
            raise APIError(400, "invalid_webhook", "Webhook rejected.")
        if event.paid:
            _apply(event)
        else:
            db.x("UPDATE topups SET status='failed' WHERE id=? AND status='pending'", (event.topup_id,))
        return {"ok": True}

    @app.get("/v1/dev/checkout/{topup_id}", response_class=HTMLResponse, include_in_schema=False)
    def dev_checkout(topup_id: str):
        """DEV ONLY stand-in for the Razorpay checkout page the app opens."""
        if not dev_payments:
            raise APIError(404, "not_found", "Not found.")
        topup = wallet.get_topup(topup_id)
        if topup is None:
            raise APIError(404, "topup_not_found", "Top-up not found.")
        return HTMLResponse(DEV_CHECKOUT_HTML.format(
            amount=f"{paise_to_inr(topup['amount_paise']):.2f}", topup_id=html.escape(topup_id, quote=True),
            status=html.escape(topup["status"])))

    # ------------------------------------------------------------ models & chat
    @app.get("/v1/models", response_model=ModelsOut)
    def models():
        return {"tiers": [
            {"tier": t, "label": TIER_LABELS[t],
             "est_cost_per_message_inr": estimate_message_inr(catalog.tier(t).model, settings)}
            for t in TIERS]}

    @app.post("/v1/chat", response_model=ChatOut)
    async def post_chat(body: ChatIn, user_id: str = Depends(current_user)):
        limited(chat_limit, user_id)
        return await chat.handle(user_id, ChatRequestData(body.tier, body.message, body.conversation_id,
                                                          body.image_base64))

    @app.get("/healthz", include_in_schema=False)
    def healthz():
        return {"ok": True, "models_source": catalog.source, "dev_mode": settings.dev_mode}

    return app


DEV_CHECKOUT_HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Test payment</title>
<style>
body{{font-family:system-ui,sans-serif;max-width:420px;margin:40px auto;padding:0 16px;color:#1b1b1b;background:#fff}}
.card{{border:1px solid #ddd;border-radius:12px;padding:20px}}
.note{{background:#fff4d6;border-radius:8px;padding:10px;font-size:14px}}
button{{width:100%;padding:14px;font-size:16px;border:0;border-radius:8px;background:#1a73e8;color:#fff;margin-top:16px}}
#done{{display:none;color:#137333;font-weight:600;margin-top:16px}}
</style></head><body><div class="card">
<p class="note">This is a <b>test payment page</b> for development. No real money is charged.</p>
<h2>Add &#8377;{amount} to your wallet</h2>
<button id="pay">Simulate successful UPI payment</button>
<p id="done">Payment successful. You can go back to the app.</p>
</div><script>
const s="{status}";
function done(){{document.getElementById('pay').style.display='none';document.getElementById('done').style.display='block';}}
if(s==="paid")done();
document.getElementById('pay').onclick=async()=>{{
  const r=await fetch('/v1/wallet/topup/confirm',{{method:'POST',headers:{{'Content-Type':'application/json'}},
    body:JSON.stringify({{topup_id:"{topup_id}"}})}});
  if(r.ok)done();else alert('Payment simulation failed: '+r.status);
}};
</script></body></html>
"""
