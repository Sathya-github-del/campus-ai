package dev.straightanswer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterTextTest {
    @Test
    fun twoDecimalsAndLeadingUnlitCells() {
        assertEquals("0.02", MeterText.of(0.02))
        assertEquals("19.98", MeterText.of(19.98))
        assertEquals("  19.98", MeterText.of(19.98, minIntDigits = 4))
        assertEquals("   0.00", MeterText.of(0.0, minIntDigits = 4))
        assertEquals("2000.00", MeterText.of(2000.0, minIntDigits = 4))
        assertEquals("12345.00", MeterText.of(12345.0, minIntDigits = 4)) // grows, never truncates
        assertEquals("1.60", MeterText.of(1.6))
        assertEquals("0.01", MeterText.of(0.005))
    }

    @Test
    fun negativeAndSubPaisa() {
        assertEquals("-1.00", MeterText.of(-1.0))
        assertEquals(" -1.00", MeterText.of(-1.0, minIntDigits = 3))
        assertEquals("0.00", MeterText.of(0.001))
        assertTrue(MeterText.isSubPaisa(0.001))
        assertFalse(MeterText.isSubPaisa(0.0))
        assertFalse(MeterText.isSubPaisa(0.02))
    }
}
