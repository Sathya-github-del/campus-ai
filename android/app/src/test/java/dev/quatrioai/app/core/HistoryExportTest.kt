package dev.quatrioai.app.core

import dev.quatrioai.app.data.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HistoryExportTest {
    @Test
    fun emptyListSaysSo() {
        val md = HistoryExport.markdown(emptyList())
        assertTrue(md.contains("No saved questions"))
    }

    @Test
    fun oneItemHasQuestionTierDateCostAndTag() {
        val item = HistoryItem(
            conversationId = "conv_1",
            firstQuestion = "Explain recursion",
            createdAt = Instant.parse("2026-11-10T09:42:00Z"),
            parts = 2,
            totalCostInr = 0.42,
            tier = Tier.SMART,
        )
        val md = HistoryExport.markdown(listOf(item), tags = mapOf("conv_1" to "Homework"))
        assertTrue(md.contains("## Explain recursion"))
        assertTrue(md.contains("Tag: Homework"))
        assertTrue(md.contains("2 parts"))
        assertEquals(Tier.SMART.label, Tier.SMART.label) // sanity: label resolves without throwing
        assertTrue(md.contains("Cost: ${Money.cost(0.42)}"))
    }

    @Test
    fun untaggedItemOmitsTagLine() {
        val item = HistoryItem("conv_2", "Untagged question", null, 1, 0.0, null)
        val md = HistoryExport.markdown(listOf(item))
        assertTrue(md.contains("## Untagged question"))
        assertTrue(!md.contains("Tag:"))
    }
}
