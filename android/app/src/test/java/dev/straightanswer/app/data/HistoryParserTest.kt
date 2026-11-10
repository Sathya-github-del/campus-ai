package dev.straightanswer.app.data

import dev.straightanswer.app.core.Tier
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HistoryParserTest {
    @Test
    fun parsesTheContractShape() {
        val page = HistoryParser.parse(
            """{"items":[{"conversation_id":"c1","first_question":"What is 2+2?","created_at":"2026-11-10T09:30:00Z",
               "parts":3,"total_cost_inr":0.12,"tier":"smart"}]}""",
        )
        val i = page.items.single()
        assertEquals("c1", i.conversationId)
        assertEquals("What is 2+2?", i.firstQuestion)
        assertEquals(Instant.parse("2026-11-10T09:30:00Z"), i.createdAt)
        assertEquals(3, i.parts)
        assertEquals(0.12, i.totalCostInr, 1e-9)
        assertEquals(Tier.SMART, i.tier)
        assertTrue(page.unknownFields.isEmpty())
        assertEquals(0, page.skipped)
    }

    @Test
    fun toleratesRenamedFieldsStringNumbersAndExtras() {
        val page = HistoryParser.parse(
            """{"history":[{"id":"c9","question":" Draft a reply ","createdAt":1731231000,"message_count":"2",
               "cost_inr":"1.5","tier":"EXPERT","model":"secret/model","pinned":false}]}""",
        )
        val i = page.items.single()
        assertEquals("c9", i.conversationId)
        assertEquals("Draft a reply", i.firstQuestion)
        assertEquals(Instant.ofEpochSecond(1731231000), i.createdAt)
        assertEquals(2, i.parts)
        assertEquals(1.5, i.totalCostInr, 1e-9)
        assertEquals(Tier.EXPERT, i.tier)
        assertEquals(setOf("model", "pinned"), page.unknownFields)
    }

    @Test
    fun acceptsABareArrayAndNullList() {
        assertEquals(1, HistoryParser.parse("""[{"conversation_id":"a","first_question":"q"}]""").items.size)
        assertTrue(HistoryParser.parse("""{"items":null}""").items.isEmpty())
        assertTrue(HistoryParser.parse("""{"items":[]}""").items.isEmpty())
    }

    @Test
    fun missingOptionalFieldsGetSafeDefaultsAndUnknownTierIsDropped() {
        val i = HistoryParser.parse("""{"items":[{"conversation_id":"x","tier":"gpt-9-raw","created_at":null}]}""").items.single()
        assertEquals("", i.firstQuestion)
        assertNull(i.createdAt)
        assertEquals(1, i.parts)
        assertEquals(0.0, i.totalCostInr, 0.0)
        assertNull(i.tier)
    }

    @Test
    fun itemsWithoutAnIdAreSkippedNotFatal() {
        val page = HistoryParser.parse("""{"items":[{"first_question":"no id"},"junk",{"conversation_id":"ok"}]}""")
        assertEquals(listOf("ok"), page.items.map { it.conversationId })
        assertEquals(2, page.skipped)
    }

    @Test
    fun partsMayBeAListAndDatesMayLackAZoneOrBeMillis() {
        val i = HistoryParser.parse(
            """{"items":[{"conversation_id":"m","messages":[{},{},{},{}],"created_at":"2026-11-10 09:30:00"}]}""",
        ).items.single()
        assertEquals(4, i.parts)
        assertEquals(Instant.parse("2026-11-10T09:30:00Z"), i.createdAt)
        assertEquals(Instant.ofEpochMilli(1731231000123), HistoryParser.instant(JsonPrimitive(1731231000123)))
        assertEquals(Instant.parse("2026-11-10T04:00:00Z"), HistoryParser.instant(JsonPrimitive("2026-11-10T09:30:00+05:30")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aResponseWithNoListIsAnError() {
        HistoryParser.parse("""{"detail":"nope"}""")
    }
}
