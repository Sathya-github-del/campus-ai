package dev.quatrioai.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.quatrioai.app.R
import dev.quatrioai.app.core.Latex
import dev.quatrioai.app.core.MathNode
import dev.quatrioai.app.core.MathRun
import dev.quatrioai.app.core.Markdown
import dev.quatrioai.app.core.MdBlock
import dev.quatrioai.app.core.Script
import dev.quatrioai.app.core.Span
import dev.quatrioai.app.core.TableKind

/*
 * Renders an AI answer (Markdown + LaTeX maths, parsed by core/Markdown.kt and core/Latex.kt) in the
 * night-meter style: paper-cream body text, serif headings like the rest of the app, steps and list
 * numbers in lit amber, bullets drawn as small lit meter segments, code in the dark "display well"
 * (Ink.Panel), and display maths laid out in 2-D (stacked fractions, radicals, matrices). A boxed
 * final answer (\boxed{}) gets an amber frame, like a fare landing on the meter.
 */

private val CodeInk = Color(0xFFFFD9B0)
private val MathSize = 19.sp
/** Italic correction: a raised script right after an italic letter would touch it (b²), so nudge it over. */
private val ItalicCorrection = 0.1.em

private fun needsItalicCorrection(italic: Boolean, text: String, next: Script?) = italic && text.length == 1 && next == Script.SUP

@Composable
fun AnswerText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    Column(modifier) {
        blocks.forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.height(gapBefore(blocks[i - 1], b)))
            AnswerBlock(b)
        }
    }
}

private fun gapBefore(prev: MdBlock, b: MdBlock): Dp = when {
    b is MdBlock.Heading -> if (b.level <= 2) 20.dp else 16.dp
    prev is MdBlock.Heading -> 6.dp
    prev is MdBlock.Item && b is MdBlock.Item -> 6.dp
    else -> 12.dp
}

@Composable
private fun AnswerBlock(b: MdBlock) {
    val body = MaterialTheme.typography.bodyLarge.copy(color = Ink.Paper)
    when (b) {
        is MdBlock.Paragraph -> Text(annotate(b.spans), style = body)
        is MdBlock.Heading -> Text(
            annotate(b.spans),
            style = when (b.level) {
                1 -> MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, color = Ink.Paper)
                2 -> MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Ink.Paper)
                // "### Step 1: …": a lit amber step label, like a legend on the meter's bezel.
                else -> MaterialTheme.typography.titleSmall.copy(color = Lit.Amber, letterSpacing = 0.3.sp)
            },
        )
        is MdBlock.Item -> ListItem(b, body)
        MdBlock.Rule -> DashedRule()
        is MdBlock.Code -> CodeBlock(b)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Lit.Tangerine.copy(alpha = 0.55f)))
            Spacer(Modifier.width(12.dp))
            Text(annotate(b.spans), style = body.copy(color = Ink.PaperDim))
        }
        is MdBlock.Math -> MathBlock(b.node)
        is MdBlock.Table -> MarkdownTable(b, body)
    }
}

