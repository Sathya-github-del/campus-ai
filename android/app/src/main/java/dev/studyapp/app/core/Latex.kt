package dev.studyapp.app.core

/*
 * A small LaTeX-math reader for AI answers. Models write maths as \frac{a}{b}, x^2, \times,
 * \text{m/s}, \[ ... \] and so on even when asked not to, and showing that source raw is unreadable.
 *
 * This is not a TeX engine. It parses the subset models actually produce into a tiny tree
 * ([MathNode]) that the UI can lay out (stacked fractions, radicals, boxed answers, matrices) and
 * that [Latex.runs] / [Latex.plain] can flatten into readable Unicode ("(v₀² sin 2θ)/g", "x ≈ 3.2").
 * Anything it doesn't know degrades to its name without the backslash, never to raw source.
 */

enum class Script { NONE, SUP, SUB }

enum class SymKind {
    /** A variable: Latin letters and lowercase Greek, set in italic. */
    VAR,
    NUM,
    /** Binary operator (+ − × ·): spaced unless it is a sign. */
    OP,
    /** Relation (= ≈ ≤ → ⇒): always spaced. */
    REL,
    PUNCT, OPEN, CLOSE,
    /** Upright text (\text{}, uppercase Greek, symbols like ∞). */
    TEXT,
    /** Upright function name (sin, log, lim). */
    FUNC,
    SPACE,
    /** Big operators: ∑ ∫ ∏. */
    LARGE,
}

enum class TableKind { LINES, CASES, MATRIX, PMATRIX, BMATRIX, VMATRIX }

sealed interface MathNode {
    data class Row(val items: List<MathNode>) : MathNode
    data class Sym(val text: String, val kind: SymKind) : MathNode
    data class Frac(val num: MathNode, val den: MathNode, val line: Boolean = true) : MathNode
    data class Scripts(val base: MathNode, val sup: MathNode?, val sub: MathNode?) : MathNode
    data class Sqrt(val body: MathNode, val index: MathNode? = null) : MathNode
    data class Boxed(val body: MathNode) : MathNode
    data class Accent(val body: MathNode, val mark: Char) : MathNode
    data class Table(val rows: List<List<MathNode>>, val kind: TableKind) : MathNode
}

/** One piece of flattened maths: [italic] for variables, [script] for raised/lowered parts. */
data class MathRun(val text: String, val script: Script = Script.NONE, val italic: Boolean = false)

object Latex {
    fun parse(src: String): MathNode = Parser(src).parseTop()

    /** Flattened, styled runs for inline maths (a fraction becomes a/b, x^2 a raised 2). */
    fun runs(node: MathNode): List<MathRun> = Linear().apply { node(node) }.finish()

    fun runs(src: String): List<MathRun> = runs(parse(src))

    /**
     * Runs for a slice of a row (the UI lays fractions out between slices). [continued] means
     * something came before, so a leading "+" is an operator, not a sign.
     */
    fun runs(items: List<MathNode>, continued: Boolean): List<MathRun> =
        Linear(if (continued) SymKind.VAR else null).apply { items.forEach(::node) }.finish()

    /** Plain Unicode text (for copying, accessibility and tests): x², v₀, ½, a/(b + c). */
    fun plain(src: String): String = plainOf(runs(src))

    fun plain(node: MathNode): String = plainOf(runs(node))

    /** True when a display formula needs real 2-D layout (a stacked fraction, radical over a group, box, matrix). */
    fun isStructured(n: MathNode): Boolean = when (n) {
        is MathNode.Row -> n.items.any(::isStructured)
        is MathNode.Sym -> false
        is MathNode.Frac -> true
        is MathNode.Sqrt -> !isAtomic(n.body) || n.index != null && n.index != MathNode.Row(emptyList())
        is MathNode.Boxed -> true
        is MathNode.Accent -> isStructured(n.body)
        is MathNode.Table -> true
        is MathNode.Scripts -> isStructured(n.base) || n.sup?.let(::isStructured) == true || n.sub?.let(::isStructured) == true
    }

