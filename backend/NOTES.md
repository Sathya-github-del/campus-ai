# Known gaps and decisions to make (backend v0.1)

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

## 3. Anonymous tokens: a lost phone means a lost balance

Registration is anonymous, as specified. If the app is reinstalled or the phone is lost, the token and
the paid balance are gone, with no way to recover them. That's acceptable for testing, but not once
students pay real money: add phone-OTP or Google sign-in (and token rotation/expiry) before launch.
There is also no way yet to delete an account or its data.

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
3. Check that the Fast pick's answers are good enough. "Newest in band" is a price rule, not a quality
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
- **No migration tool.** The schema is `CREATE TABLE IF NOT EXISTS` on startup, which only creates
  missing tables; it never alters existing ones. The first column change after launch needs a real
  migration step (Alembic or numbered SQL files). Existing local SQLite data is not copied to
  Postgres (there's no script); that is dev data only.
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
- Conversation history endpoints (list or reopen past chats) are not built; saved chats are v1.1 in
  BLUEPRINT §8. Messages are stored server-side already because context and caching need them.
- The ≤ 1-paisa prepaid sub-paisa credit isn't included in `balance_inr` (it shows whole paise).
- Holds expire after 2 × `UPSTREAM_TIMEOUT_SECONDS` + 60 s, in case a process dies mid-call.
- Single process only: in-memory rate limits and conversation locks. See README "Scaling". (Wallet
  money moves are already safe across processes on Postgres; only these two are in-process.)
- The PPI/RBI classification of the wallet still needs the professional check in BLUEPRINT §9.1. The
  code only guarantees the closed-loop shape (no transfer or cash-out paths, per-top-up and wallet caps).
