package dev.studyapp.app.core

/**
 * The three answer levels the user picks between. The backend decides which model serves a
 * tier; the app never shows model names (blueprint §4: this is a product with its own workflow,
 * not a raw model picker).
 */
enum class Tier(val key: String, val label: String, val blurb: String) {
    // [blurb] is the one-line "when to use this" on the tier cards: framed by the kind of question,
    // not by school subject, because the app is for anyone with a quick question.
    FAST("fast", "Fast", "Everyday questions and quick drafts"),
    SMART("smart", "Smart", "Step-by-step thinking, code, careful writing"),
    EXPERT("expert", "Expert", "The genuinely hard stuff. Costs much more");

    companion object {
        val DEFAULT = FAST  // blueprint §7: default everyone to Fast
        fun fromKey(key: String?): Tier? = entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

/** A tier with the backend's current per-message estimate. */
data class TierPrice(val tier: Tier, val estCostInr: Double)

object Tiers {
    /**
     * Turns the /v1/models list into display data: unknown tier keys are dropped (so a server-side
     * experiment can't leak a raw name into the UI), duplicates keep the first, ordered Fast → Expert.
     */
    fun fromServer(items: List<Pair<String, Double>>): List<TierPrice> =
        items.mapNotNull { (key, cost) -> Tier.fromKey(key)?.let { TierPrice(it, cost) } }
            .distinctBy { it.tier }
            .sortedBy { it.tier.ordinal }

    /** "Fast · about ₹0.02 per message", or just the label when the price isn't known yet. */
    fun priceLine(tier: Tier, est: Double?): String =
        if (est == null) tier.label else "${tier.label} · ${Money.estimate(est)} per message"

    /** How many times pricier [tier] is than Fast, rounded, for the Expert warning ("about 80× Fast"). */
    fun multipleOfFast(prices: List<TierPrice>, tier: Tier): Int? {
        val fast = prices.firstOrNull { it.tier == Tier.FAST }?.estCostInr ?: return null
        val t = prices.firstOrNull { it.tier == tier }?.estCostInr ?: return null
        if (fast <= 0.0) return null
        return Math.round(t / fast).toInt()
    }
}
