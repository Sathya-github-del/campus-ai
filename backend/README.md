# campus-ai backend

Backend for a pay-as-you-go AI study-chat Android app for Indian students (working name; not a brand).
Students top up a closed-loop rupee wallet (UPI, from ₹20) and pay per message on a Fast / Smart /
Expert tier. Models are reached through OpenRouter. Product research and economics: `../BLUEPRINT.md`.
Known gaps and launch blockers: [`NOTES.md`](NOTES.md).

Python 3.11+ · FastAPI · SQLite · httpx

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

### Tests

```bash
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

The tests use a fake OpenRouter (no network, no key needed) and a real SQLite file per test.

## API (the contract the Android client is built against)

| Method & path | Auth | Request | Response |
|---|---|---|---|
| `POST /v1/auth/register` | – | `{}` (or no body) | `{"token"}` |
| `GET /v1/wallet` | Bearer | – | `{"balance_inr"}` |
| `POST /v1/wallet/topup` | Bearer | `{"amount_inr"}` (≥ 20, ≤ 2000, max 2 decimals) | `{"checkout_url", "topup_id"}` |
| `POST /v1/wallet/topup/confirm` | optional | `{"topup_id"}` | `{"balance_inr"}` (**dev mode only**, 404 otherwise) |
| `GET /v1/models` | – | – | `{"tiers": [{"tier", "label", "est_cost_per_message_inr"}]}` |
| `POST /v1/chat` | Bearer | `{"tier", "message", "conversation_id", "image_base64"}` | `{"reply", "conversation_id", "cost_inr", "wallet_balance_inr"}` |

Response bodies contain exactly these fields and nothing more (Kotlin serializers reject unknown keys
by default). Server-only extras:

- `POST /v1/payments/webhook`: where the payment provider reports a paid top-up.
- `GET /v1/dev/checkout/{topup_id}` (dev only): the mock checkout page `checkout_url` points to. It
  has a "Simulate successful UPI payment" button that calls `/v1/wallet/topup/confirm`.

**Errors** always look like `{"error": "<code>", "message": "<text you can show the student>", ...}`:

| Status | `error` | When |
|---|---|---|
| 400 | `invalid_amount`, `wallet_limit`, `invalid_image`, `empty_message`, `content_rejected`, `image_not_supported` | bad input |
| 401 | `unauthorized` | missing/unknown token |
| 402 | `insufficient_balance` | wallet can't cover one message on that tier; extra fields `balance_inr`, `required_inr`, `tier` |
| 404 | `conversation_not_found`, `topup_not_found`, `not_found` | |
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

If the refresh fails the previous choice stays; the last good list is saved in SQLite so a restart
without network still uses it. Only a cold start with no network ever uses the dated snapshot in
`app/catalog.py:FALLBACK_MODELS`. Pin a tier with `MODEL_FAST` / `MODEL_SMART` / `MODEL_EXPERT` once
you have quality-tested a model (pins are still priced from the live list).

`est_cost_per_message_inr` is a standalone question (300 input + 400 output tokens, no cache) at the
user-visible price. Long conversations cost more per message; label it "about" in the app.

### Prompt caching (required, BLUEPRINT §7)

Every turn resends the whole conversation, so requests are built to keep a byte-identical, growing
prefix that providers bill at ~10–25% of the input price on a cache hit (details in `app/chat.py`):

- one fixed system prompt; history replayed verbatim from SQLite in an identical shape each turn;
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
  plus `MAX_OUTPUT_TOKENS` (1500) of output. If the wallet can't cover that, `max_tokens` is lowered to
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
  (`RATE_LIMIT_PER_MINUTE`, `CHAT_RATE_LIMIT_PER_MINUTE`). Registrations: 20/hour per IP. Behind a
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
  chat.py        request building (prompt caching), pre-authorisation, metering, persistence
  catalog.py     OpenRouter model list -> Fast/Smart/Expert
  wallet.py      balance, holds, charges, idempotent top-up credit
  money.py       paise / unit math (Decimal + int only)
  payments.py    PaymentProvider interface, mock, Razorpay stub
  openrouter.py  async HTTP client
  db.py          SQLite schema + helpers
  config.py      env-var settings and validation
  errors.py      single error shape
  ratelimit.py   sliding-window limiter
tests/           pytest (unit + end-to-end through the HTTP API)
```

## Scaling

v1 is deliberately simple and runs as **one process**:

- **SQLite → Postgres before real scale.** Money moves already run in single transactions
  (`BEGIN IMMEDIATE`) and holds live in the DB, so the port is mechanical: `SELECT … FOR UPDATE` on
  the wallet row.
- Rate limits and the per-conversation lock are in-process. Move them to Redis before running more than
  one worker or instance.
- Photos are stored as base64 in SQLite (they must be resent each turn for the cache prefix). Move them
  to object storage with a retention policy.
