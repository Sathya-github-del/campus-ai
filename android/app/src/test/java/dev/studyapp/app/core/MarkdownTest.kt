package dev.studyapp.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun spans(s: String) = Markdown.inline(s)

    @Test
    fun boldItalicCodeAndStrike() {
        assertEquals(listOf(Span("a "), Span("bold", bold = true), Span(" word")), spans("a **bold** word"))
        assertEquals(listOf(Span("an "), Span("aside", italic = true)), spans("an *aside*"))
        assertEquals(listOf(Span("both", bold = true, italic = true)), spans("***both***"))
        assertEquals(listOf(Span("run "), Span("pip install x", code = true)), spans("run `pip install x`"))
        assertEquals(listOf(Span("old", strike = true)), spans("~~old~~"))
        assertEquals(listOf(Span("x", bold = true), Span(" and "), Span("y", italic = true)), spans("__x__ and _y_"))
    }

    @Test
    fun notEmphasis() {
        // Multiplication, snake_case and unmatched markers stay as typed.
        assertEquals("2 * 3 * 4", Markdown.plain(spans("2 * 3 * 4")))
        assertEquals("snake_case_name", Markdown.plain(spans("snake_case_name")))
        assertEquals("a ** b", Markdown.plain(spans("a ** b")))
        assertEquals("*", Markdown.plain(spans("\\*")))
        // Code keeps its characters, including ** and backslashes.
        assertEquals(listOf(Span("a**b\\n", code = true)), spans("`a**b\\n`"))
    }

    @Test
    fun linksAndPlainExponents() {
        val s = spans("see [the docs](https://example.com/x) now")
        assertEquals(Span("the docs", link = "https://example.com/x"), s[1])
        val e = spans("x^2 + 10^-3")
        assertEquals(Span("2", script = Script.SUP), e[1])
        assertEquals(Span("−3", script = Script.SUP), e[3])
        assertEquals("x² + 10⁻³", Markdown.plain(e))
        assertEquals("^_^", Markdown.plain(spans("^_^")))
    }

    @Test
    fun inlineMathInAllItsForms() {
        assertEquals("area = πr²", Markdown.plain(spans("area = \\(\\pi r^2\\)")))
        assertEquals("area = πr²", Markdown.plain(spans("area = \$\\pi r^2\$")))
        assertEquals("so 3 × 4 ≈ 12", Markdown.plain(spans("so 3 \\times 4 \\approx 12")))
        assertEquals("half is ½ here", Markdown.plain(spans("half is \\frac{1}{2} here")))
        // Money is not maths.
        assertEquals("costs \$5 and \$10", Markdown.plain(spans("costs \$5 and \$10")))
        val m = spans("\\(x\\)")
        assertTrue(m.single().math && m.single().italic)
    }

    @Test
    fun blocks() {
        val b = Markdown.parse(
            """
            # Title
            Some *text*
            continues here.

            - one
            - two
              - nested
            1. first
            2) second

            ---
            > quoted
            ```python
            print("hi")  # **not bold**
            ```
            | A | B |
            |---|:-:|
            | 1 | **2** |
            """.trimIndent(),
        )
        assertEquals(MdBlock.Heading(1, listOf(Span("Title"))), b[0])
        assertEquals(MdBlock.Paragraph(listOf(Span("Some "), Span("text", italic = true), Span("\ncontinues here."))), b[1])
        assertEquals(MdBlock.Item(false, "", 0, listOf(Span("one"))), b[2])
        assertEquals(MdBlock.Item(false, "", 1, listOf(Span("nested"))), b[4])
        assertEquals(MdBlock.Item(true, "1.", 0, listOf(Span("first"))), b[5])
        assertEquals(MdBlock.Item(true, "2.", 0, listOf(Span("second"))), b[6])
        assertEquals(MdBlock.Rule, b[7])
        assertEquals(MdBlock.Quote(listOf(Span("quoted"))), b[8])
        assertEquals(MdBlock.Code("print(\"hi\")  # **not bold**", "python"), b[9])
        val t = b[10] as MdBlock.Table
        assertEquals(listOf(listOf(Span("A")), listOf(Span("B"))), t.header)
        assertEquals(listOf(Span("2", bold = true)), t.rows[0][1])
        assertEquals(11, b.size)
    }

    @Test
    fun displayMathInEveryDelimiterStyle() {
        val forms = listOf(
            "\\[ x = \\frac{a}{b} \\]",
            "\\[\nx = \\frac{a}{b}\n\\]",
            "$$ x = \\frac{a}{b} $$",
            "$$\nx = \\frac{a}{b}\n$$",
            "[ x = \\frac{a}{b} ]",
            "[\nx = \\frac{a}{b}\n]",
        )
        forms.forEach { f ->
            val b = Markdown.parse("Before\n\n$f\n\nAfter")
            assertEquals(f, 3, b.size)
            val m = b[1] as MdBlock.Math
            assertEquals(f, "x = a/b", Latex.plain(m.node))
        }
        val env = Markdown.parse("\\begin{aligned}\nx &= 1 \\\\\ny &= 2\n\\end{aligned}")
        assertEquals("x = 1\ny = 2", Latex.plain((env.single() as MdBlock.Math).node))
        // A link-like "[ see above ]" line is not maths.
        assertTrue(Markdown.parse("[ see above ]").single() is MdBlock.Paragraph)
    }

    private fun assertClean(plain: String) {
        listOf("\\", "**", "{", "}", "$", "###").forEach { assertFalse("'$it' left in:\n$plain", plain.contains(it)) }
    }

    @Test
    fun realQuadraticAnswer() {
        val b = Markdown.parse(RealAnswers.QUADRATIC)
        assertTrue(b[1] is MdBlock.Math)
        assertTrue(Latex.isStructured((b[1] as MdBlock.Math).node))
        assertEquals(MdBlock.Heading(3, listOf(Span("Step 1: Identify the coefficients"))), b[2])
        assertEquals(3, b.count { it is MdBlock.Heading })
        assertEquals(3, b.count { it is MdBlock.Math })
        assertEquals(listOf("", "", "", "1.", "2."), b.filterIsInstance<MdBlock.Item>().map { it.marker })
        assertTrue(b.contains(MdBlock.Rule))

        val plain = Markdown.plainText(RealAnswers.QUADRATIC)
        assertClean(plain)
        val lines = plain.lines()
        assertEquals("To solve the quadratic equation 2x² + 3x − 2 = 0, we can use the quadratic formula:", lines[0])
        assertTrue(plain, "x = (−b ± √(b² − 4ac))/(2a)" in lines)
        assertTrue(plain, "• c = −2" in lines)
        assertTrue(plain, "b² − 4ac = 3² − 4 × 2 × (−2) = 9 + 16 = 25" in lines)
        assertTrue(plain, "x = (−3 ± √25)/(2 × 2) = (−3 ± 5)/4" in lines)
        assertTrue(plain, "1. x₁ = (−3 + 5)/4 = 2/4 = ½" in lines)
        assertTrue(plain, "2. x₂ = (−3 − 5)/4 = −8/4 = −2" in lines)
        assertEquals("So the roots are x = ½ and x = −2.", lines.last())
        // The bold around the inline maths is kept as styling, not shown as asterisks.
        val last = (b.last() as MdBlock.Paragraph).spans
        assertTrue(last.filter { it.math }.all { it.bold })
    }

    @Test
    fun realProjectileAnswer() {
        val b = Markdown.parse(RealAnswers.PROJECTILE)
        assertEquals(MdBlock.Paragraph(listOf(Span("Given:", bold = true))), b[0])
        assertEquals(3, b.count { it is MdBlock.Math })
        b.filterIsInstance<MdBlock.Math>().forEach { assertTrue(Latex.isStructured(it.node)) }

        val plain = Markdown.plainText(RealAnswers.PROJECTILE)
        assertClean(plain)
        val lines = plain.lines()
        assertTrue(plain, "• Initial velocity u = 20 m/s" in lines)
        assertTrue(plain, "• Angle of projection θ = 30°" in lines)
        assertTrue(plain, "• g = 9.8 m/s²" in lines)
        assertTrue(plain, "T = (2u sin θ)/g = (2 × 20 × sin 30°)/9.8 = 20/9.8 ≈ 2.04 s" in lines)
        assertTrue(plain, "H = (u² sin²θ)/(2g) = (400 × 0.25)/19.6 ≈ 5.10 m" in lines)
        assertTrue(plain, "R = (u² sin 2θ)/g = (400 × √3/2)/9.8 ≈ 35.35 m" in lines)
        assertEquals("So the ball stays in the air for about 2.04 s, rises 5.10 m and lands 35.35 m away.", lines.last())
    }

    @Test
    fun plainTextOfOrdinaryAnswersIsUnchangedText() {
        assertEquals("Hello there. No markdown here!", Markdown.plainText("Hello there. No markdown here!"))
        assertEquals("C:\\new folder", Markdown.plainText("C:\\new folder"))
        assertEquals("Use the sum ∑ symbol", Markdown.plainText("Use the sum \\sum symbol"))
    }
}