    /** Joins runs into plain text, using Unicode super/subscripts where every character has one. */
    fun plainOf(runs: List<MathRun>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < runs.size) {
            val script = runs[i].script
            val group = StringBuilder()
            while (i < runs.size && runs[i].script == script) group.append(runs[i++].text)
            val t = group.toString()
            sb.append(
                when (script) {
                    Script.NONE -> t
                    Script.SUP -> scriptText(t, SUP, "^")
                    Script.SUB -> scriptText(t, SUB, "_")
                },
            )
        }
        return sb.toString().replace(Regex("[ \\t]{2,}"), " ").lines().joinToString("\n") { it.trim() }.trim()
    }

    private fun scriptText(t: String, map: Map<Char, Char>, mark: String): String {
        val s = t.trim()
        if (s.isEmpty()) return ""
        if (s.all { it in map }) return s.map { map.getValue(it) }.joinToString("")
        return if (s.length == 1) mark + s else "$mark($s)"
    }

    /** One symbol (x, 9.8, m/s, x², √3): safe as a denominator without brackets. */
    internal fun isSingle(n: MathNode): Boolean = when (n) {
        is MathNode.Sym -> n.kind in setOf(SymKind.VAR, SymKind.NUM, SymKind.TEXT) && ' ' !in n.text
        is MathNode.Row -> n.items.size == 1 && isSingle(n.items[0])
        is MathNode.Scripts -> isSingle(n.base)
        is MathNode.Accent, is MathNode.Sqrt -> true
        else -> false
    }

    internal fun isAtomic(n: MathNode): Boolean = when (n) {
        is MathNode.Sym -> n.kind in setOf(SymKind.VAR, SymKind.NUM, SymKind.TEXT) && ' ' !in n.text
        is MathNode.Row -> n.items.size > 1 && n.items[0].let { it is MathNode.Sym && it.text == "−" } && isAtomic(MathNode.Row(n.items.drop(1))) ||
            n.items.isNotEmpty() && n.items.all { (it is MathNode.Sym && it.kind in setOf(SymKind.VAR, SymKind.NUM, SymKind.TEXT) && ' ' !in it.text) || it is MathNode.Scripts && isAtomic(it) || it is MathNode.Accent } ||
            n.items.size == 1 && isAtomic(n.items[0])
        is MathNode.Scripts -> isAtomic(n.base) || n.base == MathNode.Row(emptyList())
        is MathNode.Accent -> true
        is MathNode.Sqrt -> true
        else -> false
    }

    val SUP: Map<Char, Char> = buildMap {
        "0123456789".zip("⁰¹²³⁴⁵⁶⁷⁸⁹").forEach { (a, b) -> put(a, b) }
        put('+', '⁺'); put('−', '⁻'); put('-', '⁻'); put('=', '⁼'); put('(', '⁽'); put(')', '⁾')
        "abcdefghijklmnoprstuvwxyz".zip("ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ").forEach { (a, b) -> put(a, b) }
        put('T', 'ᵀ'); put('′', '′')
    }

    val SUB: Map<Char, Char> = buildMap {
        "0123456789".zip("₀₁₂₃₄₅₆₇₈₉").forEach { (a, b) -> put(a, b) }
        put('+', '₊'); put('−', '₋'); put('-', '₋'); put('=', '₌'); put('(', '₍'); put(')', '₎')
        "aehijklmnoprstuvx".zip("ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ").forEach { (a, b) -> put(a, b) }
    }

    /** Common fractions with their own Unicode character. */
    internal val VULGAR = mapOf(
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾", "1/5" to "⅕", "2/5" to "⅖",
        "3/5" to "⅗", "4/5" to "⅘", "1/6" to "⅙", "5/6" to "⅚", "1/8" to "⅛", "3/8" to "⅜", "5/8" to "⅝", "7/8" to "⅞",
    )

    // ------------------------------------------------------------------ symbol tables

    private fun table(kind: SymKind, vararg pairs: Pair<String, String>) = pairs.associate { it.first to (it.second to kind) }

    internal val SYMBOLS: Map<String, Pair<String, SymKind>> = buildMap {
        putAll(table(SymKind.VAR,
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
            "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
            "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π", "varpi" to "ϖ",
            "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
            "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω", "ell" to "ℓ", "hbar" to "ℏ",
            "imath" to "ı", "jmath" to "ȷ",
        ))
        putAll(table(SymKind.TEXT,
            "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ",
            "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
            "infty" to "∞", "partial" to "∂", "nabla" to "∇", "angle" to "∠", "measuredangle" to "∡", "triangle" to "△",
            "degree" to "°", "circ" to "∘", "prime" to "′", "forall" to "∀", "exists" to "∃", "nexists" to "∄",
            "neg" to "¬", "lnot" to "¬", "emptyset" to "∅", "varnothing" to "∅", "aleph" to "ℵ", "Re" to "ℜ", "Im" to "ℑ",
            "ldots" to "…", "dots" to "…", "dotsc" to "…", "dotsb" to "⋯", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
            "checkmark" to "✓", "square" to "□", "Box" to "□", "blacksquare" to "■", "star" to "⋆", "dagger" to "†",
            "%" to "%", "$" to "$", "&" to "&", "#" to "#", "_" to "_", "|" to "‖", "Vert" to "‖", "vert" to "|",
            "lvert" to "|", "rvert" to "|", "lVert" to "‖", "rVert" to "‖", "S" to "§", "copyright" to "©",
        ))
        putAll(table(SymKind.OP,
            "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗", "bullet" to "•",
            "oplus" to "⊕", "otimes" to "⊗", "odot" to "⊙", "cup" to "∪", "cap" to "∩", "setminus" to "∖",
            "wedge" to "∧", "land" to "∧", "vee" to "∨", "lor" to "∨", "bmod" to "mod", "cross" to "×",
        ))
        putAll(table(SymKind.REL,
            "approx" to "≈", "neq" to "≠", "ne" to "≠", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
            "leqslant" to "≤", "geqslant" to "≥", "ll" to "≪", "gg" to "≫", "equiv" to "≡", "sim" to "∼",
            "simeq" to "≃", "cong" to "≅", "propto" to "∝", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
            "gets" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐", "implies" to "⟹", "impliedby" to "⟸",
            "iff" to "⟺", "Leftrightarrow" to "⇔", "leftrightarrow" to "↔", "longrightarrow" to "⟶",
            "Longrightarrow" to "⟹", "longleftarrow" to "⟵", "Longleftrightarrow" to "⟺", "mapsto" to "↦",
            "uparrow" to "↑", "downarrow" to "↓", "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂",
            "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇", "perp" to "⊥", "parallel" to "∥", "mid" to "∣",
            "approxeq" to "≊", "doteq" to "≐", "coloneqq" to "≔", "therefore" to "∴", "because" to "∵",
            "lt" to "<", "gt" to ">", "nless" to "≮", "ngtr" to "≯", "rightleftharpoons" to "⇌", "leadsto" to "⇝",
        ))
        putAll(table(SymKind.LARGE,
            "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
            "bigcup" to "⋃", "bigcap" to "⋂", "bigoplus" to "⨁", "bigotimes" to "⨂",
        ))
        putAll(table(SymKind.OPEN, "langle" to "⟨", "lfloor" to "⌊", "lceil" to "⌈", "{" to "{", "lbrace" to "{", "lbrack" to "["))
        putAll(table(SymKind.CLOSE, "rangle" to "⟩", "rfloor" to "⌋", "rceil" to "⌉", "}" to "}", "rbrace" to "}", "rbrack" to "]"))
    }

    internal val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
        "log", "ln", "lg", "exp", "lim", "liminf", "limsup", "max", "min", "sup", "inf", "det", "gcd", "lcm",
        "deg", "arg", "dim", "ker", "Pr", "hom", "sgn",
    )

    /** Function names a model may write without a backslash inside maths ("sin(2θ)"). */
    internal val BARE_FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "log", "ln", "exp", "lim", "max", "min", "sinh", "cosh", "tanh")

    internal val ACCENTS = mapOf(
        "vec" to '⃗', "overrightarrow" to '⃗', "hat" to '̂', "widehat" to '̂', "bar" to '̄',
        "overline" to '̅', "dot" to '̇', "ddot" to '̈', "tilde" to '̃', "widetilde" to '̃',
        "underline" to '̲', "check" to '̌', "acute" to '́', "grave" to '̀', "breve" to '̆',
    )

    internal val TEXT_COMMANDS = setOf("text", "textrm", "textnormal", "textbf", "textit", "textsf", "texttt", "mbox", "hbox", "textup", "emph")
    internal val ROMAN_COMMANDS = setOf("mathrm", "mathbf", "mathsf", "mathtt", "operatorname", "boldsymbol", "bm", "mathbfit", "pmb")
    internal val PASS_COMMANDS = setOf("mathit", "mathcal", "mathscr", "mathfrak", "mathnormal", "displaystyle", "underbrace", "overbrace", "ensuremath")
    internal val IGNORED = setOf(
        "left", "right", "middle", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
        "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle", "limits", "nolimits", "nonumber", "notag",
        "allowbreak", "nobreak", "relax", "centering", "hline", "noindent",
    )
    internal val DOUBLE_STRUCK = mapOf('N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'R' to "ℝ", 'C' to "ℂ", 'P' to "ℙ", 'E' to "𝔼")
}

