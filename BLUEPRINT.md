# Blueprint: Pay-as-you-go AI for Indian students
**Working name:** `campus-ai` (placeholder — not a final brand name)
**Status:** Pre-build research complete. Nothing built yet.
**Author:** Sathya Prakash J
**Platform:** Android only (Kotlin + Compose client, backend required for key security and billing)

---

## 1. The problem, stated precisely

- Claude/GPT/Gemini full subscriptions run ~₹1,999–2,000/month in India.
- OpenAI's cheaper tier, ChatGPT Go, is ₹399/month — still a subscription, still assumes daily use.
- Google currently gives eligible Indian **college** students a free year of Google AI Plus (Gemini), verified via SheerID. This is real and live, and it removes part of the market: college students who know about it and complete verification.
- Nobody found in this research sells AI access **per message, in rupees, via UPI, to a consumer who doesn't write code.** The closest thing that exists — [AICredits.in](https://aicredits.in) — does rupee/UPI pay-as-you-go access, but it's a **developer API gateway** (swap `base_url`, write code). It is not a chat app.
- **The gap:** light/occasional users (a student asking a handful of questions a week), school students, college students who skip Google's offer, and anyone who wants Claude/GPT specifically — none of them are served by a flat subscription or a developer tool.

## 2. What it is

An Android chat app for people who don't write code:
- Camera button → photograph a textbook problem → ask about it (OCR/vision)
- Subject-templated prompts: Math, Physics, Coding, Essay help, English doubt-solving
- A simple **Fast / Smart / Expert** model toggle (not raw model names)
- Cost of each message shown in ₹ before it's sent
- Wallet: non-transferable in-app credits, UPI top-up starting at ₹20

**What it deliberately is not:** a bare "pick any model, raw chat" clone. That distinction is the legal hinge of the whole project (§4).

## 3. Architecture

| Layer | Choice | Why |
|---|---|---|
| Client | Kotlin + Jetpack Compose | Same stack as prior Android work; reusable skills |
| Backend | Required — thin proxy service | API keys cannot ship inside an APK; also where billing/metering/abuse-checks must live |
| Model gateway | OpenRouter | Terms explicitly permit this use case (§4); wide model selection; live pricing API |
| Payments | Razorpay, UPI, alternative billing path | Confirmed available to all developers in India (§5) |
| Prompt caching | Required from day 1, not an optimization for later | Cuts multi-turn conversation cost dramatically (§7) — see why in §7 |

## 4. Legal basis — read from primary sources, not summaries

**OpenRouter's Terms of Service** ([openrouter.ai/terms](https://openrouter.ai/terms), read directly):

Prohibited (§7): *"access the Site or Service for purposes of **reselling API access to Models** or otherwise **developing a competing service**."*

Explicitly permitted (§5.1 — Model Terms): *"the Service enables you, your Authorized Users, and your customers **(to the extent you incorporate the Service into your own products and services)** access to Models provided by Model Providers."*

**Conclusion:** giving someone raw model access, or building another bare aggregator, is prohibited. Building your own product — your own UI, your own credit system, your own workflow — that calls models on the backend is the explicitly permitted case. This app is designed to sit firmly on the permitted side: it has its own camera workflow, subject templates, and Fast/Smart/Expert framing, not a raw model picker.

**AICredits' Terms of Service** ([aicredits.in/terms](https://aicredits.in/terms), read directly) are thinner and more ambiguous — "resell or redistribute the Service without authorization," with no equivalent carve-out language. **Recommendation: do not build on AICredits without asking them directly first** (`legal@aicredits.in`). Not needed for v1 since OpenRouter already gives a clear yes.

**Trademark (separate from the above):** never name the app or claim affiliation with AICredits, OpenRouter, Anthropic, OpenAI, or Google. Unambiguous, easy to just not do.

## 5. Payment & platform compliance, sourced

