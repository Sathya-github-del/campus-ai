package dev.quatrioai.app.core

import java.math.BigDecimal
import java.math.RoundingMode

/** Rupee formatting. Pure Kotlin (no Android), so it is covered by JVM unit tests. */
object Money {
    private fun round2(amount: Double): BigDecimal =
        BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)

    /** Balance / exact amounts: always two decimals, e.g. "₹20.00", "₹0.40". Negative is shown as "-₹1.00". */
    fun inr(amount: Double): String {
        val r = round2(amount)
        return if (r.signum() < 0) "-₹" + r.negate().toPlainString() else "₹" + r.toPlainString()
    }

    /** Whole-rupee amounts such as top-up chips: "₹20", "₹1,000" (grouping only kicks in past 999). */
    fun inrWhole(amount: Int): String = "₹" + "%,d".format(java.util.Locale.US, amount)

    /**
     * A per-message cost. Fast-tier messages cost a couple of paise, so plain two-decimal
     * rounding would show "₹0.00" and look free. Anything above zero but under one paisa is
     * shown as "under ₹0.01".
     */
    fun cost(amount: Double): String = when {
        amount <= 0.0 -> "₹0.00"
        amount < 0.005 -> "under ₹0.01"
        else -> inr(amount)
    }

    /** Pre-send estimate wording: "about ₹0.02". */
    fun estimate(amount: Double): String =
        if (amount > 0.0 && amount < 0.005) "under ₹0.01" else "about " + cost(amount)

    /** Roughly how many single questions an amount buys at a per-message estimate (floor, never negative). */
    fun messagesFor(amountInr: Double, perMessageInr: Double): Int {
        if (perMessageInr <= 0.0 || amountInr <= 0.0) return 0
        return BigDecimal.valueOf(amountInr).divide(BigDecimal.valueOf(perMessageInr), 0, RoundingMode.FLOOR).toInt()
    }

    /** "about 1,000" style count for "₹20 ≈ about 1,000 Fast questions". */
    fun count(n: Int): String = "%,d".format(java.util.Locale.US, n)
}
