package dev.straightanswer.app.core

import dev.straightanswer.app.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TextHelpersTest {
    @Test
    fun examplePagesRotateThroughTheWholePool() {
        val n = Examples.ALL.size
        assertEquals(Examples.PAGE_SIZE, Examples.page(0).size)
        assertEquals(Examples.ALL.take(Examples.PAGE_SIZE), Examples.page(0))
        // Paging covers every example, then wraps around (also for negative steps).
        val seen = (0 until n).flatMap { Examples.page(it) }.toSet()
        assertEquals(Examples.ALL.toSet(), seen)
        assertEquals(Examples.page(0), Examples.page(n / Examples.PAGE_SIZE))
        assertEquals(Examples.page(n / Examples.PAGE_SIZE - 1), Examples.page(-1))
        assertTrue(Examples.page(3, pool = emptyList()).isEmpty())
        assertEquals(2, Examples.page(5, size = 4, pool = Examples.ALL.take(2)).size)
    }

    @Test
    fun examplesAreGeneralNotSubjectLabels() {
        // The old chip row was "Math / Physics / Essay help". Examples are real questions, not labels.
        assertTrue(Examples.ALL.size >= 8)
        assertTrue(Examples.ALL.all { it.text.length > 20 && it.fill.isNotBlank() })
        assertTrue(Examples.ALL.none { it.text in setOf("Math", "Physics", "Coding", "Essay help", "English doubt") })
        // Paste-style examples end where the user's own text goes.
        Examples.ALL.filter { it.fill != it.text }.forEach { assertTrue(it.fill.endsWith("\n")) }
    }

    @Test
    fun ocrCleanup() {
        assertEquals("Find x if\n2x + 3 = 11", OcrText.clean("  Find   x if \r\n 2x +\t3 = 11  \n\n\n\n"))
        assertEquals("a\n\nb", OcrText.clean("a\n\n\n\nb"))
        assertEquals("", OcrText.clean(" \n \n"))
        val long = OcrText.clean("x".repeat(OcrText.MAX_CHARS + 50))
        assertEquals(OcrText.MAX_CHARS + 1, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun ocrAppends() {
        assertEquals("q", OcrText.appendTo("", "q"))
        assertEquals("Solve:\nq", OcrText.appendTo("Solve:\n", "q"))
        assertEquals("note\nq", OcrText.appendTo("note  ", "q"))
        assertEquals("note", OcrText.appendTo("note", " "))
    }

    @Test
    fun friendlyErrors() {
        assertEquals("Your balance is too low for this. Top up to continue.", ErrorText.of(ApiException(402, "x")))
        assertTrue(ErrorText.of(ApiException(503, "upstream")).contains("our side"))
        assertEquals("tier not allowed", ErrorText.of(ApiException(400, "tier not allowed")))
        assertTrue(ErrorText.of(ApiException(400, "HTTP 400")).startsWith("Something went wrong"))
        assertTrue(ErrorText.of(UnknownHostException("h")).startsWith("Can't reach"))
        assertTrue(ErrorText.of(SocketTimeoutException()).contains("too long"))
    }
}