| Item | Finding | Source |
|---|---|---|
| Google Play alternative billing in India | Available to all developers. Service fee reduced by **4 points** vs standard Play Billing (15%→11% on the standard tier) — **not free**, just cheaper | Google's own Play Console help page, fetched directly |
| New UPI MDR (0.4%, from 15 Oct 2026) | Applies only to person-to-merchant transactions **above ₹2,000**. A ₹20 top-up is unaffected. | Multiple 2026 news sources on the RBI/NPCI framework |
| Razorpay UPI fee | ~2% + 18% GST on that fee ≈ **2.36%** effective, even though UPI itself has zero bank-side MDR below the new threshold | Multiple current pricing breakdowns |
| Razorpay new-merchant promo | **0% platform fee for first 90 days** | Same sources as above |
| RBI PPI (wallet) rules | Closed-loop, non-transferable, non-cashable in-app credits are the standard pattern treated as exempt from PPI licensing (same bucket as game currency) | Secondary legal/fintech commentary — **primary RBI Master Direction text not confirmed; get this checked before launch, not after** |
| GST | 18% on digital services. Registration required once turnover crosses ₹20 lakh (₹10 lakh in special-category states) for a domestic seller | CGST Act Section 22, per multiple sources |
| Google Play Developer account | **$25 one-time**, no renewal, covers all apps under the account | Confirmed across multiple current (2026) sources |

## 6. Live model pricing (pulled directly from OpenRouter, not estimated)

Fetched from openrouter.ai/models during this research pass. **The specific named models will be superseded by build time — what's durable is the tier spread, not the names:**