/** Recursive-descent reader over one LaTeX maths string. Never throws: bad input degrades to text. */
private class Parser(private val s: String) {
    private var i = 0
    private var depth = 0

    fun parseTop(): MathNode {
        val rows = parseTable(top = true)
        return when {
            rows.size == 1 && rows[0].size == 1 -> rows[0][0]
            rows.size == 1 -> MathNode.Row(rows[0])
            else -> MathNode.Table(rows.map { listOf(MathNode.Row(it)) }, TableKind.LINES)
        }
    }

    /** Rows of cells, split on \\ and &; stops at \end or end of input. */
    private fun parseTable(top: Boolean = false): List<List<MathNode>> {
        val rows = mutableListOf<List<MathNode>>()
        var cells = mutableListOf<MathNode>()
        while (true) {
            cells += row(parseList(top))
            when {
                s.startsWith("&", i) -> i++
                s.startsWith("\\\\", i) -> {
                    i += 2
                    skipOptionalBracket()
                    rows += cells; cells = mutableListOf()
                }
                else -> break
            }
        }
        rows += cells
        // A trailing \\ leaves an empty last row.
        return rows.filterNot { r -> r.all { it == MathNode.Row(emptyList()) } }.ifEmpty { listOf(listOf(MathNode.Row(emptyList()))) }
    }

