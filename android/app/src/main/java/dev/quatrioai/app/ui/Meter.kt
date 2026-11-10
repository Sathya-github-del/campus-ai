package dev.quatrioai.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.quatrioai.app.core.MeterText
import dev.quatrioai.app.core.Money
import kotlin.math.abs
import kotlin.math.min

/*
 * The meter motif. Every place the app talks about money uses the same lit seven-segment readout,
 * drawn here on a Canvas (no font files): unlit segments stay faintly visible as ghost "8"s like a
 * real fare meter, lit ones glow amber, digits are slightly slanted, and changes tick through every
 * paisa in between and then "land" with a brief flash.
 */

// Segment bits: a = top, b = upper right, c = lower right, d = bottom, e = lower left, f = upper left, g = middle.
private const val A = 1; private const val B = 2; private const val C = 4; private const val D = 8
private const val E = 16; private const val F = 32; private const val G = 64
private val DIGITS = intArrayOf(
    A or B or C or D or E or F, B or C, A or B or G or E or D, A or B or G or C or D, F or G or B or C,
    A or F or G or C or D, A or F or G or E or C or D, A or B or C, 127, A or B or C or D or F or G,
)
/** The outer ring, in drawing order, for the "meter running" chase. */
private val RING = intArrayOf(A, B, C, D, E, F)

private data class Cell(val mask: Int, val dot: Boolean = false)

private fun cellsOf(text: String): List<Cell> {
    val out = ArrayList<Cell>(text.length)
    for (ch in text) {
        when {
            ch == '.' -> if (out.isEmpty()) out += Cell(0, dot = true) else out[out.lastIndex] = out.last().copy(dot = true)
            ch.isDigit() -> out += Cell(DIGITS[ch - '0'])
            ch == '-' -> out += Cell(G)
            else -> out += Cell(0)
        }
    }
    return out
}

// Proportions of one digit cell, relative to its height.
private const val CELL_W = 0.54f
private const val THICK = 0.13f
private const val SPACING = 0.24f
private const val SLANT = 0.08f

private fun widthFor(cells: Int, heightPx: Float): Float =
    if (cells == 0) 0f else cells * CELL_W * heightPx + (cells - 1) * SPACING * heightPx + heightPx * SLANT + SPACING * heightPx * 0.5f

/**
 * A static seven-segment readout of [text] (digits, '.', '-', and spaces for unlit cells).
 * [flash] (0..1) brightens the glow, used for the "fare landed" moment.
 */
@Composable
fun SegmentDisplay(
    text: String,
    digitHeight: Dp,
    modifier: Modifier = Modifier,
    lit: Color = Lit.Amber,
    ghost: Color = Lit.Ghost,
    flash: Float = 0f,
) = SegmentCells(cellsOf(text), digitHeight, modifier, lit, ghost, flash)

@Composable
private fun SegmentCells(
    cells: List<Cell>,
    digitHeight: Dp,
    modifier: Modifier,
    lit: Color,
    ghost: Color,
    flash: Float,
) {
    val density = LocalDensity.current
    val hPx = with(density) { digitHeight.toPx() }
    val wDp = with(density) { widthFor(cells.size, hPx).toDp() }
    Canvas(modifier.size(wDp, digitHeight)) {
        val h = size.height
        val w = CELL_W * h
        val t = THICK * h
        val pitch = w + SPACING * h
        val litColor = lerp(lit, Color.White, flash * 0.35f)
        val glowAlpha = 0.16f + 0.5f * flash
        cells.forEachIndexed { i, cell ->
            val x0 = i * pitch
            val ghostPath = Path()
            val litPath = Path()
            for (bit in intArrayOf(A, B, C, D, E, F, G)) {
                segment(if (cell.mask and bit != 0) litPath else ghostPath, bit, x0, w, h, t)
            }
            dot(if (cell.dot) litPath else ghostPath, x0 + w + SPACING * h * 0.38f, h, t)
            drawPath(ghostPath, ghost)
            if (!litPath.isEmpty) {
                drawPath(litPath, Lit.Glow.copy(alpha = glowAlpha), style = Stroke(width = t * (0.45f + 0.5f * flash), join = StrokeJoin.Round))
                drawPath(litPath, litColor)
            }
        }
    }
}