| Tier example (Nov 2026 snapshot) | Input | Output |
|---|---|---|
| Fast (e.g. Meta: Muse Ember) | $0.10/M tokens | $0.50/M tokens |
| Expert/flagship (e.g. Anthropic's current top model) | $8/M tokens | $40/M tokens |

**That's an 80x spread between tiers.** This is the single most important number in this whole document — it's wider than assumed in earlier drafts of this plan, and it directly shapes the tiering design (§8).

USD/INR used throughout this doc: **₹93/$**, a working midpoint — sources disagreed between ₹86 and ₹96 and I could not pin a verified live rate. **Recheck this at build time; it moves every number below proportionally.**

## 7. What ₹20 actually buys

The fee waterfall from §5, applied to one ₹20 top-up:

| Step | Rate | ₹ remaining |
|---|---|---|
| Student pays | — | ₹20.00 |
| Google Play (alternative billing) | 11% | ₹17.80 |
| Razorpay | 2.36% | ₹17.38 |
| OpenRouter (bulk credit purchase fee) | 5.5% | **₹16.47 real AI credit** |

**~82% of face value survives the platform stack.**

Converting that into usage — and this is the part worth sitting with: **a chat API resends the entire conversation history as input on every turn.** A standalone question is cheap. A 5-message back-and-forth is far more expensive than 5× a standalone question, because each turn re-pays for every prior turn.

| Scenario | Tokens (in / out) | Cost | What ₹16.47 buys |
|---|---|---|---|
| One standalone question, Fast tier | ~150 / 400 | ~₹0.02 | **~800 questions** |
| One 5-turn conversation, Fast tier | ~5,500 / 2,000 (history resent) | ~₹0.14 | **~115 full conversations** |
| One standalone question, Expert tier | ~150 / 400 | ~₹1.60 | **~10 questions** |
| One 5-turn conversation, Expert tier | ~5,500 / 2,000 | ~₹11.50 | **~1.4 conversations** |

**The Expert-tier row is the critical finding:** on the flagship model, ₹20 barely covers one real conversation. This is why:
1. **Default everyone to Fast tier.** At ~800 questions per ₹20, pricing essentially disappears as a concern for normal use.
2. **A two-tier Fast/Expert split is too big a cliff (80x).** Add a genuine **mid tier** (~$0.50–2/M tokens) so "Smart" isn't "nearly unusable."
3. **Gate Expert hard** — show cost before sending, consider a minimum balance to unlock it.
4. **Prompt caching is not optional.** Both Anthropic's and OpenAI's APIs (passed through by OpenRouter) charge roughly 90% less for input tokens that repeat across turns. Implemented properly, this turns "₹11.50 per Expert conversation" into something much smaller. **Build this in from day 1, not as a later optimization** — retrofitting it after the data model is set is much more painful.

## 8. V1 feature scope

| Feature | In v1? | Notes |
|---|---|---|
| Fast / Smart / Expert model toggle | Yes | 3 tiers, not 2 — see §7 |
| Camera → OCR/vision → ask | Yes | Core differentiator vs. a plain chat app |
| Subject quick-prompts | Yes | Math, Physics, Coding, Essay, English |
| Per-message ₹ cost shown before sending | Yes | Trust + keeps the pricing honest |
| UPI top-up, ₹20 minimum | Yes | Via Razorpay alternative billing |
| Prompt caching | Yes | See §7 — architectural, not optional |
| Non-transferable credit wallet | Yes | The RBI-safe structure (§5), pending the check noted there |
| Multi-model comparison / raw model picker | **No** | This is the line that would make it look like reselling (§4) |
| Conversation history / saved chats | v1.1 | Not essential for launch |
| Referral/campus-ambassador system | v1.1 | Growth lever, not core product |

## 9. What still needs a professional, not a guess, before money moves

1. **RBI wallet structure** — the non-transferable-credit pattern is the standard mitigation, but the primary RBI Master Direction text was not confirmed in this research. Get this checked.
2. **Live OpenRouter and USD/INR rates** at build time — this document's numbers are a snapshot.
3. **GST registration timing** — not needed at ₹0 revenue, needed well before ₹20 lakh turnover; set it up early anyway since it's low-cost and avoids a scramble later.
4. **Razorpay business KYC** — requires a registered entity (sole proprietorship is sufficient) and a bank account in that entity's name.

## 10. Investment required

Numbers below are sourced where marked; everything else is a reasoned estimate, clearly labeled.

### One-time setup

| Item | Cost (₹) | Basis |
|---|---|---|
| Google Play Developer account | ~2,100–2,300 | **Sourced**: $25 flat, confirmed across multiple current sources |
| GST + Udyam registration (professional fee; government fee is ₹0) | 1,000–3,000 | Sourced range, multiple current sources |
| Compliance consultation specifically on the wallet/credit structure (§9.1) | 5,000–8,000 | Estimated from general CA consultation ranges (₹500–10,000 depending on complexity); this is a more specific question than routine registration |
| Domain name (1 year) | 800–1,200 | Standard .in/.com registrar pricing |
| App icon / store graphics | 0 | Assume self-made; outsourcing adds ₹2,000–5,000 if wanted |
| **Subtotal** | **~₹9,000–14,500** | |

### Working capital (not an expense — prepaid, recovered as users spend it)

| Item | Amount (₹) | Why |
|---|---|---|
| OpenRouter seed balance | 10,000–15,000 | Needs to be funded *ahead* of user top-ups (Razorpay settlement lags by 1–4 days); sized for several hundred early users' first top-ups before revenue cycles back |

### Ongoing fixed costs (monthly, pre-scale)

| Item | ₹/month | Basis |
|---|---|---|
| VPS (backend) | 800–1,500 | **Sourced**: Indian budget VPS providers quote ₹400–800 for 2GB/50GB; mid-tier ₹1,200–2,500. Took the lower-mid end. |
| Domain + misc, amortized | ~150 | |
| **Subtotal** | **~₹1,000–1,650/month** | |

### Total cash to launch and run ~3 months

| Scenario | Total |
|---|---|
| Minimum (DIY compliance, smaller float, skip paid consultation) | **~₹15,000** |
| Recommended (proper compliance check, adequate float) | **~₹28,000** |

**This does not include the founder's own development time**, which is the actual largest input here (§11) and isn't a cash cost for a solo build.

## 11. Time investment

Estimated **4–6 weeks of solo, AI-assisted development** to a tested v1: Android chat client, backend proxy with metering, OpenRouter integration with prompt caching, Razorpay alternative-billing integration, and the camera/OCR workflow. This is an estimate, not a committed schedule — payment integration and Play Store review time are the likeliest sources of delay, not the AI features.

## 12. ROI — precise where precision is possible, honest where it isn't

**What can be stated precisely: the margin structure.** What cannot be stated precisely by anyone: how many students will actually use this. Rather than invent a single confident ROI percentage, here is the sensitivity — profit at different adoption levels, so you can judge against what you think is realistic for a campus/city-level launch.

**Per-user economics, one ₹20 top-up/month:**
- Revenue: ₹20.00
- Platform fees (§7): −₹3.53
- Remaining: ₹16.47
- Assume ~60% of that is actually consumed by the user (natural breakage — unused balance — is normal in prepaid credit products, similar to gift cards and game currency): COGS ≈ ₹9.88
- **Gross margin per active paying user: ≈ ₹6.59/month (~33% of revenue)**

This 33% figure is the one genuinely load-bearing assumption in this whole ROI section — it depends on how generous the Fast-tier allowance is and how much of it students actually use. Treat it as a planning assumption to validate with real usage data in month 1, not a fact.

**Break-even on fixed costs alone:**
₹1,000–1,650 fixed ÷ ₹6.59 margin/user ≈ **152–250 active paying users/month**

**Payback on the one-time + working-capital investment (~₹25,000–43,000 total including float), at different scales:**

| Active paying users/month | Monthly margin | Minus fixed costs | Net/month | Payback period on ~₹28,000 investment |
|---|---|---|---|---|
| 150 | ₹989 | −₹1,650 | **−₹661** | Never, at this scale — below break-even |
| 300 | ₹1,977 | −₹1,650 | +₹327 | ~86 months — too slow, scale must grow |
| 500 | ₹3,295 | −₹1,650 | +₹1,645 | ~17 months |
| 1,000 | ₹6,590 | −₹2,000 (scaled VPS) | +₹4,590 | **~6 months** |
| 2,500 | ₹16,475 | −₹3,000 | +₹13,475 | ~2 months |

**Read this table as:** the product only works financially past roughly 500 active paying users/month, and becomes genuinely attractive past ~1,000. Below ~250, it loses money every month on fixed costs alone, independent of the initial investment. **The entire ROI question reduces to one thing: can this acquire and retain a few hundred to a thousand paying students in a city or a few colleges.** That's a distribution/marketing question, not a build question, and nothing in this research answers it — it needs a real v1 in front of real students to find out.

## 13. Risks, restated plainly

- **Adoption is unproven and unknowable in advance** — see §12. Everything else in this document can be made precise; this cannot.
- **Margin is thin and sensitive to usage patterns** — if students use more credit than the 60%-consumption assumption, margin shrinks fast. Needs real usage data early.
- **OpenRouter's permitted-use line (§4) still has some interpretive room** — the explicit carve-out language helps a lot, but it's not a signed legal opinion.
- **If a bigger player (Google, OpenAI, or AICredits itself) ships a consumer pay-per-use chat UI, differentiation narrows fast.** The camera/subject-template workflow is the defensible part, not the pricing model, which is easy to copy.
- **RBI's wallet rules are tightening in 2026** (noted in earlier research, not re-verified in this pass) — the non-transferable-credit structure is standard practice but should be confirmed, not assumed.

## 14. Immediate next steps

1. Decide: DIY the GST/Udyam registration now (free, just time), or wait until closer to launch.
2. Get the wallet/credit structure checked by a CA or fintech-focused lawyer (§9.1, §10) — this is the one item that should happen before, not after, writing the payment code.
3. Begin build: Android client + backend skeleton + OpenRouter integration with prompt caching from day one.
4. Treat month 1 post-launch as a measurement exercise for the 60%-consumption assumption in §12 — that number will either validate or break the whole margin model, and real data beats any estimate in this document.