    private fun skipOptionalBracket() {
        // "\\[4pt]" spacing after a line break.
        val m = Regex("^\\s*\\[[0-9.]+\\s*[a-z]{2}\\]").find(s.substring(i))
        if (m != null) i += m.value.length
    }

    private fun row(items: List<MathNode>): MathNode = if (items.size == 1) items[0] else MathNode.Row(items)

    /** Items until '}', '&', '\\', '\end' or end of input. At [top] level a stray '}' is skipped. */
    private fun parseList(top: Boolean = false, stopAt: Char? = null): List<MathNode> {
        val out = mutableListOf<MathNode>()
        while (i < s.length) {
            val c = s[i]
            if (c == '}') { if (top) { i++; continue } else break }
            if (stopAt != null && c == stopAt) break
            if (c == '&' || s.startsWith("\\\\", i) || isEnd()) break
            val atom = parseAtom() ?: continue
            out += postfix(atom)
        }
        return out
    }

    private fun isEnd() = s.startsWith("\\end", i) && (i + 4 >= s.length || !s[i + 4].isLetter())

    private fun postfix(base0: MathNode): MathNode {
        val base = base0
        var sup: MathNode? = null
        var sub: MathNode? = null
        while (true) {
            val save = i
            while (i < s.length && s[i].isWhitespace()) i++
            when {
                i < s.length && s[i] == '^' && sup == null -> { i++; sup = scriptArg() }
                i < s.length && s[i] == '_' && sub == null -> { i++; sub = scriptArg() }
                i < s.length && s[i] == '\'' -> {
                    i++
                    sup = MathNode.Row(listOfNotNull(sup, MathNode.Sym("′", SymKind.TEXT)))
                }
                else -> { i = save; break }
            }
        }
        if (sup == null && sub == null) return base
        // x^\circ / 30^{\circ}: a degree sign, not a raised ring.
        if (sub == null && sup != null && isRing(sup)) return MathNode.Row(listOf(base, MathNode.Sym("°", SymKind.TEXT)))
        return MathNode.Scripts(base, sup, sub)
    }

