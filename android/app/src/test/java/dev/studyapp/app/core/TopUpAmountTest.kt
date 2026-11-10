package dev.studyapp.app.core

import dev.studyapp.app.core.TopUpAmount.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopUpAmountTest {
    @Test
    fun accepts() {
        assertEquals(Check.Ok(20), TopUpAmount.check("20"))
        assertEquals(Check.Ok(75), TopUpAmount.check(" ₹75 "))
        assertEquals(Check.Ok(1500), TopUpAmount.check("1,500"))
        assertEquals(Check.Ok(2000), TopUpAmount.check("2000"))
    }

    @Test
    fun rejects() {
        assertTrue(TopUpAmount.check("") is Check.Invalid)
        assertEquals(Check.Invalid("Minimum top-up is ₹20"), TopUpAmount.check("19"))
        assertEquals(Check.Invalid("Maximum top-up is ₹2,000"), TopUpAmount.check("2001"))
        assertTrue(TopUpAmount.check("20.50") is Check.Invalid)
        assertTrue(TopUpAmount.check("-20") is Check.Invalid)
        assertTrue(TopUpAmount.check("99999999999999999999") is Check.Invalid)   // no overflow
    }

    @Test
    fun chipsAreTheAdvertisedOnes() = assertEquals(listOf(20, 50, 100, 200), TopUpAmount.CHIPS)
}
