package dev.straightanswer.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Wording for history rows. Pure Kotlin so it is covered by JVM tests. */
object HistoryText {
    private val SAME_YEAR = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
    private val OTHER_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** "Today, 3:42 pm", "Yesterday, 9:05 am", "4 Nov, 6:10 pm", "28 Dec 2025"; empty when unknown. */
    fun date(at: Instant?, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
        if (at == null) return ""
        val t = ZonedDateTime.ofInstant(at, zone)
        val today = ZonedDateTime.ofInstant(now, zone).toLocalDate()
        val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(t).lowercase()
        return when (t.toLocalDate()) {
            today -> "Today, $time"
            today.minusDays(1) -> "Yesterday, $time"
            else -> if (t.year == today.year) SAME_YEAR.format(t).replace("AM", "am").replace("PM", "pm") else OTHER_YEAR.format(t)
        }
    }

    /** "1 part" / "3 parts". */
    fun parts(n: Int): String = if (n == 1) "1 part" else "$n parts"

    /** The row text when the server sent no question (shouldn't happen, but never show an empty row). */
    fun question(q: String): String = q.ifBlank { "(Question not available)" }
}
