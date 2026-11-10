package dev.studyapp.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TierTest {
    @Test
    fun fromKeyIsLenientButClosed() {
        assertEquals(Tier.FAST, Tier.fromKey("fast"))
        assertEquals(Tier.EXPERT, Tier.fromKey(" Expert "))
        assertNull(Tier.fromKey("claude-something"))
        assertNull(Tier.fromKey(null))
        assertEquals(Tier.FAST, Tier.DEFAULT)
    }

    @Test
    fun serverListDropsUnknownsDedupesAndOrders() {
        val p = Tiers.fromServer(listOf("expert" to 1.6, "turbo" to 9.0, "fast" to 0.02, "smart" to 0.2, "fast" to 0.5))
        assertEquals(listOf(Tier.FAST, Tier.SMART, Tier.EXPERT), p.map { it.tier })
        assertEquals(0.02, p[0].estCostInr, 0.0)
    }

    @Test
    fun displayLabelsAreNeverModelNames() {
        assertEquals(listOf("Fast", "Smart", "Expert"), Tier.entries.map { it.label })
    }

    @Test
    fun priceLine() {
        assertEquals("Fast · about ₹0.02 per message", Tiers.priceLine(Tier.FAST, 0.02))
        assertEquals("Smart", Tiers.priceLine(Tier.SMART, null))
    }

    @Test
    fun multipleOfFast() {
        val p = Tiers.fromServer(listOf("fast" to 0.02, "expert" to 1.6))
        assertEquals(80, Tiers.multipleOfFast(p, Tier.EXPERT))
        assertNull(Tiers.multipleOfFast(p, Tier.SMART))
        assertNull(Tiers.multipleOfFast(Tiers.fromServer(listOf("fast" to 0.0, "expert" to 1.0)), Tier.EXPERT))
    }
}