/** Adds one hexagonal segment to [p], slanted like a real LED meter. */
private fun segment(p: Path, bit: Int, x0: Float, w: Float, h: Float, t: Float) {
    val gap = t * 0.14f
    val half = t / 2f
    fun sx(x: Float, y: Float) = x0 + x + (h - y) * SLANT
    fun horiz(yc: Float) {
        val l = half + gap; val r = w - half - gap
        p.moveTo(sx(l, yc), yc); p.lineTo(sx(l + half, yc - half), yc - half); p.lineTo(sx(r - half, yc - half), yc - half)
        p.lineTo(sx(r, yc), yc); p.lineTo(sx(r - half, yc + half), yc + half); p.lineTo(sx(l + half, yc + half), yc + half); p.close()
    }
    fun vert(xc: Float, top: Float, bottom: Float) {
        val a = top + gap; val b = bottom - gap
        p.moveTo(sx(xc, a), a); p.lineTo(sx(xc + half, a + half), a + half); p.lineTo(sx(xc + half, b - half), b - half)
        p.lineTo(sx(xc, b), b); p.lineTo(sx(xc - half, b - half), b - half); p.lineTo(sx(xc - half, a + half), a + half); p.close()
    }
    when (bit) {
        A -> horiz(half)
        B -> vert(w - half, half, h / 2f)
        C -> vert(w - half, h / 2f, h - half)
        D -> horiz(h - half)
        E -> vert(half, h / 2f, h - half)
        F -> vert(half, half, h / 2f)
        G -> horiz(h / 2f)
    }
}

private fun dot(p: Path, cx: Float, h: Float, t: Float) {
    val r = t * 0.55f
    val cy = h - r
    p.addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r))
}

/**
 * An amount on the meter that ticks to new values. On first show it starts at [startFrom] (e.g. 0
 * for a fare that has just been charged) or, if null, directly at [amount]. Every later change ticks
 * through the paise in between and lands with a flash, so a balance visibly counts down by the fare.
 */
@Composable
fun MeterReadout(
    amount: Double,
    digitHeight: Dp,
    modifier: Modifier = Modifier,
    minIntDigits: Int = 1,
    startFrom: Double? = null,
    lit: Color = Lit.Amber,
) {
    val value = remember { Animatable((startFrom ?: amount).toFloat()) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(amount) {
        val target = amount.toFloat()
        if (value.value != target) {
            val paise = abs(target - value.value) * 100f
            // Small fares tick quickly; big top-ups run a little longer but never drag.
            val ms = (380 + 1100 * min(1f, paise / 400f)).toInt()
            value.animateTo(target, tween(ms, easing = LinearOutSlowInEasing))
            flash.animateTo(1f, snap())
            flash.animateTo(0f, tween(650))
        }
    }
    SegmentDisplay(
        text = MeterText.of(value.value.toDouble(), minIntDigits),
        digitHeight = digitHeight,
        lit = lit,
        flash = flash.value,
        modifier = modifier.semantics { contentDescription = Money.cost(amount) },
    )
}

/** The rupee sign printed beside a readout, sized to sit on its baseline. */
@Composable
fun RupeeSign(digitHeight: Dp, color: Color = Lit.Amber) {
    val sp = with(LocalDensity.current) { (digitHeight * 0.78f).toSp() }
    Text("₹", color = color, fontSize = sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = digitHeight * 0.12f))
}

