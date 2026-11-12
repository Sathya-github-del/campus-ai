package dev.quatrioai.app.core

/** Static copy for the About screen. */
object AboutText {
    const val TAGLINE = "Pay for the answer, not the month."
    const val OWNER_PLACEHOLDER = "Developed by Sathya Prakash J · © 2026"

    val SECTIONS = listOf(
        LegalSection(
            "What QUATRIO AI is",
            "QUATRIO AI gives you AI-written answers to questions you type or photograph. You pay only for " +
                "the answers you ask for, from a prepaid balance — there's no subscription and nothing renews " +
                "or is charged automatically.",
        ),
        LegalSection(
            "How it's priced",
            "Every tier shows its rate before you ask, and the exact fare lands with the answer. Fast, Smart " +
                "and Expert trade thinking depth for cost, so you only pay more when a question actually needs it.",
        ),
    )
}