@Composable
private fun ListItem(b: MdBlock.Item, body: TextStyle) {
    val lineHeight = with(LocalDensity.current) { body.lineHeight.toDp() }
    Row(Modifier.padding(start = 20.dp * b.level)) {
        Box(Modifier.width(if (b.ordered) 28.dp else 20.dp).height(lineHeight), contentAlignment = Alignment.CenterStart) {
            if (b.ordered) {
                Text(b.marker, style = body.copy(color = Lit.Amber, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
            } else {
                // A lit meter segment as the bullet; nested levels are dimmer, like a half-lit digit.
                Box(
                    Modifier.padding(start = 2.dp).size(width = 8.dp, height = 3.dp).clip(RoundedCornerShape(1.5.dp))
                        .background(if (b.level == 0) Lit.Amber else Lit.Amber.copy(alpha = 0.45f)),
                )
            }
        }
        Text(annotate(b.spans), style = body)
    }
}

@Composable
private fun DashedRule() {
    Canvas(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(2.dp)) {
        drawLine(
            Ink.Line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
    }
}

@Composable
private fun CodeBlock(b: MdBlock.Code) {
    val shape = MaterialTheme.shapes.small
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(shape).background(Ink.Panel).border(1.dp, Ink.LineSoft, shape).padding(vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            b.lang?.let { Legend(it, color = Lit.Amber.copy(alpha = 0.7f)) } ?: Spacer(Modifier.width(1.dp))
            IconButton(onClick = { context.copy(b.text) }, modifier = Modifier.size(32.dp)) {
                Icon(painterResource(R.drawable.ic_copy), "Copy code", tint = Ink.PaperDim, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            b.text,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, lineHeight = 20.sp, color = Color(0xFFEBD9C3)),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        )
    }
}

/** Inline spans → styled text. Inline maths is serif with italic variables; scripts are raised/lowered. */
fun annotate(spans: List<Span>): AnnotatedString = buildAnnotatedString {
    spans.forEachIndexed { i, sp ->
        val style = SpanStyle(
            fontWeight = if (sp.bold) FontWeight.SemiBold else null,
            fontStyle = if (sp.italic) FontStyle.Italic else null,
            fontFamily = when {
                sp.code -> FontFamily.Monospace
                sp.math -> FontFamily.Serif
                else -> null
            },
            fontSize = when {
                sp.script != Script.NONE -> 0.72.em
                sp.code -> 0.88.em
                else -> TextUnit.Unspecified
            },
            baselineShift = when (sp.script) {
                Script.SUP -> BaselineShift.Superscript
                Script.SUB -> BaselineShift(-0.25f)
                Script.NONE -> null
            },
            background = if (sp.code) Ink.Raised3 else Color.Unspecified,
            color = if (sp.code) CodeInk else Color.Unspecified,
            textDecoration = if (sp.strike) TextDecoration.LineThrough else null,
            letterSpacing = if (needsItalicCorrection(sp.italic, sp.text, spans.getOrNull(i + 1)?.script)) ItalicCorrection else TextUnit.Unspecified,
        )
        val link = sp.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = Lit.Tangerine, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(sp.text) }
            }
        } else withStyle(style) { append(sp.text) }
    }
}

// ---------------------------------------------------------------------------------- tables

@Composable
private fun MarkdownTable(t: MdBlock.Table, body: TextStyle) {
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    val shape = MaterialTheme.shapes.small
    val cell = body.copy(fontSize = 14.sp, lineHeight = 20.sp)
    // Wide tables scroll sideways; the frame hugs the table rather than the card.
    Box(Modifier.horizontalScroll(rememberScrollState())) {
      Box(Modifier.clip(shape).border(1.dp, Ink.LineSoft, shape)) {
        Grid(cols = cols, align = { Alignment.Start }, hGap = 0.dp, vGap = 0.dp, centerRows = false, maxCellWidth = 220.dp) {
            (listOf(t.header) + t.rows).forEachIndexed { r, row ->
                repeat(cols) { c ->
                    val bg = if (r == 0) Ink.Raised2 else if (r % 2 == 0) Ink.Raised else Color.Transparent
                    Box(Modifier.background(bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            annotate(row.getOrNull(c).orEmpty()),
                            style = if (r == 0) cell.copy(fontWeight = FontWeight.SemiBold, color = Lit.Amber) else cell,
                        )
                    }
                }
            }
        }
      }
    }
}

/**
 * Lays [cols]-wide cells (emitted row by row) out in a grid: each column as wide as its widest cell,
 * each row as tall as its tallest. Cells are stretched to the column width so backgrounds line up.
 */
@Composable
private fun Grid(
    cols: Int,
    align: (Int) -> Alignment.Horizontal,
    hGap: Dp,
    vGap: Dp,
    centerRows: Boolean,
    maxCellWidth: Dp = Dp.Infinity,
    content: @Composable () -> Unit,
) {
    Layout(content) { measurables, _ ->
        if (cols <= 0 || measurables.isEmpty()) return@Layout layout(0, 0) {}
        val maxW = if (maxCellWidth == Dp.Infinity) Constraints.Infinity else maxCellWidth.roundToPx()
        val rows = (measurables.size + cols - 1) / cols
        val colW: IntArray
        val rowH: IntArray
        val placeables: List<androidx.compose.ui.layout.Placeable>
        if (centerRows) {
            // Maths cells: measured once at their natural size, centred in their row.
            placeables = measurables.map { it.measure(Constraints()) }
            colW = IntArray(cols) { c -> (0 until rows).maxOf { r -> placeables.getOrNull(r * cols + c)?.width ?: 0 } }
            rowH = IntArray(rows) { r -> (0 until cols).maxOf { c -> placeables.getOrNull(r * cols + c)?.height ?: 0 } }
        } else {
            // Table cells: size columns and rows from intrinsics, then measure each cell once,
            // stretched to its column and row so the cell backgrounds line up.
            colW = IntArray(cols) { c ->
                (0 until rows).maxOf { r -> measurables.getOrNull(r * cols + c)?.maxIntrinsicWidth(Constraints.Infinity)?.coerceAtMost(maxW) ?: 0 }
            }
            rowH = IntArray(rows) { r ->
                (0 until cols).maxOf { c -> measurables.getOrNull(r * cols + c)?.minIntrinsicHeight(colW[c]) ?: 0 }
            }
            placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(colW[i % cols], rowH[i / cols])) }
        }
        val gx = hGap.roundToPx()
        val gy = vGap.roundToPx()
        val width = colW.sum() + gx * (cols - 1)
        val height = rowH.sum() + gy * (rows - 1)
        layout(width, height) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until cols) {
                    val p = placeables.getOrNull(r * cols + c) ?: continue
                    val dx = align(c).align(p.width, colW[c], layoutDirection)
                    val dy = if (centerRows) (rowH[r] - p.height) / 2 else 0
                    p.place(x + dx, y + dy)
                    x += colW[c] + gx
                }
                y += rowH[r] + gy
            }
        }
    }
}