    private fun isRing(n: MathNode): Boolean = when (n) {
        is MathNode.Sym -> n.text == "∘" || n.text == "°"
        is MathNode.Row -> n.items.size == 1 && isRing(n.items[0])
        else -> false
    }

    private fun scriptArg(): MathNode {
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return MathNode.Row(emptyList())
        val c = s[i]
        return when {
            c == '{' -> group()
            c == '\\' -> parseAtom() ?: MathNode.Row(emptyList())
            c.isDigit() -> { i++; MathNode.Sym(c.toString(), SymKind.NUM) }
            c.isLetter() -> { i++; MathNode.Sym(c.toString(), SymKind.VAR) }
            c == '-' -> { i++; MathNode.Sym("−", SymKind.OP) }
            else -> { i++; MathNode.Sym(c.toString(), SymKind.PUNCT) }
        }
    }

    /** A braced group (or, if no brace follows, the next single atom, like \frac12). */
    private fun group(): MathNode {
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return MathNode.Row(emptyList())
        if (s[i] != '{') {
            val c = s[i]
            return when {
                c == '\\' -> parseAtom() ?: MathNode.Row(emptyList())
                c.isDigit() -> { i++; MathNode.Sym(c.toString(), SymKind.NUM) }
                c.isLetter() -> { i++; MathNode.Sym(c.toString(), SymKind.VAR) }
                else -> { i++; MathNode.Sym(c.toString(), SymKind.PUNCT) }
            }
        }
        i++ // {
        depth++
        val items = mutableListOf<MathNode>()
        while (i < s.length && s[i] != '}') {
            items += parseList()
            // & or \\ inside a plain group: not a table here, just skip the separator.
            if (s.startsWith("\\\\", i)) i += 2
            else if (i < s.length && s[i] == '&') i++
            else if (isEnd()) { i += 4; rawArg() }
        }
        if (i < s.length) i++ // }
        depth--
        return row(items).let { if (it is MathNode.Row) it else MathNode.Row(listOf(it)) }
    }

