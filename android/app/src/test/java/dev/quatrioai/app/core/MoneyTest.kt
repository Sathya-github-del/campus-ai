package dev.quatrioai.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test
    fun balances() {
        assertEquals("₹20.00", Money.inr(20.0))
        assertEquals("₹17.38", Money.inr(17.375))   // half-up, no binary-float surprise
        assertEquals("₹0.40", Money.inr(0.4))
        assertEquals("₹0.00", Money.inr(0.0))
        assertEquals("-₹1.50", Money.inr(-1.5))
        assertEquals("₹1234.50", Money.inr(1234.5))
    }

    @Test
    fun wholeRupees() {
        assertEquals("₹20", Money.inrWhole(20))
        assertEquals("₹200", Money.inrWhole(200))
        assertEquals("₹2,000", Money.inrWhole(2000))
    }

    @Test
    fun tinyCostsNeverLookFree() {
        assertEquals("₹0.02", Money.cost(0.02))
        assertEquals("₹0.02", Money.cost(0.018))
        assertEquals("₹0.01", Money.cost(0.005))
        assertEquals("under ₹0.01", Money.cost(0.004))
        assertEquals("₹0.00", Money.cost(0.0))
        assertEquals("₹1.60", Money.cost(1.6))
    }

    @Test
    fun estimates() {
        assertEquals("about ₹0.02", Money.estimate(0.02))
        assertEquals("about ₹1.60", Money.estimate(1.6))
        assertEquals("under ₹0.01", Money.estimate(0.001))
    }

    @Test
    fun messagesForAmount() {
        assertEquals(1000, Money.messagesFor(20.0, 0.02))
        assertEquals(12, Money.messagesFor(20.0, 1.6))
        assertEquals(0, Money.messagesFor(20.0, 0.0))
        assertEquals(0, Money.messagesFor(0.0, 0.02))
        assertEquals(3, Money.messagesFor(0.3, 0.1))   // exact decimal division, not 2.9999…
        assertEquals("1,000", Money.count(1000))
    }
}