// ---------------------------------------------------------------------------------- maths

/** A display formula: centred, scrolls sideways if it's wider than the card. */
@Composable
fun MathBlock(node: MathNode, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val min = maxWidth
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.widthIn(min = min).padding(vertical = 4.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Math(node, MathSize)
            }
        }
    }
}

@Composable
private fun Math(n: MathNode, size: TextUnit) {
    if (!Latex.isStructured(n)) {
        MathLine(Latex.runs(n), size)
        return
    }
    when (n) {
        is MathNode.Row -> MathRow(n.items, size)
        is MathNode.Frac -> Fraction(n, size)
        is MathNode.Sqrt -> Radical(n, size)
        is MathNode.Boxed -> Box(
            Modifier.padding(horizontal = 2.dp).clip(MaterialTheme.shapes.small).background(Lit.Amber.copy(alpha = 0.08f))
                .border(1.5.dp, Lit.Amber.copy(alpha = 0.85f), MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) { Math(n.body, size) }
        is MathNode.Scripts -> Row(verticalAlignment = Alignment.CenterVertically) {
            Math(n.base, size)
            val small = smaller(size, 0.7f)
            val lift = with(LocalDensity.current) { (size * 0.9f).toDp() }
            Column(Modifier.padding(start = 1.dp, bottom = if (n.sub == null) lift else 0.dp, top = if (n.sup == null) lift * 0.6f else 0.dp)) {
                n.sup?.let { Math(it, small) }
                n.sub?.let { Math(it, small) }
            }
        }
        is MathNode.Accent -> Math(n.body, size)
        is MathNode.Table -> MathTable(n, size)
        is MathNode.Sym -> MathLine(Latex.runs(n), size)
    }
}

/** A row: runs of simple symbols become one text line; fractions etc. are laid out between them. */
@Composable
private fun MathRow(items: List<MathNode>, size: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        var chunk = mutableListOf<MathNode>()
        var afterAtom = false
        fun flushInto(list: List<MathNode>, cont: Boolean) = Latex.runs(list, continued = cont)
        val parts = mutableListOf<Pair<List<MathRun>?, MathNode?>>()
        for (item in items) {
            if (Latex.isStructured(item)) {
                if (chunk.isNotEmpty()) parts += flushInto(chunk, afterAtom) to null
                chunk = mutableListOf()
                parts += null to item
                afterAtom = true
            } else chunk += item
        }
        if (chunk.isNotEmpty()) parts += flushInto(chunk, afterAtom) to null
        parts.forEach { (runs, node) ->
            if (runs != null) MathLine(runs, size) else Math(node!!, size)
        }
    }
}

@Composable
private fun MathLine(runs: List<MathRun>, size: TextUnit) {
    val text = buildAnnotatedString {
        runs.forEachIndexed { i, r ->
            withStyle(
                SpanStyle(
                    fontStyle = if (r.italic) FontStyle.Italic else null,
                    letterSpacing = if (needsItalicCorrection(r.italic, r.text, runs.getOrNull(i + 1)?.script)) ItalicCorrection else TextUnit.Unspecified,
                    fontSize = if (r.script == Script.NONE) TextUnit.Unspecified else 0.7.em,
                    baselineShift = when (r.script) {
                        Script.SUP -> BaselineShift.Superscript
                        Script.SUB -> BaselineShift(-0.25f)
                        Script.NONE -> null
                    },
                ),
            ) { append(r.text) }
        }
    }
    Text(text, style = TextStyle(fontFamily = FontFamily.Serif, fontSize = size, color = Ink.Paper), softWrap = false)
}