    /** The raw text of a braced argument, for \text{...} and environment names. */
    private fun rawArg(): String {
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] != '{') return ""
        var d = 0
        val start = i + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '{' -> d++
                '}' -> { d--; if (d == 0) { i++; return s.substring(start, i - 1) } }
            }
            i++
        }
        return s.substring(start.coerceAtMost(s.length))
    }

    private fun optionalArg(): String? {
        var j = i
        while (j < s.length && s[j].isWhitespace()) j++
        if (j >= s.length || s[j] != '[') return null
        var d = 0
        var k = j
        while (k < s.length) {
            when (s[k]) { '[' -> d++; ']' -> { d--; if (d == 0) { i = k + 1; return s.substring(j + 1, k) } } }
            k++
        }
        return null
    }

    private fun parseAtom(): MathNode? {
        val c = s[i]
        when {
            c.isWhitespace() -> { i++; return null }
            c == '{' -> return group()
            c == '\\' -> return command()
            c.isDigit() || c == '.' && i + 1 < s.length && s[i + 1].isDigit() -> {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit())) i++
                return MathNode.Sym(s.substring(start, i), SymKind.NUM)
            }
            c.isLetter() && c.code < 0x250 -> {
                var j = i
                while (j < s.length && s[j].isLetter() && s[j].code < 0x250) j++
                val word = s.substring(i, j)
                // A bare function name ("sin x") is upright. Otherwise one letter at a time, so a
                // following ^2 attaches to the last letter only (ab^2 = a·b²).
                if (word in Latex.BARE_FUNCTIONS) { i = j; return MathNode.Sym(word, SymKind.FUNC) }
                i++
                return MathNode.Sym(c.toString(), SymKind.VAR)
            }
            (c == '^' || c == '_') -> return postfix(MathNode.Row(emptyList()))
            else -> {
                i++
                return when (c) {
                    '+' -> MathNode.Sym("+", SymKind.OP)
                    '-', '−' -> MathNode.Sym("−", SymKind.OP)
                    '*' -> MathNode.Sym("×", SymKind.OP)
                    '×', '÷', '±', '·' -> MathNode.Sym(c.toString(), SymKind.OP)
                    '=', '<', '>', ':', '≈', '≠', '≤', '≥', '→', '⇒' -> MathNode.Sym(c.toString(), SymKind.REL)
                    '(', '[' -> MathNode.Sym(c.toString(), SymKind.OPEN)
                    ')', ']' -> MathNode.Sym(c.toString(), SymKind.CLOSE)
                    ',', ';', '/', '|', '!', '.', '?' -> MathNode.Sym(c.toString(), SymKind.PUNCT)
                    '~' -> MathNode.Sym(" ", SymKind.SPACE)
                    '$' -> null
                    else -> if (c.isLetter()) MathNode.Sym(c.toString(), SymKind.VAR) else MathNode.Sym(c.toString(), SymKind.TEXT)
                }
            }
        }
    }

    private fun command(): MathNode? {
        i++ // backslash
        if (i >= s.length) return null
        val name: String
        if (s[i].isLetter()) {
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            name = s.substring(start, i)
        } else {
            name = s[i].toString(); i++
        }
        when (name) {
            ",", ":", ">", ";", " " -> return MathNode.Sym(" ", SymKind.SPACE)
            "!", "/" -> return null
            "quad" -> return MathNode.Sym(" ", SymKind.SPACE)
            "qquad" -> return MathNode.Sym("  ", SymKind.SPACE)
            "\\" -> return null
        }
        if (name in Latex.IGNORED) {
            if (name == "left" || name == "right" || name == "middle" || name.startsWith("big") || name.startsWith("Big")) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] == '.') i++
            }
            return null
        }
        Latex.SYMBOLS[name]?.let { (text, kind) -> return MathNode.Sym(text, kind) }
        if (name in Latex.FUNCTIONS) return MathNode.Sym(name, SymKind.FUNC)
        Latex.ACCENTS[name]?.let { return MathNode.Accent(group(), it) }
        return when (name) {
            "frac", "dfrac", "tfrac", "cfrac" -> MathNode.Frac(group(), group())
            "binom", "dbinom", "tbinom" -> MathNode.Frac(group(), group(), line = false)
            "sqrt" -> {
                val idx = optionalArg()?.let { Parser(it).parseTop() }
                MathNode.Sqrt(group(), idx)
            }
            "boxed", "fbox", "framebox" -> MathNode.Boxed(group())
            "pmod" -> MathNode.Row(listOf(MathNode.Sym("(mod ", SymKind.TEXT), group(), MathNode.Sym(")", SymKind.CLOSE)))
            "mathbb" -> MathNode.Sym(rawArg().map { Latex.DOUBLE_STRUCK[it] ?: it.toString() }.joinToString(""), SymKind.TEXT)
            "overset", "stackrel" -> { val top = group(); MathNode.Scripts(group(), top, null) }
            "underset" -> { val bot = group(); MathNode.Scripts(group(), null, bot) }
            "color" -> { rawArg(); null }
            "textcolor", "colorbox" -> { rawArg(); group() }
            "phantom", "hphantom", "vphantom", "label", "hspace", "vspace" -> { rawArg(); null }
            "tag" -> MathNode.Sym("  (${rawArg()})", SymKind.TEXT)
            "begin" -> environment(rawArg())
            "end" -> { rawArg(); null }
            "not" -> {
                val next = parseAtom()
                if (next is MathNode.Sym && next.text == "=") MathNode.Sym("≠", SymKind.REL)
                else if (next is MathNode.Sym) MathNode.Sym(next.text + "̸", next.kind) else next
            }
            in Latex.TEXT_COMMANDS -> MathNode.Sym(unescapeText(rawArg()), SymKind.TEXT)
            in Latex.ROMAN_COMMANDS -> upright(group())
            in Latex.PASS_COMMANDS -> group()
            // Unknown command: show its name (never a backslash).
            else -> MathNode.Sym(name, SymKind.TEXT)
        }
    }

    private fun environment(name: String): MathNode {
        val base = name.trimEnd('*')
        if (base == "array" || base == "alignat") rawArg() // column spec / count
        val kind = when (base) {
            "matrix", "smallmatrix", "array" -> TableKind.MATRIX
            "pmatrix" -> TableKind.PMATRIX
            "bmatrix", "Bmatrix" -> TableKind.BMATRIX
            "vmatrix", "Vmatrix" -> TableKind.VMATRIX
            "cases", "dcases", "rcases" -> TableKind.CASES
            else -> TableKind.LINES
        }
        val rows = parseTable()
        if (isEnd()) { i += 4; rawArg() }
        return MathNode.Table(rows, kind)
    }

    private fun upright(n: MathNode): MathNode = when (n) {
        is MathNode.Sym -> if (n.kind == SymKind.VAR) MathNode.Sym(n.text, SymKind.TEXT) else n
        is MathNode.Row -> {
            // \mathrm{kg} reads as one word.
            if (n.items.isNotEmpty() && n.items.all { it is MathNode.Sym && it.kind == SymKind.VAR }) {
                MathNode.Sym(n.items.joinToString("") { (it as MathNode.Sym).text }, SymKind.TEXT)
            } else MathNode.Row(n.items.map(::upright))
        }
        is MathNode.Scripts -> MathNode.Scripts(upright(n.base), n.sup?.let(::upright), n.sub?.let(::upright))
        else -> n
    }

    private fun unescapeText(t: String): String =
        t.replace(Regex("\\\\([%$&#_\\{\\} ])"), "$1").replace("~", " ")
            .replace(Regex("\\$([^$]*)\\$")) { Latex.plain(it.groupValues[1]) }
            .replace(Regex("\\\\([a-zA-Z]+)")) { m -> Latex.SYMBOLS[m.groupValues[1]]?.first ?: m.groupValues[1] }
}

