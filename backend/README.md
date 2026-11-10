# campus-ai backend

Backend for **QUATRIO AI**, a pay-as-you-go AI study-chat Android app for Indian students
(`campus-ai` stays the internal repo/package name).
Students top up a closed-loop rupee wallet (UPI, from ₹20) and pay per message on a Fast / Smart /
Expert tier. Models are reached through OpenRouter. Product research and economics: `../BLUEPRINT.md`.
Known gaps and launch blockers: [`NOTES.md`](NOTES.md).

Python 3.11+ · FastAPI · Postgres (production, via `DATABASE_URL`) or SQLite (local dev default) · httpx

## Run it

```bash
cd backend
cp .env.example .env          # set OPENROUTER_API_KEY; defaults are dev-mode with mock payments
./run.sh                      # creates .venv on first run, serves http://127.0.0.1:8000
./run.sh --reload             # auto-reload while developing
HOST=0.0.0.0 ./run.sh         # reachable from a phone on the same Wi-Fi
```

For the Android emulator set `PUBLIC_BASE_URL=http://10.0.2.2:8000` in `.env` so `checkout_url` opens
from inside the emulator. Health check: `GET /healthz`.

### Database: SQLite locally, Postgres in production

- **`DATABASE_URL` unset (local default):** a SQLite file at `DATABASE_PATH` (`data/campus_ai.db`).
  Nothing to install or run.
- **`DATABASE_URL` set:** Postgres, e.g. Neon. Format
  `postgresql://USER:PASSWORD@HOST/DBNAME?sslmode=require` (Neon needs `sslmode=require`). Tables are
  created on startup, and the few additive upgrades in `db.py` (`MIGRATION_COLUMNS`) are applied to an
  existing database on startup too (v0.2 adds `users.email` and `users.password_hash`). This is the production path, and the server
  refuses to start on Render without it.

The same code runs on both (`app/db.py`). Money-moving transactions lock the user's wallet row
(`SELECT … FOR UPDATE` on Postgres; SQLite's `BEGIN IMMEDIATE` already locks the whole database), so
parallel requests can't double-spend or lose an update on either backend. `PostgresDB.tx()` in
`app/db.py` explains the choice. `run.sh` only installs requirements when it creates `.venv`, so
after pulling a version that adds a dependency run `.venv/bin/pip install -r requirements.txt` once
(v0.2 added `argon2-cffi` for passwords; Render installs it on its next build).

### Tests

```bash
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q                # default: SQLite only, a few seconds, no setup
.venv/bin/python -m pytest -q -m postgres    # the same money-path tests on a real Postgres (needs Docker)
.venv/bin/python -m pytest -q -m ""          # both
```

The tests use a fake OpenRouter (no network, no key needed) and a real SQLite file per test.

`-m postgres` starts a throwaway `postgres:16-alpine` container (`TEST_PG_IMAGE` to change it), gives
every test a fresh database, and removes the container afterwards. To use an existing server instead,
set `TEST_DATABASE_URL` to a **disposable** Postgres superuser URL (the tests create and drop
databases on it; never point it at Neon production). It covers:

- `tests/test_postgres.py`: the wallet / top-up / 402 / chat-charging tests from `test_api.py` and the
  signup / login / history / account-deletion tests from `test_accounts.py`, re-run unchanged against
  Postgres, plus schema, migration and isolation checks.
- `tests/test_concurrency.py` (runs on both backends): lock tests prove each money operation waits for
  the wallet or top-up row lock; race tests start 16 threads at once and check exact end states (no
  overcommitted holds, no lost settle updates, a duplicate webhook credits once, the wallet cap holds),
  plus the CHECK / foreign-key constraints. With `FOR UPDATE` removed, 6 of these fail on Postgres, so
  they really detect the races.

## Deploy to Render (with Neon Postgres)

Render's free web services have no persistent disk, so the database lives in Neon (serverless
Postgres, free tier). `render.yaml` in the repository root is a Render Blueprint for the service.

**Why Render's native Python runtime and not a Dockerfile:** this is a pure-Python app with no system
packages (`psycopg[binary]` bundles libpq), so Render's Python runtime is the simplest, idiomatic
fit: `pip install -r requirements.txt` to build, `uvicorn` to start, Render manages the OS and Python
patches, and there's no image to maintain. A Dockerfile is only worth adding if the app later needs
system libraries or has to run the same image on another host.

