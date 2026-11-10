package dev.quatrioai.app.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HistoryTextTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-10T12:00:00Z") // 17:30 IST

    @Test
    fun relativeDays() {
        assertEquals("Today, 3:12 pm", HistoryText.date(Instant.parse("2026-11-10T09:42:00Z"), ist, now))
        assertEquals("Yesterday, 9:05 am", HistoryText.date(Instant.parse("2026-11-09T03:35:00Z"), ist, now))
        assertEquals("4 Nov, 6:10 pm", HistoryText.date(Instant.parse("2026-11-04T12:40:00Z"), ist, now))
        assertEquals("28 Dec 2025", HistoryText.date(Instant.parse("2025-12-28T10:00:00Z"), ist, now))
        assertEquals("", HistoryText.date(null, ist, now))
    }

    @Test
    fun usesTheLocalCalendarDay() {
        // 20:00 UTC on the 9th is already 01:30 on the 10th in India.
        assertEquals("Today, 1:30 am", HistoryText.date(Instant.parse("2026-11-09T20:00:00Z"), ist, now))
    }

    @Test
    fun partsAndMissingQuestion() {
        assertEquals("1 part", HistoryText.parts(1))
        assertEquals("3 parts", HistoryText.parts(3))
        assertEquals("(Question not available)", HistoryText.question("  "))
    }
}
