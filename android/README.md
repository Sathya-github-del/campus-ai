# StudyApp — Android client

> **StudyApp** / `dev.studyapp.app` is a placeholder name and package. Rename before release
> (applicationId + namespace in `app/build.gradle.kts`, the Kotlin package, `app_name`). The in-app UI
> deliberately shows no product name, so only the launcher label (`app_name`) still says "StudyApp".

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

**Where things live:** `ui/Theme.kt` (palette, type, shapes), `ui/Meter.kt` (segment display, ticking
readout, meter-running, heat bars, perforation, balance meter), `ui/Components.kt` (tier cards, Ask button,
banners, Expert dialog), `ui/HomeScreen.kt`, `ui/AnswerScreen.kt`, `ui/AskScreen.kt` (switches home ↔
answer, back handling), `ui/AskViewModel.kt` (questions as threads that only grow on explicit follow-up),
`ui/WalletScreen.kt`, `ui/WelcomeScreen.kt`, `ui/CameraScreen.kt`, `core/Examples.kt`, `core/MeterText.kt`,
`ui/AnswerText.kt` (answer rendering), `core/Markdown.kt` + `core/Latex.kt` (parsing),
`core/CostEstimate.kt` (live estimate).

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
| release | `https://api.studyapp.invalid` (placeholder) | `-PreleaseApiBaseUrl=https://…` |

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
| Token in DataStore; auto re-register + retry once on 401 | `data/SessionStore.kt`, `data/Backend.kt` |

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
  activity-result hook. Select it in `StudyApp.kt`.

## Backend API contract (what the client calls)

| Call | Auth | Body → Response |
|---|---|---|
| `POST /v1/auth/register` | none | → `{token}` |
| `GET /v1/wallet` | Bearer | → `{balance_inr}` |
| `POST /v1/wallet/topup` | Bearer | `{amount_inr}` → `{checkout_url, topup_id}` |
| `POST /v1/wallet/topup/confirm` | Bearer sent (contract doesn't require it) | `{topup_id}` → `{balance_inr}` (dev mock only) |
| `GET /v1/models` | Bearer if registered | → `{tiers: [{tier, label, est_cost_per_message_inr}]}` |
| `POST /v1/chat` | Bearer | `{tier, message, conversation_id, image_base64}` → `{reply, conversation_id, cost_inr, wallet_balance_inr, truncated}`; **402** = balance too low |

Requests send explicit `null`s (`"conversation_id": null`). Responses are parsed leniently (unknown
fields ignored, integer or decimal amounts). Errors read FastAPI-style `{"detail": "..."}`.
`image_base64` is always `null` in v1 (see gaps). `truncated` (the reply stopped at the model's length
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

## Decisions & assumptions

* **minSdk 26 (Android 8.0).** In 2026 this covers about 97% or more of active Android devices in India. Current
  AndroidX/Compose already need 23+, so going lower gains almost nobody. 26 also gives adaptive icons
  (so no raster launcher fallbacks), `java.time` and `java.util.Base64`. compile/targetSdk 37, the same as TileOps.
* **Fast is the default tier**, and the last-used tier is remembered (blueprint §7.1).
* **The token is not Keystore-encrypted.** It is the only key to the student's wallet (there are no accounts yet).
  A Keystore key lost on some OEM ROMs would quietly empty their balance. So it lives in app-private
  DataStore and is left out of backup and device transfer. Revisit once account recovery exists.
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
* **Top-up range ₹20–₹2,000.** The minimum is from the blueprint. The maximum keeps the closed-loop wallet
  small and stays under the ₹2,000 UPI MDR threshold. The backend should enforce the same limits.

## Known gaps / TODO

* **Account recovery / multi-device (needed before real money):** the wallet is tied to an anonymous
  device token. Uninstalling the app, clearing its data or changing phones loses the balance. Add phone/OTP or Google sign-in.
* **Razorpay** isn't integrated (see above). The release build can't top up.
* **App icon and brand** are placeholders (`res/drawable/ic_launcher_foreground.xml` is a tangerine bubble with a
  seven-segment "?" in the new identity; the launcher label is still "StudyApp", which now contradicts the
  general-purpose positioning and should change with the brand).
* **No saved history.** "This session" answers live only in memory until the app process dies (history is v1.1 per the blueprint).
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
* No instrumented/UI tests, release signing config, Play listing, privacy policy, or crash reporting yet.
* Wallet T&C copy (non-transferable, non-withdrawable) is a draft, pending the RBI/PPI check in blueprint §9.1.

## Tests

Last run: 80 JVM tests, 0 failures (`./build.sh assembleDebug testDebugUnitTest`).

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
* `core/*Test` covers ₹ formatting, meter readout text (leading unlit cells, sub-paisa), tier mapping, top-up validation,
  example rotation, OCR cleanup and friendly errors.
