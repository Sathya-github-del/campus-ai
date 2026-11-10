# Known gaps and decisions to make (backend v0.2, app name: QUATRIO AI)

Ordered by how much they matter before real money moves.

## 1. `MARGIN_PCT=17.65` recovers fees; it is not a markup

**Decision:** the default is `MARGIN_PCT=17.65`. Every charge is
**real cost (OpenRouter `usage.cost`, USD) × `USD_INR_RATE` / (1 − 0.1765)**.

**Why that number:** BLUEPRINT §7 shows ~17.65% of each top-up goes to fees before it becomes
spendable OpenRouter credit (Play 11% → Razorpay 2.36% → OpenRouter 5.5%: ₹20 → ₹16.47). Charging at
cost / (1 − 0.1765) means a ₹20 wallet buys exactly ₹16.47 of real model usage, so **a student who
spends the whole balance comes out even**: the fee stack is covered with no extra markup. BLUEPRINT
§12's profit model gets its margin from **breakage** (balance students don't spend), not from
per-message markup. At §12's 60% consumption assumption, COGS is 0.6 × ₹16.47 = ₹9.88, leaving
**₹6.59/user/month (~33%)**, which matches §12 exactly.

| Per ₹20 top-up, MARGIN_PCT = 17.65 | |
|---|---|
| Real credit consumed if fully spent | ₹16.47 (break-even) |
| Margin at §12's 60% consumption | ₹6.59 (33%) |
| Users to cover ₹1,000–1,650/mo fixed cost | ~152–250 (§12) |

Guard rails:
- **Don't raise it above 17.65 to force per-message margin.** That would make per-message pricing
  dishonest relative to the economics already presented. The server logs a startup warning if
  `MARGIN_PCT` is set above the fee stack (`PLATFORM_FEE_PCT`).
- Below 17.65, every spent rupee loses money (at 5%, a fully spent ₹20 wallet loses ₹2.53 and the
  60%-consumption margin drops to ₹5.07). The server warns about that too.
- If the fee stack changes (Razorpay's 90-day 0% promo ends, Play billing tier, OpenRouter fee), update
  `MARGIN_PCT` and `PLATFORM_FEE_PCT` together. They are env vars, and a restart applies them.
- The margin depends entirely on the 60%-consumption assumption, which §12 and §14 say to validate in
  month 1. The per-message `ledger` and `messages.cost_usd` rows are the data for that.

## 1a. GST (18%) is not accounted for anywhere yet. Open gap

GST on digital services (18%) is **not reflected anywhere in this project**: not in BLUEPRINT §7's
per-transaction fee waterfall, not in §12's profit figures, and not in the code (`MARGIN_PCT`, charges,
receipts). It is a tax on revenue owed to the government, separate from the Play/Razorpay/OpenRouter
fee stack. It has not been netted out of any profit figure yet, so the ₹6.59/user/month (33%) in §12
is **pre-GST**. Once GST registration applies (BLUEPRINT §5, §9.3), whether the ₹20 price is
GST-inclusive and how that changes net margin both need working out, probably with the CA consulted on
the wallet structure. Deliberately not solved in v0.1.

## 2. Razorpay is not wired up

`PaymentProvider` and the mock are done. `RazorpayPaymentProvider` in `app/payments.py` is a stub whose
docstring lists the work: Orders API, hosted checkout / UPI intent, **`X-Razorpay-Signature`
HMAC-SHA256 verification of the raw webhook body** (constant-time compare, before parsing), mapping
`order.paid` → `topup_id` via `receipt`, event-id dedupe, and a reconciliation job for pending top-ups
whose webhook never arrived. Also not built: failed/expired top-up cleanup, refunds, and an admin view
of the ledger. Google Play alternative billing (§5) needs its own user-choice flow on the client.

## 3. Accounts: email/password is built; what's still missing

**Built (v0.2):** `POST /v1/auth/signup` and `/v1/auth/login` (email + password, argon2id hashes,
generic login errors, a dummy hash check for unknown emails so response time doesn't reveal them,
per-email and per-IP login limits), `DELETE /v1/account`, and history list/delete (README API table).
Anonymous `POST /v1/auth/register` still works unchanged for quick testing. Both kinds of account use
the same token mechanism (`users.token_hash`), so the auth check didn't change.

**Gaps, most important first:**
- **Password reset doesn't send email.** `POST /v1/auth/forgot-password` is a stub: it always answers
  "If an account exists for that email, we've sent it a link…" (so it can't be used to discover which
  emails have accounts) but sends nothing and stores nothing. A student who forgets their password
  can't get back in, and their balance is stuck. Needs: an email provider (e.g. SES, Postmark,
  Resend), a single-use, short-lived reset token table (store only its hash), a reset endpoint, and a
  page or deep link. Until then, don't tell testers the reset link works.
