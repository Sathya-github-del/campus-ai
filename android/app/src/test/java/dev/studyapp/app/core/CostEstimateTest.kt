package dev.studyapp.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEstimateTest {
    @Test
    fun shortQuestionsShowTheServersTypicalFigure() {
        assertEquals(0.20, CostEstimate.forQuestion(0.20, ""), 0.0)
        assertEquals(0.20, CostEstimate.forQuestion(0.20, "what is 2+2?"), 0.0)
        assertEquals(1.60, CostEstimate.forQuestion(1.60, "Explain photosynthesis briefly"), 0.0)
    }

    @Test
    fun longerQuestionsCostVisiblyMore() {
        val base = 0.20
        val medium = CostEstimate.forQuestion(base, "x".repeat(2000))
        val long = CostEstimate.forQuestion(base, "x".repeat(8000))
        assertTrue("$medium", medium > base)
        assertTrue("$long", long > medium)
        // 2000 chars ≈ 670 tokens: input 935 + 1600 output-weight over 1900 → ×1.33.
        assertEquals(0.27, medium, 0.0)
        assertEquals(0.48, long, 0.0)
    }

    @Test
    fun followUpsIncludeTheReSentHistory() {
        val fresh = CostEstimate.forQuestion(0.20, "and then?")
        val followUp = CostEstimate.forQuestion(0.20, "and then?", historyTokens = 900)
        assertTrue(followUp > fresh)
    }

    @Test
    fun tokensFollowTheServersRoughCount() {
        assertEquals(0, CostEstimate.tokens("   "))
        assertEquals(5, CostEstimate.tokens("abc"))
        // Devanagari is 3 bytes a character, so it isn't undercounted.
        assertEquals(2 + 4, CostEstimate.tokens("नम"))
    }

    @Test
    fun subPaisaRatesAreNotRoundedToZero() {
        assertEquals(0.004, CostEstimate.forQuestion(0.004, "hi"), 1e-12)
    }
}
