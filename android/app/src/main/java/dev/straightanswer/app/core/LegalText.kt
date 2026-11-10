package dev.straightanswer.app.core

/**
 * DRAFT Terms of Service and Privacy Policy, written in plain language to say honestly what the app
 * does today. NOT reviewed by a lawyer. Every screen that shows them says so, and the bracketed
 * [TO CONFIRM] notes mark facts that depend on decisions or backend details not settled yet.
 * Replace this whole file with reviewed text before a public launch (see README "Known gaps").
 */
enum class LegalDoc(val route: String, val title: String) {
    TERMS("terms", "Terms of Service"),
    PRIVACY("privacy", "Privacy Policy");

    companion object {
        fun fromRoute(r: String?): LegalDoc = entries.firstOrNull { it.route == r } ?: TERMS
    }
}

data class LegalSection(val heading: String, val body: String)

object LegalText {
    const val DRAFT_NOTICE =
        "Draft, not final. This text has not been reviewed by a lawyer yet and may change before launch. " +
            "It describes honestly how the app works today."
    const val LAST_UPDATED = "Draft of 10 November 2026"

    fun sections(doc: LegalDoc): List<LegalSection> = when (doc) {
        LegalDoc.TERMS -> TERMS
        LegalDoc.PRIVACY -> PRIVACY
    }

    private val TERMS = listOf(
        LegalSection(
            "What this is",
            "Straight Answer gives you AI-written answers to questions you type or photograph. You pay for each " +
                "answer from a prepaid balance. There is no subscription and nothing renews or is charged automatically.",
        ),
        LegalSection(
            "Using it without an account",
            "You can use the app as a guest. Your balance is then tied to this phone only: uninstalling the app, " +
                "clearing its data or switching to an account on this phone loses access to it, and we can't recover it. " +
                "Creating an account lets you log in again on this or another phone.",
        ),
        LegalSection(
            "Your balance is prepaid credit, not money",
            "Money you add becomes credit for answers in this app. It is non-transferable: it can't be sent to " +
                "another person or account, moved out of the app, withdrawn or paid out as cash, and it doesn't earn " +
                "interest. Unused credit is not refunded, except where the law requires it or a payment failed and " +
                "was charged in error. [TO CONFIRM: refund rules and any credit expiry, pending the RBI prepaid " +
                "payment instrument review.]",
        ),
        LegalSection(
            "What an answer costs",
            "Each tier shows an estimated price before you ask. The exact charge depends on the length of your " +
                "question and the answer, is shown on the answer, and is taken from your balance. Follow-ups cost a " +
                "little more because the earlier parts of the conversation are sent again. If a request fails, " +
                "nothing is charged.",
        ),
        LegalSection(
            "AI answers can be wrong",
            "Answers are generated automatically by third-party AI models and may be incomplete, out of date or " +
                "simply wrong. Check anything important, and don't rely on answers for medical, legal, financial or " +
                "safety decisions without asking a qualified person.",
        ),
        LegalSection(
            "Fair use",
            "Don't use the app to break the law, to harm or harass anyone, to create content that sexualises " +
                "minors, or to try to break, overload or misuse the service. We may limit or close an account that " +
                "does. [TO CONFIRM: what happens to a remaining balance if an account is closed for misuse.]",
        ),
        LegalSection(
            "Deleting your account",
            "You can delete your account at any time from Profile. This is permanent: your history is deleted and " +
                "any remaining balance is forfeited, because it is non-transferable credit and there is no way to " +
                "pay it out.",
        ),
        LegalSection(
            "Changes and contact",
            "If these terms change in a way that matters, the app will tell you before the change applies. " +
                "These terms are governed by the laws of India. Questions: [TO CONFIRM: support email address].",
        ),
    )

    private val PRIVACY = listOf(
        LegalSection(
            "The short version",
            "We collect only what the app needs to work. We do not sell your data, and we don't share your " +
                "account details with anyone for advertising. There are no ads and no third-party tracking in the app.",
        ),
        LegalSection(
            "What we keep",
            "If you create an account: your email address and your password, which the server should only ever " +
                "store as a one-way hash, never as readable text [TO CONFIRM with the backend]. For everyone: a " +
                "random sign-in token, your balance and top-ups, and your questions and answers with their cost, so " +
                "the app can show your history and charge the right fare. Guests have no email on file.",
        ),
        LegalSection(
            "Who processes your questions",
            "To write an answer, the text of your question (and, for a follow-up, the earlier parts of that " +
                "conversation) is sent to OpenRouter, which passes it to the AI model provider that serves the tier " +
                "you picked. They process it to generate the answer under their own terms and privacy policies. " +
                "Please don't put passwords, ID numbers or other sensitive personal details in questions. " +
                "[TO CONFIRM: OpenRouter and provider data-retention settings.]",
        ),
        LegalSection(
            "Photos stay on your phone",
            "When you snap a question, the text is read on your phone. The photo itself is not uploaded; only the " +
                "text you choose to send is.",
        ),
        LegalSection(
            "Payments",
            "Top-ups are handled by a payment provider. We receive whether a payment succeeded and its amount, " +
                "never your UPI PIN or card details. [TO CONFIRM: payment provider name once integrated.]",
        ),
        LegalSection(
            "Your choices",
            "You can delete any conversation from your history in Profile, or delete your whole account, which " +
                "removes your email, history and balance from our systems. [TO CONFIRM: how long deleted data " +
                "lingers in backups, and any records we must keep by law, e.g. payment records.]",
        ),
        LegalSection(
            "Security",
            "The app talks to our server only over an encrypted connection (HTTPS). Your sign-in token is kept " +
                "in the app's private storage on your phone and is excluded from device backups.",
        ),
        LegalSection(
            "Children",
            "[TO CONFIRM: minimum age and parental consent, as required for users under 18 by India's Digital " +
                "Personal Data Protection Act, 2023.]",
        ),
        LegalSection(
            "Contact",
            "Questions or requests about your data: [TO CONFIRM: privacy contact email and grievance officer].",
        ),
    )
}