- **No email verification.** Anyone can sign up with someone else's address, and the real owner then
  gets `409 email_taken`. Fix together with the reset email (same sending path).
- **One token per account, no expiry.** Login replaces the stored token, so signing in on a second
  phone signs the first one out (it gets `401 unauthorized`, message "You're signed out. Please sign
  in again."). Tokens never expire. A `sessions` table (several tokens per user, expiry, "sign out
  everywhere") is the next step; it means changing `resolve_user` in `api.py`.
- **No way to upgrade an anonymous account.** A tester with an anonymous token and a balance who then
  signs up gets a *new, empty* account. If anonymous accounts stay in the app, add "attach email to
  this account" (signup called with the anonymous Bearer token).
- **Account deletion doesn't ask for the password again.** Anyone holding the token (e.g. an unlocked
  phone) can delete the account. Consider requiring the password for email accounts.
- **Account deletion also deletes the ledger and top-up rows**, as requested ("everything tied to
  them"). That includes the record of real payments received. Indian tax and accounting rules
  generally require keeping transaction records for years, so before real money moves, check with the
  CA (BLUEPRINT §9) whether these rows should be kept with the user link removed (`user_id` is already
  a random id with no personal data once the `users` row is gone) instead of deleted.
- **A payment that completes after deletion is lost.** If the student deletes the account while a
  top-up is pending and then pays, the webhook finds no top-up (`404 topup_not_found`) and nothing is
  credited. Harmless with mock payments; the Razorpay work (§2) must refund such payments.
- **An answer in flight during account deletion errors.** The chat request then fails (500) when it
  tries to save and charge; nothing is written and the student isn't charged, but the platform pays
  OpenRouter for that answer. Rare; acceptable for now.
- Not built: changing the email or password, Google / phone-OTP sign-in.

**Suggested confirmation copy for "Delete account" in the app** (balance is forfeited; there is no
payout mechanism and there must not be one, BLUEPRINT §5, §9):

> Delete your QUATRIO AI account? This permanently deletes your account, all your conversations
> and your wallet. Your remaining balance of ₹{balance} will be lost. It can't be refunded or moved to
> another account. This can't be undone.

## 4. Children's data (school students)

Conversations and homework photos are stored server-side indefinitely (needed for multi-turn context
and caching). India's DPDP Act 2023 requires verifiable parental consent for processing data of under-18s
and limits tracking of children. Like the RBI question in BLUEPRINT §9, this needs a professional check
before school students are targeted. A retention policy (e.g. delete conversations after N days) is
easy to add. Deciding what it should be is the open part.

## 5. Not yet exercised against live OpenRouter chat

No API key was available. The model-list fetch and tier selection were run against the live
`/api/v1/models` endpoint (picks on 2026-11-10: Fast `deepseek/deepseek-v4.1-flash-1017`, Smart
`openai/gpt-6.1-terra-pro`, Expert `anthropic/claude-fable-5.2`). `POST /chat/completions` has only
run against the test fake. Request shape follows OpenRouter's docs (cache_control blocks, `session_id`,
`reasoning.effort`, `usage.cost`). First checks once a key exists:

1. A 3+ turn Expert conversation with a long first message (>4k tokens) should show
   `messages.cached_tokens > 0` from turn 2 onwards. A short conversation never caches (provider
   minimums).
2. Compare `messages.cost_usd` with the OpenRouter activity page for the same generations.
3. Since v0.2 the local `.env` pins only `MODEL_FAST` (to a free model); Smart and Expert are chosen by
   live price (on 2026-11-10 that gave Smart `openai/gpt-6.1-terra-pro`, ≈ ₹0.26 per typical message,
   and Expert `anthropic/claude-fable-5.2`, ≈ ₹2.08). Photo turns on Fast go to the best paid vision
   model in the Fast band, because the free pin is text-only.
4. Check that the Fast pick's answers are good enough. "Newest in band" is a price rule, not a quality
   rule, and today it picks a dated snapshot. Use `MODEL_FAST` etc. to pin models that pass a small
   evaluation set of real student questions (Hindi and Hinglish included).

## 5a. Postgres (Neon) is the production database; what is and isn't verified