/** ₹ + ticking readout. */
@Composable
fun Fare(
    amount: Double,
    digitHeight: Dp,
    modifier: Modifier = Modifier,
    minIntDigits: Int = 1,
    startFrom: Double? = null,
    lit: Color = Lit.Amber,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RupeeSign(digitHeight, lit)
        MeterReadout(amount, digitHeight, minIntDigits = minIntDigits, startFrom = startFrom, lit = lit)
    }
}

/** "Meter running": a lit segment chases around each digit while an answer is on its way. */
@Composable
fun MeterRunning(digitHeight: Dp, modifier: Modifier = Modifier, cells: Int = 4, lit: Color = Lit.Amber) {
    val phase by rememberInfiniteTransition(label = "meter").animateFloat(
        initialValue = 0f, targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(780, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val step = phase.toInt()
    val list = List(cells) { i ->
        val k = (step + i) % 6
        Cell(RING[k] or RING[(k + 5) % 6], dot = i == cells - 3)
    }
    SegmentCells(list, digitHeight, modifier.semantics { contentDescription = "Meter running" }, lit, Lit.Ghost, 0f)
}

/** Cost-intensity glyph: 1, 2 or 3 rising bars lit in the tier's heat colour. */
@Composable
fun HeatBars(level: Int, color: Color, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Canvas(modifier.size(width = height * 1.3f, height = height)) {
        val bw = size.width / 4.2f
        for (i in 0 until 3) {
            val bh = size.height * (0.45f + 0.275f * i)
            val x = i * bw * 1.6f
            drawRoundRect(
                color = if (i < level) color else Lit.Ghost,
                topLeft = Offset(x, size.height - bh),
                size = androidx.compose.ui.geometry.Size(bw, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 3f),
            )
        }
    }
}

/** The dark "display well" every readout sits in, with a bezel line and a faint glass highlight. */
@Composable
fun MeterPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    border: Color = Ink.Line,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(Ink.Panel)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.035f), Color.Transparent), endY = size.height * 0.5f))
            }
            .border(1.dp, border, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Small tracked caps legend, like the labels printed on a meter's bezel. */
@Composable
fun Legend(text: String, modifier: Modifier = Modifier, color: Color = Ink.PaperFaint) =
    Text(text.uppercase(), style = LegendStyle, color = color, modifier = modifier)

/**
 * The balance in the top bar: a compact meter you can tap to add fare. Ticks down visibly as each
 * answer's fare is taken, and up after a top-up.
 */
@Composable
fun BalanceMeter(balance: Double?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val low = balance != null && balance < 1.0
    MeterPanel(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Add fare", onClick = onClick)
            .semantics { contentDescription = "Balance ${balance?.let(Money::inr) ?: "loading"}. Add fare." },
        shape = MaterialTheme.shapes.small,
        border = if (low) Lit.Vermilion.copy(alpha = 0.7f) else Ink.Line,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Legend("Balance", color = if (low) Lit.Vermilion else Ink.PaperFaint)
                Legend("+ Add", color = Lit.Tangerine)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RupeeSign(16.dp)
                if (balance == null) SegmentDisplay("--.--", 16.dp) else MeterReadout(balance, 16.dp)
            }
        }
    }
}

/**
 * A receipt tear line: dashes across the card with a half-circle notch cut into each edge.
 * [notch] should match whatever is behind the card.
 */
@Composable
fun Perforation(modifier: Modifier = Modifier, notch: Color = Ink.Bg, line: Color = Ink.Line) {
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val r = size.height / 2f
        drawLine(
            line, Offset(r * 1.6f, r), Offset(size.width - r * 1.6f, r), strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
        notchAt(Offset(0f, r), r, notch)
        notchAt(Offset(size.width, r), r, notch)
    }
}

private fun DrawScope.notchAt(c: Offset, r: Float, color: Color) = drawCircle(color, r, c)

/** Width helper so callers can reserve space for a readout of [cells] digits. */
@Composable
fun meterWidth(cells: Int, digitHeight: Dp): Dp = with(LocalDensity.current) { widthFor(cells, digitHeight.toPx()).toDp() }
