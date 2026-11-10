package dev.straightanswer.app.core

/** Top-up amount rules: whole rupees, ₹20 minimum (blueprint §8), capped to keep the wallet small. */
object TopUpAmount {
    const val MIN = 20
    /** Small closed-loop wallet; also stays under the ₹2,000 UPI MDR threshold (blueprint §5). */
    const val MAX = 2000
    val CHIPS = listOf(20, 50, 100, 200)

    sealed interface Check {
        data class Ok(val amount: Int) : Check
        data class Invalid(val reason: String) : Check
    }

    fun check(raw: String): Check {
        val s = raw.trim().removePrefix("₹").trim().replace(",", "")
        if (s.isEmpty()) return Check.Invalid("Enter an amount")
        if (!s.all { it.isDigit() }) return Check.Invalid("Use whole rupees only, like 30")
        val n = s.toBigInteger()
        return when {
            n < MIN.toBigInteger() -> Check.Invalid("Minimum top-up is ${Money.inrWhole(MIN)}")
            n > MAX.toBigInteger() -> Check.Invalid("Maximum top-up is ${Money.inrWhole(MAX)}")
            else -> Check.Ok(n.toInt())
        }
    }
}