/** Flattens a [MathNode] into styled runs with readable spacing. */
private class Linear(start: SymKind? = null) {
    private val out = mutableListOf<MathRun>()
    private var script = Script.NONE
    /** Kind of the last thing emitted at this level, for unary-minus and spacing decisions. */
    private var prev: SymKind? = start

    fun finish(): List<MathRun> = out

    private fun emit(text: String, italic: Boolean = false) {
        if (text.isEmpty()) return
        out += MathRun(text, script, italic)
    }

    fun node(n: MathNode) {
        when (n) {
            is MathNode.Row -> n.items.forEach(::node)
            is MathNode.Sym -> sym(n)
            is MathNode.Frac -> frac(n)
            is MathNode.Scripts -> {
                node(n.base)
                n.sub?.let { scripted(Script.SUB, it) }
                n.sup?.let { scripted(Script.SUP, it) }
                prev = SymKind.VAR
            }
            is MathNode.Sqrt -> {
                val idx = n.index?.let { Latex.plain(it) }
                emit(when (idx) { null, "" -> "√"; "3" -> "∛"; "4" -> "∜"; else -> (Latex.SUP.let { m -> if (idx.all { it in m }) idx.map { m.getValue(it) }.joinToString("") else "($idx)" }) + "√" })
                wrapped(n.body)
                prev = SymKind.VAR
            }
            is MathNode.Boxed -> node(n.body)
            is MathNode.Accent -> {
                val inner = Latex.plain(n.body)
                val marked = if (inner.length <= 1 || n.mark == '̅' || n.mark == '̲') inner.map { "$it${n.mark}" }.joinToString("") else inner + n.mark
                emit(marked, italic = inner.length == 1 && inner[0].isLetter())
                prev = SymKind.VAR
            }
            is MathNode.Table -> table(n)
        }
    }

