package dev.straightanswer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexTest {
    private fun p(src: String) = Latex.plain(src)

    @Test
    fun commonOperatorsBecomeSymbols() {
        assertEquals("3 × 4 ≈ 12", p("3 \\times 4 \\approx 12"))
        assertEquals("a · b ÷ c ± d", p("a \\cdot b \\div c \\pm d"))
        assertEquals("x ≤ 5 ⇒ y ≥ 2", p("x \\leq 5 \\Rightarrow y \\geq 2"))
        assertEquals("θ = 30°", p("\\theta = 30^\\circ"))
        assertEquals("Δx → 0", p("\\Delta x \\to 0"))
    }

    @Test
    fun exponentsAndSubscriptsUseUnicodeWhenTheyCan() {
        assertEquals("x² + 3x − 4 = 0", p("x^2 + 3x - 4 = 0"))
        assertEquals("v₀² = u²", p("v_0^2 = u^2"))
        assertEquals("eⁿ⁺¹", p("e^{n+1}"))
        assertEquals("10⁻³", p("10^{-3}"))
        // No Unicode superscript for every character: fall back to a readable ^(...).
        assertEquals("e^(−x²/2)", p("e^{-x^2/2}"))
        assertEquals("xₘₐₓ", p("x_{max}"))
        assertEquals("ab²", p("ab^2"))
    }

    @Test
    fun fractionsReadAsSlashesWithBracketsOnlyWhenNeeded() {
        assertEquals("½", p("\\frac{1}{2}"))
        assertEquals("¾ m", p("\\frac{3}{4}\\,\\text{m}"))
        assertEquals("a/b", p("\\frac{a}{b}"))
        assertEquals("(a + b)/c", p("\\frac{a+b}{c}"))
        // "2a" is fine on top but needs brackets underneath: x/(2a), not x/2a.
        assertEquals("(−b ± √(b² − 4ac))/(2a)", p("\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"))
        assertEquals("20/9.8", p("\\dfrac{20}{9.8}"))
        assertEquals("C(n, k)", p("\\binom{n}{k}"))
    }

    @Test
    fun textUnitsAndFunctionsStayUpright() {
        assertEquals("u = 20 m/s", p("u = 20 \\, \\text{m/s}"))
        assertEquals("g = 9.8 m/s²", p("g = 9.8 \\, \\text{m/s}^2"))
        assertEquals("sin θ", p("\\sin\\theta"))
        assertEquals("sin 30°", p("\\sin 30^\\circ"))
        assertEquals("u² sin 2θ", p("u^2 \\sin 2\\theta"))
        assertEquals("F = ma", p("\\mathrm{F} = ma"))
        assertEquals("x ∈ ℝ", p("x \\in \\mathbb{R}"))
    }

    @Test
    fun radicalsAccentsAndBoxes() {
        assertEquals("√2", p("\\sqrt{2}"))
        assertEquals("√(x + 1)", p("\\sqrt{x+1}"))
        assertEquals("∛8 = 2", p("\\sqrt[3]{8} = 2"))
        assertEquals("v\u20D7", p("\\vec{v}"))
        assertEquals("x = 4", p("\\boxed{x = 4}"))
    }

    @Test
    fun environmentsBecomeLinesOrMatrices() {
        assertEquals("x = 2 + 3\n= 5", p("\\begin{aligned} x &= 2 + 3 \\\\ &= 5 \\end{aligned}"))
        assertEquals("[1, 2; 3, 4]", p("\\begin{bmatrix} 1 & 2 \\\\ 3 & 4 \\end{bmatrix}"))
        assertEquals("|x| = { x, x ≥ 0; −x, x < 0 }", p("|x| = \\begin{cases} x, & x \\ge 0 \\\\ -x, & x < 0 \\end{cases}"))
    }

    @Test
    fun neverShowsBackslashesOrBraces() {
        val nasty = listOf(
            "\\left( \\frac{a}{b} \\right)^2", "\\unknowncommand{x}", "\\frac{1}{", "x^", "}{", "\\",
            "\\text{speed} = \\dfrac{\\Delta s}{\\Delta t}", "\\overline{AB} \\parallel \\overline{CD}",
            "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1", "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}",
        )
        nasty.forEach { src ->
            val out = p(src)
            assertFalse("$src -> $out", out.contains('\\') || out.contains('{') || out.contains('}'))
        }
        assertEquals("(a/b)²", p("\\left( \\frac{a}{b} \\right)^2"))
    }

    @Test
    fun structureDecidesTwoDimensionalLayout() {
        assertTrue(Latex.isStructured(Latex.parse("x = \\frac{a}{b}")))
        assertTrue(Latex.isStructured(Latex.parse("\\boxed{4}")))
        assertTrue(Latex.isStructured(Latex.parse("\\sqrt{b^2 - 4ac}")))
        assertFalse(Latex.isStructured(Latex.parse("x^2 + 3 \\times 4 \\approx 15")))
        assertFalse(Latex.isStructured(Latex.parse("\\sqrt{2}")))
    }

    @Test
    fun runsMarkVariablesItalicAndScriptsRaised() {
        val runs = Latex.runs("v_0^2")
        assertEquals(MathRun("v", Script.NONE, italic = true), runs[0])
        assertTrue(runs.any { it.text == "0" && it.script == Script.SUB })
        assertTrue(runs.any { it.text == "2" && it.script == Script.SUP })
        // Numbers and function names are upright.
        assertTrue(Latex.runs("2\\sin x").filter { it.text == "2" || it.text == "sin" }.none { it.italic })
    }

    @Test
    fun aSliceAfterAFractionTreatsPlusAsAnOperator() {
        val slice = listOf(MathNode.Sym("+", SymKind.OP), MathNode.Sym("1", SymKind.NUM))
        assertEquals("+ 1", Latex.plainOf(Latex.runs(slice, continued = true)))
        assertEquals("+1", Latex.plainOf(Latex.runs(slice, continued = false)))
    }
}
