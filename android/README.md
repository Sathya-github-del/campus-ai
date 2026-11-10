# Straight Answer — Android client

> **Straight Answer**, applicationId and Kotlin package `dev.straightanswer.app` (renamed from the
> `dev.studyapp.app` placeholder: namespace, applicationId, every package/import, `app_name`, the
> `Application` class `StraightAnswerApp`, theme `Theme.StraightAnswer`, `rootProject.name`, logcat tag
> `StraightAnswer`). The in-app UI still deliberately shows no product name; the launcher label does.
> A changed applicationId is a **new app** to Android: an installed `dev.studyapp.app` build is not
> upgraded in place, and its guest token (and wallet) stays with the old app.

Pay-per-answer AI for anyone with a quick question: students, professionals checking something,
people drafting a message, debugging code, or just curious. Top up from ₹20 with UPI and pay for each
answer instead of a monthly plan. Product spec and economics: [`../BLUEPRINT.md`](../BLUEPRINT.md).

Kotlin + Jetpack Compose + Material 3, AGP 9 with built-in Kotlin (no `kotlin-android` plugin),
kotlinx.serialization, plain `HttpURLConnection`, DataStore, CameraX + ML Kit text recognition.
Same build conventions as TileOps.

## Design direction

**Positioning.** This is a quick-answer tool for anyone, not a homework app: students, but also someone
checking a contract clause, drafting a reply, debugging code or just curious. So the UI never mentions
subjects or studying. The home headline is "Got a question?", and the old Math / Physics / Essay chips are
replaced by example questions whose range (a physics problem, an email reply, a Python bug, a Jaipur trip,
FD vs RD) makes the point without labels.

**The motif: a fare meter, not a wallet.** Every AI competitor hides cost behind a flat subscription. This
app's identity is the opposite: you pay exactly for what you asked. So the hero element is a meter, the
abstract idea of a taxi/auto meter (a trusted "pay exactly what you used" object), not literal vehicle
imagery. It's a seven-segment readout drawn on a Canvas (`ui/Meter.kt`, no font files): unlit segments stay
faintly visible as ghost "8"s, lit ones glow amber, digits are slightly slanted. It appears wherever money
does, and it moves:

* **Tier cards** show each tier's live rate on a small meter, lit only on the selected card.
* **The Ask button** carries an inset "about ₹0.02" meter; switching tiers makes it tick to the new rate.
* **While an answer is on its way**, a segment chases around the digits ("meter running"), because
  `/v1/chat` doesn't stream and a fake count would be dishonest.
* **When the answer lands**, its fare ticks up from ₹0.00 to the exact charge and flashes, like a fare
  landing. At the same moment the balance meter in the top bar ticks *down* by the same amount.
* **Add fare (wallet)** is a big balance meter with ghost leading digits that counts up when a top-up lands,
  plus chunky fare keys (₹20 / ₹50 / ₹100 / ₹200, each with "≈ N asks") and a "balance after" readout.

**One question, one answer card.** Most use is a one-off question, so the default is not an endless chat
log. Asking opens a self-contained card laid out like a receipt: the question in serif, a perforated tear
line, the answer, and the fare strip with the exact charge and balance after. Below it: **Follow up**
(explicit, turns the card into a short thread with per-part fares, a running total and an honest note that
follow-ups cost a bit more because earlier parts are re-sent) or **New question**. Earlier answers from the
session stay on home as compact receipts with their fares.

**Camera as a first-class action.** "Snap it" is a tile as tall as the question box, right beside it, not
an icon in the corner. The camera screen gets amber viewfinder brackets, a tangerine shutter and the
"never uploaded" promise in its header. The CameraX + ML Kit flow underneath is unchanged.

**Colour and type ("night meter").** Always dark, but warm: espresso-black ink (`#130F0C`) instead of
Material's blue-grey, paper-cream text, and lit-segment amber/tangerine as the single accent. Tiers sit on
a heat scale: marigold (Fast) → tangerine (Smart) → vermilion (Expert), so pricier answers literally run
hotter. Headlines use the system serif (Noto Serif) for a calm, editorial voice that sets it apart from
the all-sans AI apps; labels are small tracked caps, like legends printed on a meter's bezel. Deliberately
avoided: Material purple, finance green/gold, primary-colour kid palettes, ChatGPT-style teal. Still
Material 3 underneath (`ui/Theme.kt` overrides the full colour scheme, typography and shapes), so stock
components (dialogs, menus, text fields) inherit the identity. Dark only is a deliberate call: the meter
reads best lit on a dark panel, and one tuned scheme beats two half-tuned ones. The system bars are forced
to light icons to match.