**Steps**

1. **Neon:** create a project in region *AWS Asia Pacific (Singapore)*, the closest to the Render
   region in `render.yaml`. Copy the connection string from *Connect* (pooled or direct both work; it
   already ends in `?sslmode=require`).
2. **Render:** *New → Blueprint*, pick this repository. Render reads `render.yaml` and asks for each
   variable below. (Or *New → Web Service* by hand: root directory `backend`, runtime Python, build
   `pip install -r requirements.txt`, start command as in `render.yaml`, health check `/healthz`.)
3. Deploy. The first start creates the tables in Neon. Check `https://<service>.onrender.com/healthz`.

**Environment variables to enter in Render (checklist)**

| Variable | What to paste | Notes |
|---|---|---|
| `DATABASE_URL` | Neon connection string, e.g. `postgresql://neondb_owner:…@ep-…-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require` | **Required.** Secret. Keep `sslmode=require`. |
| `OPENROUTER_API_KEY` | your OpenRouter key | **Required.** Secret. Consider a credit limit on the key (see warning below). |
| `PUBLIC_BASE_URL` | `https://<service-name>.onrender.com` (no trailing slash) | Used to build `checkout_url`. |
| `DEV_MODE` | `true` while testing; `false` for real payments | See warning below. |
| `PAYMENT_PROVIDER` | `mock` while testing; `razorpay` once implemented | `mock` is refused unless `DEV_MODE=true`. |
| `USD_INR_RATE` | `93` (or today's rate) | Read at startup; redeploy/restart to change. |
| `MARGIN_PCT` | `17.65` | Fee recovery, not markup. NOTES.md §1. |
| `PLATFORM_FEE_PCT` | `17.65` | Fee stack; only used for a startup warning. |
| `MAX_OUTPUT_TOKENS` | `5000` | Cost-check before using paid models (see `.env.example`). |
| `PYTHON_VERSION` | already set to `3.13.5` by `render.yaml` | Add it yourself if you create the service by hand. |

Optional, same meaning as in `.env.example`: `MODEL_FAST`, `MODEL_SMART`, `MODEL_EXPERT` (pins),
`DB_POOL_SIZE` (default 10; Neon's free tier allows far more), `APP_URL`, `APP_NAME`, the rate limits
(including `LOGIN_LIMIT_PER_15MIN_PER_EMAIL` / `_PER_IP`) and the tier bands. Don't set `DATABASE_PATH`
or `RENDER`.

Current recommendation for the pins: set **only** `MODEL_FAST` (a free model is fine there) and leave
`MODEL_SMART` / `MODEL_EXPERT` unset, so those tiers are chosen by live price inside their bands. If
all three are pinned to free models, every tier's estimate in `GET /v1/models` reads ₹0.00, which
looks broken to testers.

> **Warning: a `DEV_MODE=true` + `PAYMENT_PROVIDER=mock` deployment is public free credit.** Anyone who
> finds the URL can register, "pay" on the mock checkout page and spend *your* OpenRouter balance.
> Razorpay is not implemented yet (NOTES.md §2), so this is the only mode that works today. Use it
> only for closed testing. With Smart and Expert on paid models, **put a credit limit on the
> OpenRouter key**: free mock credit spent on Expert costs you real money.

Free-tier behaviour to expect: the Render service sleeps after ~15 minutes without traffic and the
next request takes up to about a minute to wake it (the app's timeouts should allow for that), and
Neon suspends its compute when idle and resumes on the next query. The connection pool checks each
connection before use, so stale connections after a suspend are replaced automatically.

## API (the contract the Android client is built against)

| Method & path | Auth | Request | Response |
|---|---|---|---|
| `POST /v1/auth/register` | – | `{}` (or no body) | `{"token"}` (anonymous account; kept for quick testing) |
| `POST /v1/auth/signup` | – | `{"email", "password"}` (password 8–256 chars) | `{"token"}` |
| `POST /v1/auth/login` | – | `{"email", "password"}` | `{"token"}` (signs out the account's previous token) |
| `POST /v1/auth/forgot-password` | – | `{"email"}` | `{"message"}`, always the same text (**stub: no email is sent yet**) |
| `GET /v1/history?limit=50` | Bearer | `limit` 1–200, default 50 | `{"conversations": [{"conversation_id", "first_question", "created_at", "updated_at", "questions", "cost_inr"}]}` |
| `DELETE /v1/history/{conversation_id}` | Bearer | – | `204`, empty body |
| `DELETE /v1/account` | Bearer | – | `204`, empty body. **Remaining balance is forfeited** (see below) |
| `GET /v1/wallet` | Bearer | – | `{"balance_inr"}` |
| `POST /v1/wallet/topup` | Bearer | `{"amount_inr"}` (≥ 20, ≤ 2000, max 2 decimals) | `{"checkout_url", "topup_id"}` |
| `POST /v1/wallet/topup/confirm` | optional | `{"topup_id"}` | `{"balance_inr"}` (**dev mode only**, 404 otherwise) |
| `GET /v1/models` | – | – | `{"tiers": [{"tier", "label", "est_cost_per_message_inr"}]}` |
| `POST /v1/chat` | Bearer | `{"tier", "message", "conversation_id", "image_base64"}` | `{"reply", "conversation_id", "cost_inr", "wallet_balance_inr", "truncated"}` |

`truncated` (bool) is `true` only when OpenRouter reports the reply stopped at the output-token limit
(`finish_reason == "length"`, or the provider's raw `native_finish_reason` is a max-tokens value when
the normalised one is missing). The reply is then cut off mid-answer, so the app should say so
(e.g. "answer was cut short, ask to continue"). It is `false` for a normal finish and whenever the
signal is missing or ambiguous. The student is still charged for the tokens that were generated.

**Accounts.** Email and anonymous accounts use the same bearer tokens (random, only the SHA-256 is
stored). Emails are trimmed and lower-cased; signup with an existing email is `409 email_taken`.
Login failures are always `401 invalid_credentials` / "Incorrect email or password." whether or not the
email exists. An account has one token at a time: logging in on a new device signs the old one out
(its next request gets `401 unauthorized`). Passwords are hashed with argon2id.

**History.** Conversations are listed most recently active first (`updated_at` descending).
`first_question` is the conversation's first question, cut to 140 characters with a trailing `…`.
`questions` counts the student's questions (1 = no follow-ups). `cost_inr` is the sum of what each
answer was charged (the same figures `POST /v1/chat` returned as `cost_inr`). Timestamps are ISO 8601
UTC, e.g. `2026-11-10T14:03:22Z`. There's no paging beyond `limit` yet. Deleting a conversation that
doesn't exist **or belongs to someone else** returns `404 conversation_not_found` (same answer, so it
doesn't reveal other users' conversation ids). Deleting history doesn't change the wallet. If an answer
is still being generated in that conversation, the delete waits for it to finish and be charged.

**Account deletion** removes the user, wallet, conversations, messages, top-ups and ledger rows. Any
balance left in the wallet is **forfeited, not refunded**: credits are closed-loop and non-cashable
(BLUEPRINT §5, §9), so there is no payout path, and none should be added. The app must say this before
the student confirms (suggested copy in NOTES.md §3).

Response bodies contain exactly these fields and nothing more (Kotlin serializers reject unknown keys
by default). Server-only extras:

- `POST /v1/payments/webhook`: where the payment provider reports a paid top-up.
- `GET /v1/dev/checkout/{topup_id}` (dev only): the mock checkout page `checkout_url` points to. It
  has a "Simulate successful UPI payment" button that calls `/v1/wallet/topup/confirm`.

**Errors** always look like `{"error": "<code>", "message": "<text you can show the student>", ...}`:

| Status | `error` | When |
|---|---|---|
| 400 | `invalid_amount`, `wallet_limit`, `invalid_image`, `empty_message`, `content_rejected`, `image_not_supported`, `invalid_email`, `weak_password` | bad input |
| 401 | `unauthorized` | missing/unknown token (also after logging in elsewhere or deleting the account) |
| 401 | `invalid_credentials` | login: wrong email or password (never says which) |
| 402 | `insufficient_balance` | wallet can't cover one message on that tier; extra fields `balance_inr`, `required_inr`, `tier` |
| 404 | `conversation_not_found`, `topup_not_found`, `not_found` | `conversation_not_found` also for another user's conversation |
| 409 | `email_taken` | signup with an email that already has an account |
| 409 | `amount_mismatch`, `topup_not_pending` | webhook/confirm problems |
| 413 | `message_too_long`, `image_too_large`, `conversation_too_long` | start a new conversation for the last one |
| 422 | `invalid_request` | malformed JSON / wrong types / unknown tier |
| 429 | `rate_limited` | `Retry-After` header set |
| 502 / 503 / 504 | `upstream_error`, `service_unavailable`, `model_busy`, `model_timeout` | model side failed; **the student is not charged** |

`image_base64` accepts bare base64 or a `data:image/...;base64,` URL (JPEG/PNG/WebP/GIF, ≤ 5 MB).
A photo with an empty `message` is sent with "Please help me with the problem in this photo."

## How it works

### Tiers: chosen from OpenRouter's live price list

No model ID is hardcoded for normal operation. At startup and every 6 h (`MODELS_REFRESH_SECONDS`) the
server fetches `GET https://openrouter.ai/api/v1/models` and:

1. drops models that don't suit an interactive chat at a known price: free (`:free`), batch and other
   `:variant`s, `~latest` aliases, `openrouter/*` routers with variable pricing, image/audio-output
   models, models with an announced expiration date, context < 16k, and authors outside
   `TRUSTED_PROVIDERS`;
2. buckets the rest by **blended price** (3 parts input : 1 part output, per 1M tokens):
   Fast ≤ $0.75, Smart ≤ $4, Expert ≤ $25 (`FAST_MAX_USD_PER_M`, …);
3. inside each band prefers: a cache-read discount → image input → controllable reasoning → newest.
   If the pick is text-only, the best image-capable model in the same band handles photo turns.

If the refresh fails the previous choice stays; the last good list is saved in the database so a restart
without network still uses it. Only a cold start with no network ever uses the dated snapshot in
`app/catalog.py:FALLBACK_MODELS`. Pin a tier with `MODEL_FAST` / `MODEL_SMART` / `MODEL_EXPERT` once
you have quality-tested a model (pins are still priced from the live list).

`est_cost_per_message_inr` is a standalone question (300 input + 400 output tokens, no cache) at the
user-visible price. Long conversations cost more per message; label it "about" in the app.

### Prompt caching (required, BLUEPRINT §7)

Every turn resends the whole conversation, so requests are built to keep a byte-identical, growing
prefix that providers bill at ~10–25% of the input price on a cache hit (details in `app/chat.py`):

- one fixed system prompt; history replayed verbatim from the database in an identical shape each turn;
  nothing is ever trimmed or summarised (an over-long conversation gets 413, not truncation, because
  truncation would shift the prefix and miss the cache on every later turn);
- Anthropic models get explicit `cache_control` breakpoints on the system block and the newest user
  turn (`ANTHROPIC_CACHE_TTL`, 5m default or 1h); OpenAI, Gemini 2.5+, DeepSeek, Grok, etc. cache
  automatically;
- `session_id = conversation_id` on every call, so OpenRouter keeps a conversation on the same
  upstream provider (a cache only exists where it was written);
- tier selection prefers models that publish a cache-read price;
- `cached_tokens` / `cache_write_tokens` are stored per assistant message for measuring hit rate.

Caching only starts once the prefix passes the provider minimum (512–4096 tokens), so very short
chats show no cache hits. That is expected.

### Money

- Wallet balances are **integer paise**. There is no float anywhere in the ledger.
- Metering uses integer *units* of 1/10,000 paisa. A Fast message costs ~2 paise, so whole-paisa
  rounding would distort it by up to ±25%. Each charge takes whole paise (rounded up) and keeps the
  overpayment as a sub-paisa prepaid credit for the next message: total charged equals total metered
  cost to within one paisa, and the balance never goes negative (also enforced by a DB `CHECK`).
- **Charge = real cost (USD, from OpenRouter's `usage.cost`) × `USD_INR_RATE` / (1 − `MARGIN_PCT`/100).**
  The default `MARGIN_PCT=17.65` equals the payment/platform fee stack in BLUEPRINT §7, so it recovers
  fees rather than adding a markup. A student who spends their whole wallet breaks even, and profit
  comes from unspent balance (§12). See [NOTES.md §1](NOTES.md#1-margin_pct1765-recovers-fees-it-is-not-a-markup).
- **402 before calling the model.** The server estimates input tokens conservatively (UTF-8 bytes/3,
  plus 1,600 per image) and prices the worst case: input at the higher of input or cache-write price,
  plus `MAX_OUTPUT_TOKENS` (5000) of output. If the wallet can't cover that, `max_tokens` is lowered to
  what it can cover. If even `MIN_OUTPUT_TOKENS` (256) is out of reach, the request gets 402 and the
  model is never called.
- That worst case is **reserved** (a hold) atomically before the call, so parallel requests can't
  overspend. After the call the real cost is charged and the hold released. A student is never billed
  above the hold; if the estimate was ever too low, the platform absorbs the gap and records it in
  `ledger.shortfall_units`. Failed model calls are not charged.
- Credits are closed-loop: no transfer, withdrawal or cash-out path exists in the code (RBI PPI
  structure, BLUEPRINT §5). Top-ups are capped at ₹2,000 each and the wallet at ₹10,000.

### Payments

`app/payments.py` defines `PaymentProvider` (`create_checkout`, `parse_webhook`). Two implementations:

- `MockPaymentProvider` (dev): `checkout_url` is the local test page; `/v1/wallet/topup/confirm`
  simulates the success webhook through the same idempotent `wallet.apply_payment` path a real webhook
  uses (a second confirm doesn't credit twice). The server refuses to start with
  `PAYMENT_PROVIDER=mock` unless `DEV_MODE=true`.
- `RazorpayPaymentProvider`: a stub with the integration spelled out in its docstring (Orders API,
  `X-Razorpay-Signature` HMAC check, `order.paid` handling, reconciliation). Implement it, set
  `PAYMENT_PROVIDER=razorpay` and `DEV_MODE=false`; nothing else changes.

Dev flow from the app: `POST /v1/wallet/topup` → open `checkout_url` → tap the button (or the app
calls `POST /v1/wallet/topup/confirm`) → `GET /v1/wallet`.

### Abuse limits and content policy

- Per token, in memory, sliding window: 60 requests/min overall, 12 chats/min
  (`RATE_LIMIT_PER_MINUTE`, `CHAT_RATE_LIMIT_PER_MINUTE`). Registrations and signups: 20/hour per IP
  (shared). Forgot-password: 20/hour per IP. Logins: 10 per 15 min per email and 50 per 15 min per IP,
  counting successful ones. Behind a
  reverse proxy, uvicorn's `--proxy-headers` (set in `run.sh`) supplies the real client IP when the
  proxy is listed in `FORWARDED_ALLOW_IPS`.
- Content policy: OpenRouter and the model providers enforce their own usage policies. A blocked
  request returns 400 `content_rejected` and is not charged. `app/chat.py:screen_input()` is the hook
  for the app's own checks (age-appropriate filtering for school students, exam-integrity rules). It
  runs before any money is reserved and currently allows everything.

## Layout

```
app/
  api.py         routes, contract models, auth, rate limits, app factory
  accounts.py    user creation, password hashing, history queries, account deletion
  chat.py        request building (prompt caching), pre-authorisation, metering, persistence
  catalog.py     OpenRouter model list -> Fast/Smart/Expert
  wallet.py      balance, holds, charges, idempotent top-up credit
  money.py       paise / unit math (Decimal + int only)
  payments.py    PaymentProvider interface, mock, Razorpay stub
  openrouter.py  async HTTP client
  db.py          SQLite + Postgres backends (schema in both dialects, additive migrations, locking notes)
  config.py      env-var settings and validation
  errors.py      single error shape
  ratelimit.py   sliding-window limiter
tests/           pytest (unit + end-to-end through the HTTP API; -m postgres for real Postgres)
```

`../render.yaml` is the Render Blueprint (see "Deploy to Render").

## Scaling

v1 is deliberately simple and runs as **one process**:

- **Postgres is supported** (`DATABASE_URL`) and is the production database. Money moves lock the
  wallet row (`SELECT … FOR UPDATE`), so they are already safe across several workers or instances.
- Rate limits and the per-conversation lock are in-process. Move them to Redis before running more than
  one worker or instance.
- Photos are stored as base64 in the database (they must be resent each turn for the cache prefix). Move them
  to object storage with a retention policy.