private fun smaller(size: TextUnit, f: Float): TextUnit = (size.value * f).coerceAtLeast(12f).sp

@Composable
private fun Fraction(n: MathNode.Frac, size: TextUnit) {
    val part = smaller(size, 0.88f)
    val inner = @Composable {
        Column(Modifier.width(IntrinsicSize.Max).padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Math(n.num, part)
            if (n.line) Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.2.dp).background(Ink.Paper))
            else Spacer(Modifier.height(6.dp))
            Math(n.den, part)
        }
    }
    if (n.line) inner() else Row(Modifier.height(IntrinsicSize.Min)) {
        Fence(TableKind.PMATRIX, left = true); inner(); Fence(TableKind.PMATRIX, left = false)
    }
}

@Composable
private fun Radical(n: MathNode.Sqrt, size: TextUnit) {
    val stroke = with(LocalDensity.current) { 1.3.dp.toPx() }
    Row(verticalAlignment = Alignment.Top) {
        n.index?.let { Box(Modifier.padding(top = 2.dp)) { Math(it, smaller(size, 0.6f)) } }
        Box(
            Modifier
                .drawBehind {
                    val h = this.size.height
                    val w = this.size.width
                    val s = 12.dp.toPx()
                    val top = stroke
                    val path = Path().apply {
                        moveTo(0f, h * 0.62f)
                        lineTo(s * 0.3f, h * 0.55f)
                        lineTo(s * 0.62f, h - stroke)
                        lineTo(s * 0.95f, top)
                        lineTo(w, top)
                    }
                    drawPath(path, Ink.Paper, style = Stroke(width = stroke))
                }
                .padding(start = 14.dp, top = 5.dp, end = 2.dp, bottom = 1.dp),
        ) { Math(n.body, size) }
    }
}

@Composable
private fun MathTable(t: MathNode.Table, size: TextUnit) {
    val cols = t.rows.maxOf { it.size }
    val grid = @Composable { align: (Int) -> Alignment.Horizontal, hGap: Dp ->
        Grid(cols = cols, align = align, hGap = hGap, vGap = 8.dp, centerRows = true) {
            t.rows.forEach { row -> repeat(cols) { c -> row.getOrNull(c)?.let { Math(it, size) } ?: Box(Modifier) } }
        }
    }
    when (t.kind) {
        // Derivation lines: "x &= 5": right-align left of the &, left-align after it, so the = signs line up.
        TableKind.LINES -> grid({ c -> if (cols == 1) Alignment.CenterHorizontally else if (c % 2 == 0) Alignment.End else Alignment.Start }, 0.dp)
        TableKind.CASES -> Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Fence(TableKind.CASES, left = true)
            Spacer(Modifier.width(6.dp))
            grid({ Alignment.Start }, 18.dp)
        }
        else -> Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            if (t.kind != TableKind.MATRIX) Fence(t.kind, left = true)
            Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) { grid({ Alignment.CenterHorizontally }, 18.dp) }
            if (t.kind != TableKind.MATRIX) Fence(t.kind, left = false)
        }
    }
}

/** A tall bracket drawn to the height of its row: ( [ | or the cases brace. */
@Composable
private fun Fence(kind: TableKind, left: Boolean) {
    Canvas(Modifier.width(if (kind == TableKind.CASES) 12.dp else 7.dp).fillMaxHeight().padding(vertical = 2.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.4.dp.toPx()
        val path = Path()
        fun x(v: Float) = if (left) v else w - v
        when (kind) {
            TableKind.PMATRIX -> { path.moveTo(x(w * 0.95f), 0f); path.quadraticTo(x(-w * 0.35f), h / 2, x(w * 0.95f), h) }
            TableKind.VMATRIX -> { path.moveTo(x(w * 0.5f), 0f); path.lineTo(x(w * 0.5f), h) }
            TableKind.CASES -> {
                val m = w * 0.5f
                path.moveTo(x(w), 0f)
                path.quadraticTo(x(m), 0f, x(m), h * 0.12f)
                path.lineTo(x(m), h * 0.4f)
                path.quadraticTo(x(m), h * 0.5f, x(0f), h * 0.5f)
                path.quadraticTo(x(m), h * 0.5f, x(m), h * 0.6f)
                path.lineTo(x(m), h * 0.88f)
                path.quadraticTo(x(m), h, x(w), h)
            }
            else -> { path.moveTo(x(w), 0f); path.lineTo(x(w * 0.25f), 0f); path.lineTo(x(w * 0.25f), h); path.lineTo(x(w), h) }
        }
        drawPath(path, Ink.Paper, style = Stroke(width = sw))
    }
}

