package dev.studyapp.app.core

/*
 * Markdown for AI answers: the subset chat models actually write (bold, italics, inline code,
 * bullet and numbered lists, headings, rules, quotes, fenced code, simple tables, links) plus
 * maths in any of the forms they use: \( \), \[ \], $$ $$, $ $, \begin{..}, a bare [ \frac.. ] line,
 * stray \commands in prose and x^2.
 *
 * Pure Kotlin on purpose: it is unit-tested on the JVM, adds nothing to the APK, and the UI
 * (ui/AnswerText.kt) owns all the styling, so no library's default look fights the night-meter theme.
 */

data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    /** Set in the maths face (serif); [italic] then marks a variable. */
    val math: Boolean = false,
    val script: Script = Script.NONE,
    val link: String? = null,
)

sealed interface MdBlock {
    data class Paragraph(val spans: List<Span>) : MdBlock
    data class Heading(val level: Int, val spans: List<Span>) : MdBlock
    /** One list item. [marker] is "1." style for ordered lists (the source's own number), "" for bullets. */
    data class Item(val ordered: Boolean, val marker: String, val level: Int, val spans: List<Span>) : MdBlock
    data object Rule : MdBlock
    data class Code(val text: String, val lang: String?) : MdBlock
    data class Quote(val spans: List<Span>) : MdBlock
    /** A display formula on its own line(s). */
    data class Math(val node: MathNode, val source: String) : MdBlock
    data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : MdBlock
}

object Markdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val TABLE_SEP = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val BRACKET_LINE = Regex("^\\[\\s+(.+?)\\s+\\]$")
    private val BEGIN = Regex("^\\\\begin\\{([a-zA-Z*]+)\\}")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        var item: Triple<Boolean, String, Int>? = null // ordered, marker, level
        val itemLines = mutableListOf<String>()
        val indents = mutableListOf<Int>()

        fun flush() {
            if (para.isNotEmpty()) {
                blocks += MdBlock.Paragraph(inline(para.joinToString("\n")))
                para.clear()
            }
            item?.let { (o, m, l) -> blocks += MdBlock.Item(o, m, l, inline(itemLines.joinToString("\n"))) }
            item = null
            itemLines.clear()
        }

        fun level(indent: Int): Int {
            while (indents.isNotEmpty() && indent < indents.last()) indents.removeAt(indents.lastIndex)
            if (indents.isEmpty() || indent > indents.last()) indents += indent
            return (indents.size - 1).coerceAtMost(3)
        }

        fun math(src: String) {
            flush()
            if (src.isNotBlank()) blocks += MdBlock.Math(Latex.parse(src.trim()), src.trim())
        }

        /** Collects lines from [start] until one contains [close]; returns (inner text, next index). */
        fun collect(start: Int, first: String, close: String): Pair<String, Int> {
            val sb = StringBuilder()
            val firstClose = first.indexOf(close)
            if (firstClose >= 0) return first.substring(0, firstClose) to start + 1
            sb.append(first)
            var j = start + 1
            while (j < lines.size) {
                val l = lines[j]
                val k = l.indexOf(close)
                if (k >= 0) { sb.append('\n').append(l.substring(0, k)); return sb.toString() to j + 1 }
                sb.append('\n').append(l)
                j++
            }
            return sb.toString() to j
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.isEmpty() -> { flush(); i++; continue }
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.takeWhile { it == t[0] }
                    val lang = t.removePrefix(fence).trim().ifEmpty { null }
                    val indent = line.length - line.trimStart().length
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        body += lines[i].let { l -> l.drop(minOf(indent, l.length - l.trimStart().length)) }
                        i++
                    }
                    i++
                    blocks += MdBlock.Code(body.joinToString("\n").trimEnd(), lang)
                    continue
                }
                t.startsWith("$$") -> {
                    val (inner, next) = collect(i, t.removePrefix("$$"), "$$"); math(inner); i = next; continue
                }
                t.startsWith("\\[") -> {
                    val (inner, next) = collect(i, t.removePrefix("\\["), "\\]"); math(inner); i = next; continue
                }
                BEGIN.containsMatchIn(t) -> {
                    val env = BEGIN.find(t)!!.groupValues[1]
                    val (inner, next) = collect(i, t, "\\end{$env}")
                    math("$inner\\end{$env}"); i = next; continue
                }
                // "[ x = \frac{a}{b} ]": display maths whose backslashes on the brackets were lost.
                BRACKET_LINE.matchEntire(t)?.takeIf { looksLikeLatex(it.groupValues[1]) } != null -> {
                    math(BRACKET_LINE.matchEntire(t)!!.groupValues[1]); i++; continue
                }
                t == "[" -> {
                    val end = (i + 1 until minOf(lines.size, i + 20)).firstOrNull { lines[it].trim() == "]" }
                    val inner = end?.let { lines.subList(i + 1, it).joinToString("\n") }
                    if (end != null && looksLikeLatex(inner!!)) { math(inner); i = end + 1; continue }
                }
            }
            val heading = HEADING.matchEntire(t)
            if (heading != null) {
                flush(); indents.clear()
                blocks += MdBlock.Heading(heading.groupValues[1].length, inline(heading.groupValues[2]))
                i++; continue
            }
            if (RULE.matches(t)) { flush(); indents.clear(); blocks += MdBlock.Rule; i++; continue }
            if (t.startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1].trim())) {
                flush()
                val header = cells(t)
                val rows = mutableListOf<List<List<Span>>>()
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                blocks += MdBlock.Table(header, rows)
                continue
            }
            val bullet = BULLET.matchEntire(line)
            val ordered = if (bullet == null) ORDERED.matchEntire(line) else null
            if (bullet != null || ordered != null) {
                flush()
                val indent = (bullet ?: ordered)!!.groupValues[1].replace("\t", "    ").length
                item = if (bullet != null) Triple(false, "", level(indent)) else Triple(true, ordered!!.groupValues[2] + ".", level(indent))
                itemLines += (bullet?.groupValues?.get(2) ?: ordered!!.groupValues[3])
                i++; continue
            }
            if (t.startsWith(">")) {
                flush()
                val q = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) { q += lines[i].trim().removePrefix(">").trim(); i++ }
                blocks += MdBlock.Quote(inline(q.joinToString("\n")))
                continue
            }
            if (item != null) itemLines += t else { if (para.isEmpty()) indents.clear(); para += t }
            i++
        }
        flush()
        return blocks
    }

    private fun cells(row: String): List<List<Span>> =
        row.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|")).map { inline(it.trim()) }

    private val LATEXY = Regex("\\\\[a-zA-Z]+|\\^|_\\{")
    private val MATHY = Regex("\\\\[a-zA-Z]|\\^|_|=")

    private fun looksLikeLatex(s: String) = LATEXY.containsMatchIn(s)

    /** Commands we recognise outside maths delimiters (a model's stray "\times" in a sentence). */
    private val KNOWN_COMMANDS: Set<String> by lazy {
        (Latex.SYMBOLS.keys.filter { it.length > 1 } + Latex.FUNCTIONS + Latex.ACCENTS.keys + Latex.TEXT_COMMANDS +
            Latex.ROMAN_COMMANDS + listOf("frac", "dfrac", "tfrac", "sqrt", "boxed", "binom", "mathbb", "left", "right", "quad", "qquad")).toSet()
    }

    fun inline(text: String): List<Span> = merge(InlineParser(text).parse(Span("")))

    private fun merge(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == s.copy(text = "")) out[out.lastIndex] = last.copy(text = last.text + s.text)
            else out += s
        }
        return out
    }

    private class InlineParser(private val s: String) {
        fun parse(style: Span): List<Span> = parseRange(0, s.length, style)

        private fun parseRange(from: Int, to: Int, style: Span): List<Span> {
            val out = mutableListOf<Span>()
            val buf = StringBuilder()
            fun text() { if (buf.isNotEmpty()) { out += style.copy(text = buf.toString()); buf.clear() } }
            fun mathSpans(src: String) { text(); out += Latex.runs(src).map { style.copy(text = it.text, math = true, italic = it.italic || style.italic, script = it.script) } }
            var i = from
            while (i < to) {
                val c = s[i]
                val rest = to - i
                when {
                    c == '\\' && rest > 1 && (s[i + 1] == '(' || s[i + 1] == '[') -> {
                        val close = if (s[i + 1] == '(') "\\)" else "\\]"
                        val end = s.indexOf(close, i + 2).takeIf { it in 0 until to }
                        if (end != null) { mathSpans(s.substring(i + 2, end)); i = end + 2; continue }
                        buf.append(s[i + 1]); i += 2; continue
                    }
                    c == '\\' && rest > 1 && s[i + 1].isLetter() -> {
                        var j = i + 1
                        while (j < to && s[j].isLetter()) j++
                        val name = s.substring(i + 1, j)
                        if (name in KNOWN_COMMANDS) {
                            val end = commandExtent(j, to)
                            mathSpans(s.substring(i, end)); i = end; continue
                        }
                        buf.append(s, i, j); i = j; continue
                    }
                    c == '\\' && rest > 1 && s[i + 1] in "\\`*_{}[]()#+-.!|~<>\$" -> { buf.append(s[i + 1]); i += 2; continue }
                    c == '$' -> {
                        if (s.startsWith("$$", i)) {
                            val end = s.indexOf("$$", i + 2).takeIf { it in 0 until to }
                            if (end != null) { mathSpans(s.substring(i + 2, end)); i = end + 2; continue }
                        } else {
                            val end = dollarEnd(i, to)
                            if (end != null) { mathSpans(s.substring(i + 1, end)); i = end + 1; continue }
                        }
                    }
                    c == '`' -> {
                        var n = 0
                        while (i + n < to && s[i + n] == '`') n++
                        val fence = "`".repeat(n)
                        val end = s.indexOf(fence, i + n).takeIf { it in 0 until to }
                        if (end != null) {
                            text(); out += style.copy(text = s.substring(i + n, end).trim(), code = true); i = end + n; continue
                        }
                        buf.append(fence); i += n; continue
                    }
                    c == '*' || c == '_' -> {
                        val n = run(i, to, c)
                        val delim = c.toString().repeat(minOf(n, 3))
                        val end = emphasisEnd(i, to, delim)
                        if (end != null) {
                            text()
                            val inner = when (delim.length) {
                                3 -> style.copy(bold = true, italic = true)
                                2 -> style.copy(bold = true)
                                else -> style.copy(italic = true)
                            }
                            out += parseRange(i + delim.length, end, inner)
                            i = end + delim.length; continue
                        }
                        buf.append(s, i, i + n); i += n; continue
                    }
                    c == '~' && s.startsWith("~~", i) -> {
                        val end = s.indexOf("~~", i + 2).takeIf { it in 0 until to && it > i + 2 }
                        if (end != null) { text(); out += parseRange(i + 2, end, style.copy(strike = true)); i = end + 2; continue }
                    }
                    c == '[' -> {
                        val m = LINK.matchAt(s, i)
                        if (m != null && m.range.last < to) {
                            text(); out += parseRange(i + 1, i + 1 + m.groupValues[1].length, style.copy(link = m.groupValues[2]))
                            i = m.range.last + 1; continue
                        }
                    }
                    // Plain-text exponent: x^2, 10^-3, (a+b)^{n+1}.
                    c == '^' && i > from && (s[i - 1].isLetterOrDigit() || s[i - 1] == ')') && rest > 1 -> {
                        val m = EXPONENT.matchAt(s, i + 1)
                        if (m != null && m.range.last < to) {
                            text()
                            val v = m.value.removeSurrounding("{", "}")
                            out += style.copy(text = v.replace('-', '−'), script = Script.SUP)
                            i = m.range.last + 1; continue
                        }
                    }
                }
                buf.append(c); i++
            }
            text()
            return out
        }

        /** End of a bare \command with its [optional] and {braced} arguments and any ^/_ scripts. */
        private fun commandExtent(start: Int, to: Int): Int {
            var j = start
            while (j < to) {
                when {
                    s[j] == '{' -> j = balanced(j, to, '{', '}')
                    s[j] == '[' && j == start -> j = balanced(j, to, '[', ']')
                    (s[j] == '^' || s[j] == '_') && j + 1 < to -> {
                        j++
                        j = if (s[j] == '{') balanced(j, to, '{', '}') else if (s[j] == '\\') {
                            var k = j + 1; while (k < to && s[k].isLetter()) k++; k
                        } else j + 1
                    }
                    else -> return j
                }
            }
            return j
        }

        private fun balanced(open: Int, to: Int, o: Char, c: Char): Int {
            var d = 0
            var j = open
            while (j < to) {
                if (s[j] == '\\') { j += 2; continue }
                if (s[j] == o) d++
                if (s[j] == c) { d--; if (d == 0) return j + 1 }
                j++
            }
            return to
        }

        /** Closing '$' for inline maths, only when the content really looks like maths (not "$5 and $10"). */
        private fun dollarEnd(i: Int, to: Int): Int? {
            if (i + 1 >= to || s[i + 1].isWhitespace()) return null
            val end = s.indexOf('$', i + 1).takeIf { it in 0 until to } ?: return null
            if (s[end - 1].isWhitespace() || end + 1 < to && s[end + 1].isDigit()) return null
            val inner = s.substring(i + 1, end)
            if ('\n' in inner) return null
            val mathy = MATHY.containsMatchIn(inner) || inner.length <= 3 && inner.any { it.isLetter() }
            return if (mathy) end else null
        }

        private fun run(i: Int, to: Int, c: Char): Int { var n = 0; while (i + n < to && s[i + n] == c) n++; return n }

        /** Where the matching closing delimiter is, following CommonMark's flanking rules loosely. */
        private fun emphasisEnd(i: Int, to: Int, delim: String): Int? {
            val c = delim[0]
            val after = i + delim.length
            if (after >= to || s[after].isWhitespace()) return null
            // Intraword underscores (snake_case) and "3*x*y" are not emphasis.
            if (i > 0 && s[i - 1].isLetterOrDigit() && (c == '_' || delim.length == 1)) return null
            var j = after + 1
            while (j < to) {
                j = s.indexOf(delim, j).takeIf { it in 0 until to } ?: return null
                val closeOk = !s[j - 1].isWhitespace() &&
                    (j + delim.length >= to || s[j + delim.length] != c || delim.length == 3) &&
                    !(c == '_' && j + delim.length < to && s[j + delim.length].isLetterOrDigit())
                // A single * must not be the start of a ** run inside.
                if (closeOk && !(delim.length == 1 && j > 0 && s[j - 1] == c)) return j
                j += delim.length
            }
            return null
        }

        companion object {
            val LINK = Regex("\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)")
            val EXPONENT = Regex("\\{[^\\{\\}\\n]{1,20}\\}|-?\\d+(\\.\\d+)?|[a-zA-Z]")
        }
    }

    // ------------------------------------------------------------------ plain text

    /** The answer as clean readable text (for Copy): no Markdown marks, maths as Unicode. */
    fun plainText(text: String): String {
        val blocks = parse(text)
        val sb = StringBuilder()
        blocks.forEachIndexed { idx, b ->
            if (idx > 0) sb.append(if (b is MdBlock.Item && blocks[idx - 1] is MdBlock.Item) "\n" else "\n\n")
            sb.append(
                when (b) {
                    is MdBlock.Paragraph -> plain(b.spans)
                    is MdBlock.Heading -> plain(b.spans)
                    is MdBlock.Item -> "    ".repeat(b.level) + (if (b.ordered) b.marker else "•") + " " + plain(b.spans)
                    MdBlock.Rule -> "———"
                    is MdBlock.Code -> b.text
                    is MdBlock.Quote -> plain(b.spans)
                    is MdBlock.Math -> Latex.plain(b.node)
                    is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString(" | ") { plain(it) } }
                },
            )
        }
        return sb.toString()
    }

    fun plain(spans: List<Span>): String =
        spans.joinToString("") { sp ->
            if (sp.script == Script.NONE) sp.text else Latex.plainOf(listOf(MathRun(sp.text, sp.script)))
        }.lines().joinToString("\n") { it.replace(Regex(" {2,}"), " ").trimEnd() }.trim()
}