**Done:** `DATABASE_URL` switches the backend from SQLite to Postgres (psycopg 3, small connection pool).
SQLite stays the default for local dev and the default test run. Same schema in both dialects (same
CHECKs, including `balance_paise >= 0` and `0 <= credit_units < 10000`, same FKs and indexes). Every
money transaction locks the user's wallet row first (`SELECT … FOR UPDATE`, READ COMMITTED pinned per
connection); `apply_payment` locks the top-up row. Reasoning is in `PostgresDB.tx()` (`app/db.py`).
Render runs on the native Python runtime from `../render.yaml`; README "Deploy to Render" has the
env-var checklist.

**Verified:** Docker was available, so `pytest -m postgres` runs the money-path API tests and the
concurrency tests against a real `postgres:16-alpine` container (balance deduction, 402, top-up +
confirm, duplicate webhook, wallet cap, 16-thread races, constraint enforcement). Removing `FOR UPDATE`
makes 6 of them fail (overcommitted holds, lost settle updates, overspending), so they really catch
races. A manual uvicorn run against Postgres through the env-var path (register → top-up → double
confirm → chat) also worked.

**Not verified / still open:**
- **Not run against Neon itself** (no Neon account here). First deploy checks: the service starts and
  creates tables; `/healthz` responds; a top-up + confirm + chat round trip; a request after Neon's
  idle suspend still works (the pool checks connections before use, which should cover it).
- **No real migration tool.** The schema is `CREATE TABLE IF NOT EXISTS` on startup, plus a small
  list of additive, idempotent upgrades (`MIGRATION_COLUMNS` / `MIGRATION_INDEXES` in `db.py`) that
  run on every start on both backends. v0.2 uses it to add `users.email`, `users.password_hash` and a
  unique index on email to existing databases (tested on old-schema SQLite and Postgres databases).
  This only handles adding nullable columns and indexes; renames, type changes or backfills need a
  real tool (Alembic or numbered SQL files). Existing local SQLite data is not copied to Postgres
  (there's no script); that is dev data only.
- **Blocking DB calls on the event loop.** `POST /v1/chat` is async and calls the (sync) DB directly. On
  local SQLite that cost nothing; on Neon each call is a network round trip (a few ms within Singapore)
  during which the event loop is blocked. That's fine at test scale. Before real load, run the DB
  work in a thread (`run_in_threadpool`) or switch to psycopg's async API.
- **Backups:** depend on Neon's plan (the free tier keeps only a short point-in-time restore window).
  Decide on a backup/export routine before real money is held.
- **Registration rate limit vs Render's proxy.** The start command trusts `X-Forwarded-For` from any
  peer (`--forwarded-allow-ips '*'`); otherwise every student would share the proxy's IP and the
  20/hour registration limit would apply to everyone at once. The catch is that a client can spoof
  that header to get past the per-IP limit. That only costs empty wallets, but it should be revisited
  with the account/abuse work in §3.

## 6. Smaller known gaps

- `USD_INR_RATE` is read at startup. Edit `.env` and restart to change it (a live rate feed is not
  wired).
- Token estimation is a heuristic (UTF-8 bytes/3, 1,600 tokens per image). If it underestimates, the
  platform absorbs the difference (recorded in `ledger.shortfall_units`) and the student is never
  overbilled. Watch that column.
- The worst-case hold assumes reasoning tokens count against `max_tokens` (true for the Anthropic and
  OpenAI APIs). A model that ignores this could exceed the hold; that excess is absorbed as above.
- OpenRouter's long-context price `overrides` (e.g. above 272k prompt tokens) are ignored. Safe while
  `MAX_INPUT_TOKENS` (32k) is far below them.
- A model that returns an empty reply is still charged its real (small) cost.
- History: list and delete are built (`GET /v1/history`, `DELETE /v1/history/{id}`). Not built:
  reopening a past conversation's messages (`GET /v1/history/{id}`), paging beyond `limit` (max 200),
  search. Continuing an old conversation already works by sending its `conversation_id` to `/v1/chat`.
- The `APP_NAME` default (sent to OpenRouter as `X-Title` for attribution) is still `campus-ai`. Set
  `APP_NAME=QUATRIO AI` if the name should show on OpenRouter.
- The ≤ 1-paisa prepaid sub-paisa credit isn't included in `balance_inr` (it shows whole paise).
- Holds expire after 2 × `UPSTREAM_TIMEOUT_SECONDS` + 60 s, in case a process dies mid-call.
- Single process only: in-memory rate limits and conversation locks. See README "Scaling". (Wallet
  money moves are already safe across processes on Postgres; only these two are in-process.)
- The PPI/RBI classification of the wallet still needs the professional check in BLUEPRINT §9.1. The
  code only guarantees the closed-loop shape (no transfer or cash-out paths, per-top-up and wallet caps).