**Answers read like a textbook page, not source code.** Models answer in Markdown and LaTeX
(`**bold**`, `- lists`, `\frac{a}{b}`, `\[ … \]`), so the answer card renders both instead of showing the
raw syntax (see [Answer rendering](#answer-rendering-markdown-and-maths)). It stays in the night-meter
voice: paper-cream body text, serif headings like the rest of the app, `### Step 1` labels and list numbers
in lit amber, bullets drawn as small lit meter segments, code in the meter's dark display well, tables
with an amber header row, a dashed receipt rule for `---`, and display maths centred with real stacked
fractions and radicals. A `\boxed{}` final answer gets an amber frame, like a fare landing.

**Cut off, said plainly.** When the server reports `truncated: true`, the card shows a **Cut off** strip
under the answer, styled like the **Follow up** button (tangerine outline, follow-up icon) because that is
the fix: tapping **Continue** opens the follow-up box with "Please continue from where you stopped." ready
to send. Not an error banner: nothing failed, the answer just ran out of room.

**The rate follows the question.** The "about ₹X" on the Ask button and the tier meters tick up as a
long question is typed or pasted (legend switches from "per ask" to "this ask"); the follow-up meter also
counts the earlier parts it re-sends. See [Live estimate](#live-estimate).

**Accounts without a wall.** Signing up is optional and never stands between a new user and their first
question (for a pay-per-use tool, a signup wall is where most installs end). The welcome screen's main
button is still **Start asking**, which creates a guest wallet on this phone (`POST /v1/auth/register`);
under it, **Have an account? Log in or sign up**. The account screen is one screen with a two-key switch
(**Create account** / **Log in**, styled like the tier cards), a serif headline ("Keep your balance safe." /
"Welcome back."), email and password fields with a visible eye toggle, and **Skip for now, use without an
account** below an "or" rule. Guests get a small lit pilot dot on the profile key in the home top bar, a
quiet nudge rather than a nag. Everything stays in the night-meter palette: tangerine focus rings and
primary buttons, vermilion only for destructive actions, notices in the meter-panel style.

**Profile is the receipt drawer.** The profile key (round bezel button beside the balance meter) opens:
who you are and your balance on a meter; your saved history as the same compact fare receipts home uses
for "This session" (question, tier tag with heat bars, parts and date, fare on a small meter) with a trash
key per row; the draft Terms and Privacy pages; **Log out**; and **Delete account** in vermilion.

**Where things live:** `ui/Theme.kt` (palette, type, shapes), `ui/Meter.kt` (segment display, ticking
readout, meter-running, heat bars, perforation, balance meter), `ui/Components.kt` (tier cards, Ask button,
banners, Expert dialog), `ui/HomeScreen.kt`, `ui/AnswerScreen.kt`, `ui/AskScreen.kt` (switches home ↔
answer, back handling), `ui/AskViewModel.kt` (questions as threads that only grow on explicit follow-up),
`ui/WalletScreen.kt`, `ui/WelcomeScreen.kt`, `ui/CameraScreen.kt`, `core/Examples.kt`, `core/MeterText.kt`,
`ui/AnswerText.kt` (answer rendering), `core/Markdown.kt` + `core/Latex.kt` (parsing),
`core/CostEstimate.kt` (live estimate), `ui/AuthScreen.kt` + `ui/AuthViewModel.kt` + `core/AuthText.kt`
(signup/login), `ui/ProfileScreen.kt` + `ui/ProfileViewModel.kt` (profile, history, log out, delete
account), `data/History.kt` (lenient history parsing), `ui/LegalScreen.kt` + `core/LegalText.kt` (draft
legal text).

## Build & run

```bash
./build.sh assembleDebug testDebugUnitTest          # build + JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`build.sh` sources `../env.sh` if present (portable JDK/SDK paths, not in git), otherwise falls back
to `~/.local/jdk/current` and `~/Android/Sdk`. It keeps Android user state in `.android-user/`,
writes `local.properties`, and creates a project-local `debug.keystore` if missing (all git-ignored).

### Backend URL

| Build | Default | Override |
|---|---|---|
| debug | `http://10.0.2.2:8000` (host machine, seen from the emulator) | `./build.sh assembleDebug -PapiBaseUrl=http://192.168.1.20:8000` |
| release | `https://api.straightanswer.invalid` (placeholder) | `-PreleaseApiBaseUrl=https://…` |

Physical phone over USB without LAN setup: `adb reverse tcp:8000 tcp:8000` and build with
`-PapiBaseUrl=http://127.0.0.1:8000`. Use the same trick if the **emulator** can't reach `10.0.2.2`.
Newer emulator images route app traffic over a virtual Wi-Fi interface, and there connections to `10.0.2.2`
timed out in testing even though `nc` from `adb shell` worked.

Cleartext HTTP is allowed **only in debug builds** (`src/debug/res/xml/network_security_config.xml`);
release is HTTPS-only.

## What's in v1

| Feature | Where |
|---|---|
| 2-page welcome ("Pay for the answer. Not the month." with a live demo meter, then how it works), registers the device (`POST /v1/auth/register`) and opens the ask screen | `ui/WelcomeScreen.kt` |
| Home ("Got a question?"): large question box and an equally large "Snap it" camera tile side by side, rotating example questions, Fast / Smart / Expert tier cards with live per-answer rates, an Ask button showing the rate, and this session's answers as fare receipts | `ui/HomeScreen.kt`, `ui/Components.kt`, `core/Examples.kt` |
| Answer card: one question → one self-contained answer with its exact fare (ticks up from ₹0.00 and lands), copy, "New question" and an explicit "Follow up" that turns it into a short thread with a running total | `ui/AnswerScreen.kt`, `ui/AskViewModel.kt` |
| Tier names are plain labels; model names are never shown, and unknown tier keys from the server are dropped | `core/Tier.kt` |
| Expert guard: confirmation dialog with the rate and "roughly N× a Fast answer" (with "don't ask again"), for new questions and follow-ups | `ui/Components.kt` (`ExpertDialog`) |
| HTTP 402 → "Not enough fare" panel with an Add fare button; the unsent question goes back in its box. If the known balance is already below the estimate, the app shows this without sending | `AskViewModel.submit` |
| Camera → on-device OCR (CameraX + ML Kit) → text appended to whichever box is in use (home question or follow-up) for editing. "Choose a photo instead" (system photo picker, no permission) also works when the camera is denied. Nothing is uploaded | `ui/CameraScreen.kt`, `core/OcrText.kt` |
| Add fare (wallet): big balance meter, ₹20/₹50/₹100/₹200 fare keys + custom amount (whole rupees, ₹20–₹2,000), "balance after" readout, "≈ N Fast / Smart / Expert answers", top-up | `ui/WalletScreen.kt`, `core/TopUpAmount.kt` |
| Top-up via a payment abstraction (dev: backend mock confirm) | `payments/Payments.kt` |
| Token (+ account email) in DataStore. Guests: auto re-register + retry once on 401. Accounts: 401 signs out to the login screen ("Your session ended") instead of silently becoming a ₹0 guest | `data/SessionStore.kt`, `data/Backend.kt` |
| Account screen: Create account / Log in switch, email + password with show/hide toggle, client-side checks (email shape, non-empty password) before any request, 409 → "There's already an account with this email. Log in instead?" with a one-tap **Log in** (email and password kept), login failures always the same generic message, **Forgot password?** dialog that always ends on the generic "if an account exists…" text, Terms/Privacy links, and **Skip for now** (guest) | `ui/AuthScreen.kt`, `ui/AuthViewModel.kt`, `core/AuthText.kt` |
| Profile: identity + balance meter, full saved history from `GET /v1/history` as fare receipts with per-row delete (confirmation dialog: "Delete this question? … The fare already paid isn't refunded."), retry on load failure, Terms/Privacy, **Log out** (confirmed; accounts only), **Delete account** (dialog lists what is lost, shows the balance being forfeited on a vermilion meter, and only arms after typing `DELETE`) | `ui/ProfileScreen.kt`, `ui/ProfileViewModel.kt` |
| Terms of Service and Privacy Policy, clearly marked **Draft · pending legal review**, linked from the account screen and Profile | `ui/LegalScreen.kt`, `core/LegalText.kt` |
| Launcher icon: adaptive (espresso-black layer + amber ₹ foreground, monochrome for themed icons) plus legacy square/round PNGs at every density | `res/mipmap-*` (see [App icon](#app-icon)) |

Every new question starts with `conversation_id: null`; only an explicit follow-up reuses the
answer's `conversation_id`. The follow-up box and the running total say that follow-ups cost a bit
more, because the earlier parts are re-sent each turn (blueprint §7).

## Payments: what's stubbed

`PaymentGateway` (in `payments/Payments.kt`) is the seam:

```
TopUpFlow.topUp(amount)
  → POST /v1/wallet/topup            (backend creates the order)
  → gateway.pay(order)               (debug: DevMockPaymentGateway; release: UnavailablePaymentGateway)
  → new balance (from the gateway, or GET /v1/wallet)
```

* **Debug builds** (`BuildConfig.MOCK_PAYMENTS = true`) use `DevMockPaymentGateway`, which calls
  `POST /v1/wallet/topup/confirm` right away to simulate a successful UPI payment. The wallet
  screen shows a "Test mode" notice. The full loop (top up → balance goes up → chat works) runs end to end.
* **Release builds** use `UnavailablePaymentGateway`, which says top-up isn't available yet rather than faking it.
* **To add Razorpay:** write a `RazorpayPaymentGateway` that opens Razorpay Checkout (UPI intent flow) for
  the order, suspends until it finishes, and returns `PaymentOutcome.Success(null)` / `Cancelled` / `Failed`.
  The backend must credit the wallet from Razorpay's **webhook** (never from the client's word), and
  `TopUpFlow` then re-fetches the balance. Since the webhook can lag, the gateway may need to poll
  `GET /v1/wallet` briefly. Razorpay Checkout needs an `Activity`, so that gateway will also need an
  activity-result hook. Select it in `StraightAnswerApp.kt`.

## Backend API contract (what the client calls)

| Call | Auth | Body → Response |
|---|---|---|
| `POST /v1/auth/register` | none | → `{token}` |
| `GET /v1/wallet` | Bearer | → `{balance_inr}` |
| `POST /v1/wallet/topup` | Bearer | `{amount_inr}` → `{checkout_url, topup_id}` |
| `POST /v1/wallet/topup/confirm` | Bearer sent (contract doesn't require it) | `{topup_id}` → `{balance_inr}` (dev mock only) |
| `GET /v1/models` | Bearer if registered | → `{tiers: [{tier, label, est_cost_per_message_inr}]}` |
| `POST /v1/chat` | Bearer | `{tier, message, conversation_id, image_base64}` → `{reply, conversation_id, cost_inr, wallet_balance_inr, truncated}`; **402** = balance too low |
| `POST /v1/auth/signup` | none | `{email, password}` → `{token}`; **409** = email already has an account |
| `POST /v1/auth/login` | none | `{email, password}` → `{token}`; any failure shown as one generic message |
| `POST /v1/auth/forgot-password` | none | `{email}` → generic success (body ignored) |
| `GET /v1/history` | Bearer | → `{items: [{conversation_id, first_question, created_at, parts, total_cost_inr, tier}]}` (parsed leniently, see below) |
| `DELETE /v1/history/{conversation_id}` | Bearer | → 200 (404 is treated as "already gone") |
| `DELETE /v1/account` | Bearer | → 200, then the app signs out locally |

Requests send explicit `null`s (`"conversation_id": null`). Responses are parsed leniently (unknown
fields ignored, integer or decimal amounts). Errors read FastAPI-style `{"detail": "..."}`.
`image_base64` is always `null` in v1 (see gaps).

**History is parsed by hand** (`data/History.kt`), because the endpoint is being built in parallel and
"field names may differ slightly". Each field is read under its contract name, then likely aliases
(`id`/`conversationId`, `question`/`title`, `createdAt`/`timestamp`, `message_count`/`turns` or a list whose
size is the count, `cost_inr`/`total_inr`), numbers may be JSON numbers or strings, dates ISO-8601 with or
without a zone (no zone = UTC) or epoch seconds/milliseconds, and the list may be under `items`, `history`,
`conversations`, `data`, `results` or be the whole body. Items without any id are skipped (they couldn't be
deleted); unknown tier keys show without a tier tag. Unknown fields and skipped items are logged once per
load (logcat tag `StraightAnswer`), never fatal. Only a non-JSON body or one with no list is an error. `truncated` (the reply stopped at the model's length
limit) is new and optional on the client: absent or `null` reads as `false`, so older backends still work.

## Answer rendering: Markdown and maths

**The problem.** Answers were a plain `Text()`, so every structured answer (step-by-step maths, code,
lists) showed `**`, `\frac{…}{…}`, `\times`, `\[` and braces as literal characters, even though the
system prompt asks for plain-text maths. Models write LaTeX anyway, in every delimiter style.

**What was considered.**

| Option | Why not (or why) |
|---|---|
| Markwon (+ JLatexMath plugin) | View-based (`TextView`), not Compose; effectively unmaintained; JLatexMath adds ~1 MB+ and its own fonts. |
| `multiplatform-markdown-renderer` / `compose-richtext` | Real Compose Markdown, but no maths at all, extra parser dependency, and their default typography fights the theme (we'd restyle every element anyway). |
| WebView + KaTeX/MathJax | Best fidelity, but a WebView per answer card (memory on low-end phones, async height jumps inside a scrolling card, text selection/copy split across two worlds), ~300 KB–1 MB of bundled JS and fonts, and a web surface to style twice. |
| **Small custom parser + native Compose layout (chosen)** | Covers what models actually emit, zero dependencies and ~0 APK cost, every element styled with the app's own tokens, unit-tested on the JVM with real answers, renders synchronously so nothing jumps. |

**How it works.** `core/Markdown.kt` parses the answer into blocks (paragraphs, `#` headings, `-`/`*`/`+`
and numbered lists with nesting, `---` rules, `>` quotes, fenced code, pipe tables) and inline spans
(`**bold**`, `*italic*`, `***both***`, `` `code` ``, `~~strike~~`, `[links](https://…)`, and `x^2` in prose).
Maths is found in every form models use: `\( … \)`, `\[ … \]`, `$$ … $$`, `$ … $` (only when the content
looks like maths, so "₹5 or $10" stays text), a bare `\begin{aligned}…\end{aligned}`, a `[ x = \frac… ]` line
that lost its backslashes, and stray commands in prose (`3 \times 4`). `core/Latex.kt` parses the LaTeX
into a small tree (rows, fractions, scripts, radicals, boxes, accents, matrices/cases/aligned).

* **Display maths** (`\[ \]`, `$$`) is laid out in 2-D in Compose (`ui/AnswerText.kt`): stacked
  fractions with a rule, nested fractions shrinking, a drawn radical sign over the whole radicand (with an
  index for `\sqrt[3]`), `aligned` lines lined up on the `=`, matrices with drawn brackets, `cases` with a
  drawn brace, `\boxed{}` in an amber frame. Variables are italic, numbers and `sin`/`log` upright,
  operators spaced. Wide formulas scroll sideways inside the card instead of wrapping badly.
* **Inline maths** stays in the line as styled text: real raised/lowered scripts (`x²`, `v₀`), fractions
  linearised with brackets only where needed (`(−b ± √(b² − 4ac))/(2a)`, `½`), symbols as Unicode
  (`× · ÷ ± ≈ ≠ ≤ ≥ → ⇒ ∞ θ π Δ ∑ ∫ ℝ °`).
* **Copy** puts clean readable text on the clipboard (`Markdown.plainText`): no `**`, maths as Unicode
  (`x = (−3 ± 5)/4`, `g = 9.8 m/s²`), so it pastes sensibly into WhatsApp or notes.
* **Never raw source:** unknown commands degrade to their name without the backslash, stray braces are
  dropped, and parse errors can't throw. Tests assert no `\`, `{`, `}`, `**` or `$` survive in the two
  real-world answers that surfaced the bug.

**Limits (honest list).** Not a TeX engine: no `\def`/macros, `\overbrace` labels, `\xrightarrow`,
chemistry (`\ce{}`), commutative diagrams, or precise TeX spacing and font metrics. Matrix cells are
centred per column but row heights aren't shared across columns for tall entries. Superscripts nested
inside superscripts use Unicode characters (`e^(−x²/2)`), not a third level of raise. Inline fractions are
linear (`a/b`), never stacked, to keep line height steady. Maths uses the system serif; on phones whose
serif is the same as the sans (seen on the ColorOS test phone) it simply reads as clean sans maths.
Monospace code likewise depends on the system monospace font. If answers ever need research-grade maths,
the WebView + KaTeX route above is the upgrade path, ideally only for blocks this parser flags as
unsupported.

## App icon

Generated from the 1024px artwork (`icon-v2-1024.png` flat, `icon-v2-fg-1024.png` foreground) with a small
Pillow script, at the standard densities: adaptive foreground 108dp (108/162/216/324/432 px for
mdpi…xxxhdpi), legacy icons 48dp (48/72/96/144/192 px) as `ic_launcher.png` (rounded square) and
`ic_launcher_round.png` (circle). `mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml` use a plain
`@color/launcher_bg` (`#130F0C`, Ink.Bg) background and the PNG foreground, which also serves as the
monochrome layer for Android 13 themed icons. The old vector placeholder (`drawable/ic_launcher_foreground.xml`) is gone.

Two things about the supplied files, handled in the conversion:

* **The "foreground" PNG was not transparent.** It is fully opaque, identical to the flat icon with the
  dark background baked in. The script recovers a true alpha mask by un-blending each pixel along the
  background → amber (`#FFA32A`) axis, so the anti-aliased edges stay smooth on any launcher background.
* **The mark extended past the adaptive-icon safe zone.** On the 108dp canvas the ₹ and its underline
  reach about 42dp from the centre; the safe zone is a 33dp radius and a circular launcher mask shows only
  36dp, so the underline would have been clipped. The foreground is scaled to 75.8% about the centre so
  the farthest pixel sits at ~32dp. Checked against circle, squircle and full-bleed masks.

With minSdk 26 the framework always resolves the adaptive XML, so the legacy PNGs are a fallback for
launchers/tools that load density bitmaps directly. If design wants a bigger mark, supply a foreground with
the art inside the central 66dp circle (≈626px of 1024) on a transparent background.

## Decisions & assumptions

* **minSdk 26 (Android 8.0).** In 2026 this covers about 97% or more of active Android devices in India. Current
  AndroidX/Compose already need 23+, so going lower gains almost nobody. 26 also gives adaptive icons
  (so no raster launcher fallbacks), `java.time` and `java.util.Base64`. compile/targetSdk 37, the same as TileOps.
* **Fast is the default tier**, and the last-used tier is remembered (blueprint §7.1).
* **The token is not Keystore-encrypted.** For guests it is still the only key to their wallet, and a
  Keystore key lost on some OEM ROMs would quietly empty their balance. So it lives in app-private
  DataStore and is left out of backup and device transfer. The password is never stored on the phone
  (it is cleared from memory once signup/login succeeds); only the token and the account email are.
* **Signup is optional (placement).** Welcome → **Start asking** stays the one-tap guest path; the account
  screen is a secondary link there, the destination after logging out, and reachable from Profile. Its
  **Skip for now** button calls `register()`. A guest who opens the account screen from Profile sees
  **Keep using as a guest** instead (it just goes back).
* **Guest → account does not carry the guest balance over.** The contract's signup takes only
  `{email, password}`, so the app doesn't send the guest token with it. A guest with a balance sees a
  notice on the account screen ("Creating an account or logging in starts a separate wallet, and the guest
  balance can't be moved into it"). Suggested backend follow-up: accept the guest bearer on
  `/v1/auth/signup` and convert that user in place; the client change is one header.
* **Guests have no "Log out".** For a guest, logging out would orphan the wallet with no way back. Their
  Profile offers **Create an account or log in** and **Delete guest wallet and history** instead.
* **401 handling differs by session.** Guest: drop the token, re-register, retry once (unchanged; handy when
  a dev DB resets). Account: never swap in a fresh guest wallet; sign out and show the login screen with
  "Your session ended". After an explicit log out, nothing re-registers silently in the background.
* **Delete account takes two deliberate steps:** open the dialog (which lists what is lost and shows the
  forfeited balance), then type `DELETE` (case-insensitive) before the button arms. A failed request keeps
  you signed in and says "Your account was not deleted".
* **Login errors are generic on purpose** (400/401/403/404/422 → one message), and the forgot-password
  dialog shows the same "if an account exists…" text whatever the server says; only a network failure
  is reported as an error there.
* **Validation:** a loose email shape check (something@domain.tld, no spaces, no `..`) and a non-blank
  password, as specified. No client-side password-strength rule: the server's own 4xx message is shown if
  it is a plain string (FastAPI validation lists fall back to friendly wording, as everywhere else).
* **OCR uses ML Kit through Google Play services** (`play-services-mlkit-text-recognition`), not the bundled
  model. The bundled one added about 10 MB of native code per ABI (the release APK was 45 MB), which is a lot
  for low-storage phones on prepaid data. The model downloads at install time
  (`com.google.mlkit.vision.DEPENDENCIES=ocr` in the manifest). If it isn't ready yet, the camera screen says so.
  If it isn't ready yet, the camera screen also asks Play services for it (`ModuleInstall`). Phones without
  Play services can't use OCR. That's acceptable for a Play Store-only app; switching back to
  `com.google.mlkit:text-recognition` is a one-line change.
* **The camera only does OCR; the photo is not sent.** It keeps per-message cost predictable and the
  "not uploaded" promise simple. The API's `image_base64` is there for a later "send the photo too"
  option (diagrams, handwriting).
* **On 401 the client re-registers and retries once.** That's convenient when a dev backend resets.
  In production it would mean a fresh ₹0 wallet. See account recovery below.
* <a id="live-estimate"></a>**Live estimate.** `GET /v1/models` gives one flat per-message figure per tier,
  which the backend computes for a typical message of 300 input tokens (mostly its own ~265-token system
  prompt) and 400 output tokens. The client scales it to the question being typed (`core/CostEstimate.kt`):
  input tokens = system-prompt overhead + the question (UTF-8 bytes / 3, the server's own rough count, so
  Hindi isn't undercounted) + for follow-ups the earlier questions and answers the server re-sends. Short
  questions show the server's figure exactly (it's the floor); a 2,000-character paste shows about 1.3x; an
  8,000-character one about 2.4x. Since only the flat figure is known, the input/output price split is
  assumed: output tokens cost about 4x input tokens (typical for the routed models). The answer's length is
  unknown before sending, so output stays at the typical 400. The figure is rounded to the paisa so the meter
  ticks only when the shown amount changes. The pre-send "not enough balance" check still uses the flat
  figure, so this change can't newly block a send. The exact charge still lands with the answer.
* <a id="per-ask-not-showing"></a>**"Per-ask amount not showing" (investigated Nov 2026): two separate things.**
  1. *All-free models (not a client bug).* While every tier routed to a free model, `GET /v1/models` returned
     `0.0` for all three, so every meter read ₹0.00 and typing never moved them (scaling ₹0 by any length is
     still ₹0). That looks like "nothing updates" but is correct display. With the backend moving Smart/Expert
     to paid models, verified against a mock returning non-zero rates: Smart ₹0.31 → ₹0.33 and Expert ₹2.40 →
     ₹2.53 as a ~1,800-character question is pasted, and Fast keeps showing ₹0.00 (a lit "0.00", not a blank).
  2. *A real client bug, fixed.* Prices were fetched **once per process** (when the ask view model was
     created), in one `try` *after* the balance call. Reproduced on an emulator with the pre-change build:
     (a) if that first fetch failed (backend cold start, offline at launch), the tier meters read `-.--` and the
     Ask button showed **no amount at all**, and stayed that way after the backend came back, until the app
     process was killed; (b) a running app never saw a server price change: after switching Smart to ₹0.31
     on the server, the old build still showed ₹0.00 after visiting the wallet and backgrounding/foregrounding;
     (c) a failing `/v1/wallet` call skipped the price fetch entirely. Fix: the ask screen refreshes prices and
     balance on every resume (`LifecycleEventEffect(ON_RESUME)`), prices and balance load independently, the
     "couldn't load" error clears when a later refresh succeeds, the wallet re-reads prices when opened, and the
     Ask button keeps its inset meter with `-.--` while no rate is known instead of dropping it. Re-tested with
     the new build: outage at launch → `-.--` + error; backend back → resume → rates and amount return.
  Free-tier wording also fixed: with Fast at ₹0 the ₹0-balance card said "Add ₹20 to start" (untrue, Fast
  works at ₹0) and the wallet's "≈ N asks" / "Enough for…" lines disappeared entirely; they now say Fast is
  free and count the cheapest paid tier.
* **Top-up range ₹20–₹2,000.** The minimum is from the blueprint. The maximum keeps the closed-loop wallet
  small and stays under the ₹2,000 UPI MDR threshold. The backend should enforce the same limits.

## Known gaps / TODO

* **Accounts exist now, but guests still lose their wallet** on uninstall/clear data/new phone, and a guest
  balance can't be moved into a new account (see Decisions). Backend support for upgrading a guest in place
  would close this.
* **Log out is local only:** the contract has no logout/revoke endpoint, so the token stays valid
  server-side until it expires. Add `POST /v1/auth/logout` if tokens are long-lived.
* **Forgot password ends at the email.** The reset link's landing page (web or deep link back into the app)
  is the backend's side and isn't designed yet.
* **History rows can't be reopened.** `GET /v1/history` returns only the first question and totals, not
  the answers, so rows are receipts (delete-able) rather than links. A `GET /v1/history/{id}` would let a
  row open the full answer card and continue it. History isn't paginated either; fine for now, needed later.
* **Legal text is a draft.** `core/LegalText.kt` is honest plain-language copy, marked "Draft · pending legal
  review" on screen, with `[TO CONFIRM]` notes for refund/expiry rules (RBI PPI review), OpenRouter/provider
  retention, payment provider, backup retention, minimum age / parental consent under the DPDP Act 2023,
  and contact/grievance details. The password-hashing statement must be confirmed against the backend.
* **Razorpay** isn't integrated (see above). The release build can't top up.
* **Home's "This session" list is still in memory only** (it can reopen and follow up an answer); the
  persistent record is Profile → History.
* **No streaming.** `/v1/chat` returns the whole reply at once, so while waiting the fare meter shows a "meter running"
  chase rather than a live count. If the backend ever streams, the readout can tick live as tokens arrive.
* **The Play-services OCR download hasn't been verified end to end.** On the dev emulator (no Google account)
  Play services refused to download the module, so the app showed its "text reader is still being set up"
  message. With the *bundled* model, the same code read a photographed question into the input correctly.
  Check on a real phone before launch.
* **OCR is Latin script only.** Hindi and other Devanagari text needs the ML Kit Devanagari model.
* **The live estimate is an approximation** (see [Live estimate](#live-estimate)). For real accuracy the backend
  should also return per-token prices per tier in `GET /v1/models` (e.g. `prompt_inr_per_mtok`,
  `completion_inr_per_mtok`, plus its system-prompt token count); then the client can drop the assumed 4:1
  output:input ratio and the hard-coded 300/400/265 token baselines, which today must be kept in step with
  `EST_PROMPT_TOKENS` / `EST_COMPLETION_TOKENS` and the system prompt in the backend by hand.
* **Answer rendering limits** are listed under [Answer rendering](#answer-rendering-markdown-and-maths).
* No instrumented/UI tests, release signing config, Play listing or crash reporting yet. Password-manager
  autofill hints are set on the email/password fields (`ContentType`), but autofill wasn't tested with a
  real password manager.
* Wallet T&C copy (non-transferable, non-withdrawable) is a draft, pending the RBI/PPI check in blueprint §9.1.

## Tests

Last run: 135 JVM tests, 0 failures (`./build.sh assembleDebug testDebugUnitTest`).

**Accounts/profile/rename pass (Nov 2026), emulator (API 37, 1080×2400) against a throwaway mock of the
full contract including the new endpoints:** fresh install → welcome → "Have an account?" → signup screen;
empty submit and `riya@gmail` blocked client-side with no request sent; password eye toggles; signup →
home with ₹0.00 balance and the "Fast answers are free right now" card; live estimates (see
[above](#per-ask-not-showing)); top-up; Smart and Fast questions; Profile history (2 rows, tier tags,
dates, fares; an extra `model_slug` field logged and ignored); delete row ("Keep it" does nothing,
"Delete" sends `DELETE /v1/history/{id}`); Terms page; log out (confirmed) → "Welcome back." with no way
back into the app; wrong password → generic message; forgot password → generic confirmation; correct login
→ ₹19.50 back; delete account (button inert until `DELETE` typed, forfeited balance shown) → login screen;
skip → guest home with the pilot-dot nudge → guest Profile; signup with an existing email → 409 message
with a one-tap Log in. That walk-through caught one bug the unit tests then covered: after switching to a
new account the balance meter stayed `--.--` (an account's ₹0 was already in the backend's StateFlow, so it
never re-emitted after the screen reset).

Answer rendering, the cut-off strip and the live estimate were checked on a real phone (ColorOS, 1080×2400)
against a throwaway mock of the contract serving the two real-world answers (one with `truncated: true`)
and a formatting sampler (lists, table, code, aligned, matrix, cases, `\sqrt[3]`, `\boxed`). That run caught
two things JVM tests can't: Android's ICU regex engine rejects an unescaped literal `}`/`]` that the JVM
accepts (all patterns now escape them), and a Compose layout may measure each child only once (the table
grid sizes from intrinsics instead). Re-check on device after touching `core/Markdown.kt`/`core/Latex.kt`
regexes or `ui/AnswerText.kt` layouts.

The redesign was also walked through on an emulator (API 37, Pixel 6 size) against a throwaway mock of the
contract: welcome → register → home with ₹0 meter → Add fare ₹20 (balance counts up) → ask (meter running →
answer card, fare ₹0.02 lands, top-bar balance ticks down) → follow-up (second fare, running total) → Expert
tier → confirmation dialog → answer → home shows the session receipt → camera screen.

`./build.sh testDebugUnitTest` runs JVM unit tests (no device needed):

* `data/ApiClientTest` runs the real `ApiClient` against a local `HttpServer`. It checks the paths,
  bearer header, request JSON (including explicit nulls), response parsing, 402 handling and error bodies.
* `data/BackendTest` covers one-time registration, re-registering after a 401, and the shared balance and tier filtering.
* `payments/TopUpFlowTest` covers the dev mock top-up loop, top-up → chat, webhook-style gateways and the unavailable gateway.
* `ui/AskViewModelTest` covers ask → answer card with exact fare and balance, the "meter running" state, follow-ups
  (same conversation id, running total), new questions starting fresh, 402 → add-fare state with the question given back
  (for new questions and follow-ups), the balance pre-check, Expert confirmation for both, examples and camera text
  filling the right box, and back from an answer clearing transient state.
* `core/MarkdownTest` and `core/LatexTest` cover every block and inline form, all maths delimiter styles,
  emphasis false positives (`2 * 3 * 4`, `snake_case`, `$5 and $10`), LaTeX → Unicode for operators,
  scripts, fractions (brackets only when needed), radicals, units, environments, "never a backslash or brace"
  on malformed input, and the two real-world answers from the bug report (`core/RealAnswers.kt`: a quadratic
  step-by-step solution and a projectile-motion problem) end to end.
* `core/CostEstimateTest` covers the live estimate (short = server figure, longer = visibly more, follow-up
  history, Devanagari token count, sub-paisa rates); `ui/AskViewModelTest` checks it follows typing, tier
  switches and the open thread, plus `truncated` → Cut off → Continue pre-filling the follow-up (without
  overwriting typed text). `data/ApiClientTest` checks `truncated` parses and defaults to `false` when absent or `null`.
* `data/HistoryParserTest` covers the contract shape and the drift it tolerates (aliases, string numbers, epoch and
  zone-less dates, list-as-count, bare arrays, `null` lists, skipped id-less items, unknown-field reporting).
* `data/BackendTest` also covers signup/login storing token + email, restoring the session, an account 401 signing out
  (never silently becoming a guest), no background re-registration after log out, delete-history 404 as success, and
  delete-account signing out. `data/ApiClientTest` checks the auth/history/delete paths, methods, bodies and bearer use.
* `ui/AuthViewModelTest` (validation blocks the network, signup, 409 → log in instead, generic login errors, guest skip,
  the guest-balance warning, forgot password) and `ui/ProfileViewModelTest` (history load/retry, confirmed delete,
  failed delete, confirmed log out, delete account arming only on `DELETE`, failed deletion keeps you signed in, guests).
* `ui/AskViewModelTest` also covers the per-ask fixes: free Fast reads ₹0.00 while paid tiers follow the question,
  prices recover on the next refresh after a failed first load, a balance failure no longer blocks prices, price
  changes are picked up, and reset keeps tier, prices and balance.
* `core/AuthTextTest`, `core/HistoryTextTest`, `core/LegalTextTest`: email shapes, error wording, history dates in
  the local calendar, and that both legal texts are drafts and say what they must.
* `core/*Test` covers ₹ formatting, meter readout text (leading unlit cells, sub-paisa), tier mapping, top-up validation,
  example rotation, OCR cleanup and friendly errors.
