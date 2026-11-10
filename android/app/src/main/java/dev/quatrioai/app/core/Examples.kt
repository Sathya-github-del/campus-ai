package dev.quatrioai.app.core

/**
 * An example question for the home screen. [text] is what the card shows; tapping it puts [fill]
 * in the box. For complete questions they are the same; for "paste your thing here" ones, [fill]
 * is an instruction that ends where the user pastes or types their own text.
 */
data class Example(val text: String, val fill: String = text)

/**
 * Example prompts that replace the old Math / Physics / Essay subject chips. They are deliberately
 * not labelled by subject: the range of the text itself (homework, code, emails, contracts, travel,
 * plain curiosity) is what tells a first-time user "you can ask this anything".
 */
object Examples {
    /** Ordered so that every page of [PAGE_SIZE] mixes schoolwork, work/life admin, code and curiosity. */
    val ALL: List<Example> = listOf(
        Example("A ball is thrown straight up at 20 m/s. How high does it go?"),
        Example("Help me reply to this email and politely say no", "Help me write a short, polite reply to this email saying no:\n\n"),
        Example("Why does my Python loop skip items when I remove from the list?"),
        Example("Why do onions make your eyes water?"),

        Example("What does this contract clause actually mean?", "Explain this clause in plain words. What am I agreeing to?\n\n"),
        Example("Solve 2x² − 7x + 3 = 0 step by step"),
        Example("Plan 3 days in Jaipur on a ₹10,000 budget"),
        Example("Write a SQL query for the top 5 customers by number of orders"),

        Example("Explain photosynthesis like I'm 12"),
        Example("Turn my notes into a short WhatsApp update for the team", "Turn these notes into a short WhatsApp update for my team:\n\n"),
        Example("What's the difference between an FD and an RD?"),
        Example("Fix the grammar in my paragraph but keep my tone", "Fix the grammar in this, but keep my tone:\n\n"),
    )

    const val PAGE_SIZE = 4

    /** The examples shown at rotation [step]: consecutive pages of the pool, wrapping around forever. */
    fun page(step: Int, size: Int = PAGE_SIZE, pool: List<Example> = ALL): List<Example> {
        if (pool.isEmpty() || size <= 0) return emptyList()
        val n = minOf(size, pool.size)
        val start = Math.floorMod(step.toLong() * n, pool.size.toLong()).toInt()
        return List(n) { pool[(start + it) % pool.size] }
    }
}
