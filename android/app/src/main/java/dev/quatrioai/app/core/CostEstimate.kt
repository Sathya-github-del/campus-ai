package dev.quatrioai.app.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

/**
 * A live "about ₹X" for the question being typed, built only from what GET /v1/models gives: one
 * flat per-message estimate per tier. The backend computes that figure for a typical message of
 * [BASE_PROMPT_TOKENS] input tokens (its own system prompt is most of that) and
 * [BASE_COMPLETION_TOKENS] output tokens. We don't get the per-token prices, so the split between
 * input and output cost is assumed: output tokens cost about [OUTPUT_PRICE_RATIO]x input tokens,
 * which is typical for the models the tiers route to.
 *
 * So: a short question shows the server's typical figure (it's the floor); a long or pasted question
 * adds its extra input tokens at the inferred input price; a follow-up also adds the earlier parts the
 * server re-sends. The answer's length is unknown before sending, so output stays at the typical 400
 * tokens. The exact charge still only lands with the answer.
 */
object CostEstimate {
    const val BASE_PROMPT_TOKENS = 300
    const val BASE_COMPLETION_TOKENS = 400
    const val OUTPUT_PRICE_RATIO = 4.0
    /** The server's system prompt and framing, sent with every message (~265 tokens by its own estimator). */
    const val OVERHEAD_TOKENS = 265

    /** Same rough count the server uses: UTF-8 bytes / 3 (+4), so Hindi/Devanagari isn't undercounted. */
    fun tokens(text: String): Int = if (text.isBlank()) 0 else text.trim().toByteArray(Charsets.UTF_8).size / 3 + 4

    /**
     * The tier's typical estimate [baseInr] scaled to this question ([text]) plus [historyTokens] of
     * earlier parts re-sent with a follow-up. Rounded to the paisa (sub-paisa amounts kept as they are),
     * so the meter only ticks when the shown figure actually changes.
     */
    fun forQuestion(baseInr: Double, text: String, historyTokens: Int = 0): Double {
        if (baseInr <= 0.0) return baseInr
        val input = max(BASE_PROMPT_TOKENS, OVERHEAD_TOKENS + tokens(text) + historyTokens)
        val typicalWeight = BASE_PROMPT_TOKENS + OUTPUT_PRICE_RATIO * BASE_COMPLETION_TOKENS
        val est = baseInr * (input + OUTPUT_PRICE_RATIO * BASE_COMPLETION_TOKENS) / typicalWeight
        return if (est < 0.005) est else BigDecimal.valueOf(est).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
}