    private fun sym(n: MathNode.Sym) {
        val p = prev
        when (n.kind) {
            SymKind.OP -> {
                val unary = p == null || p == SymKind.OP || p == SymKind.REL || p == SymKind.OPEN || p == SymKind.PUNCT || p == SymKind.LARGE
                if (unary || script != Script.NONE) emit(n.text) else emit(" ${n.text} ")
            }
            SymKind.REL -> if (script != Script.NONE) emit(n.text) else emit(" ${n.text} ")
            SymKind.PUNCT -> emit(if ((n.text == "," || n.text == ";") && script == Script.NONE) "${n.text} " else n.text)
            SymKind.FUNC -> {
                if (p == SymKind.VAR || p == SymKind.NUM || p == SymKind.CLOSE) emit(" ")
                emit(n.text)
            }
            SymKind.VAR, SymKind.NUM -> {
                if (p == SymKind.FUNC || p == SymKind.LARGE) emit(" ")
                emit(n.text, italic = n.kind == SymKind.VAR && italicLetter(n.text))
            }
            SymKind.LARGE -> emit(n.text)
            else -> {
                if (p == SymKind.FUNC && n.kind == SymKind.TEXT) emit(" ")
                emit(n.text)
            }
        }
        prev = n.kind
    }

    private fun italicLetter(t: String) = t.length == 1 && t[0].isLetter()

    private fun scripted(s: Script, n: MathNode) {
        if (script != Script.NONE) {
            // A script inside a script (e^{-x^2}): there is no third level of raise, so write it as
            // Unicode super/subscript characters where possible, else ^(...).
            val inner = Latex.plain(n)
            val map = if (s == Script.SUP) Latex.SUP else Latex.SUB
            emit(if (inner.isNotEmpty() && inner.all { it in map }) inner.map { map.getValue(it) }.joinToString("") else (if (s == Script.SUP) "^" else "_") + if (inner.length == 1) inner else "($inner)")
            return
        }
        val saved = script to prev
        if (script == Script.NONE) script = s
        prev = null
        node(n)
        script = saved.first
        prev = saved.second
    }

    private fun frac(n: MathNode.Frac) {
        val num = Latex.plain(n.num)
        val den = Latex.plain(n.den)
        if (!n.line) {
            emit("C($num, $den)")
            prev = SymKind.VAR
            return
        }
        Latex.VULGAR["$num/$den"]?.let { if (script == Script.NONE) { if (prev == SymKind.NUM) emit(" "); emit(it); prev = SymKind.NUM; return } }
        wrapped(n.num)
        emit("/")
        prev = SymKind.PUNCT
        // "2a" is fine as a numerator but reads wrongly as a denominator: x/(2a), not x/2a.
        wrapped(n.den, strict = true)
        prev = SymKind.VAR
    }

    /** A sub-expression, in brackets unless it is a single atom. */
    private fun wrapped(n: MathNode, strict: Boolean = false) {
        val atomic = if (strict) Latex.isSingle(n) else Latex.isAtomic(n)
        if (!atomic) emit("(")
        prev = if (atomic) prev else SymKind.OPEN
        node(n)
        if (!atomic) emit(")")
    }

    private fun table(n: MathNode.Table) {
        fun cellText(c: MathNode) = Latex.plain(c)
        when (n.kind) {
            TableKind.LINES -> n.rows.forEachIndexed { r, cells ->
                if (r > 0) { emit("\n"); prev = null }
                cells.forEach(::node)
            }
            TableKind.CASES -> {
                emit("{ ")
                emit(n.rows.joinToString("; ") { cells -> cells.joinToString("  ") { cellText(it) } })
                emit(" }")
            }
            else -> {
                val (l, r) = when (n.kind) { TableKind.PMATRIX -> "(" to ")"; TableKind.VMATRIX -> "|" to "|"; else -> "[" to "]" }
                emit(l + n.rows.joinToString("; ") { cells -> cells.joinToString(", ") { cellText(it) } } + r)
            }
        }
        prev = SymKind.VAR
    }
}
