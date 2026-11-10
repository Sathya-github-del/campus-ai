package dev.studyapp.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.studyapp.app.core.Tier

/*
 * "Night meter" identity. The app is built around a fare meter: a lit amber readout on a dark
 * panel that says exactly what you used. So the palette is warm espresso-black ink (not the usual
 * blue-grey Material dark), paper-cream text, and lit-segment amber/tangerine as the one accent.
 * Tiers run along a heat scale (warm yellow → tangerine → vermilion): pricier answers burn hotter.
 *
 * Deliberately avoided: Material's default purple, finance green/gold, primary-colour kid palettes,
 * and anything near ChatGPT's teal-on-charcoal. Dark only: the meter reads best lit on a dark panel,
 * and one carefully tuned scheme beats two half-tuned ones.
 */
object Ink {
    val Panel = Color(0xFF0B0806)      // the meter's display well: darker than the page
    val Bg = Color(0xFF130F0C)
    val Surface = Color(0xFF1A1511)
    val Raised = Color(0xFF221B16)
    val Raised2 = Color(0xFF2B231C)
    val Raised3 = Color(0xFF362C24)
    val Line = Color(0xFF4A3C30)
    val LineSoft = Color(0xFF2F261F)
    val Paper = Color(0xFFF6EBDD)      // main text: warm paper, not pure white
    val PaperDim = Color(0xFFBFAE9B)
    val PaperFaint = Color(0xFF8A7A6A)
}

object Lit {
    /** A lit seven-segment digit. */
    val Amber = Color(0xFFFFB547)
    /** The glow halo around lit segments. */
    val Glow = Color(0xFFFF8A1F)
    /** Unlit segments: the faint "8"s you can see on any real meter. */
    val Ghost = Color(0xFF2A1F15)
    val Tangerine = Color(0xFFFF9E2C)
    val Vermilion = Color(0xFFFF5E3A)
    val Marigold = Color(0xFFFFD27A)
}

/** Heat scale: the colour of a tier's icon, heat bars and selected card. */
val Tier.heat: Color
    get() = when (this) {
        Tier.FAST -> Lit.Marigold
        Tier.SMART -> Lit.Tangerine
        Tier.EXPERT -> Lit.Vermilion
    }

private val NightMeter = darkColorScheme(
    primary = Lit.Tangerine,
    onPrimary = Color(0xFF2B1600),
    primaryContainer = Color(0xFF4A2A0A),
    onPrimaryContainer = Color(0xFFFFD9B0),
    inversePrimary = Color(0xFF9A4A00),
    secondary = Color(0xFFE8B98C),
    onSecondary = Color(0xFF3A2410),
    secondaryContainer = Ink.Raised2,
    onSecondaryContainer = Ink.Paper,
    tertiary = Lit.Vermilion,
    onTertiary = Color(0xFF3A0B00),
    tertiaryContainer = Color(0xFF4D1A10),
    onTertiaryContainer = Color(0xFFFFD2C6),
    background = Ink.Bg,
    onBackground = Ink.Paper,
    surface = Ink.Bg,
    onSurface = Ink.Paper,
    surfaceVariant = Ink.Raised2,
    onSurfaceVariant = Ink.PaperDim,
    surfaceTint = Lit.Tangerine,
    inverseSurface = Ink.Paper,
    inverseOnSurface = Ink.Bg,
    error = Color(0xFFFF8E86),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF4A1715),
    onErrorContainer = Color(0xFFFFDAD5),
    outline = Ink.Line,
    outlineVariant = Ink.LineSoft,
    scrim = Color(0xFF000000),
    surfaceBright = Ink.Raised3,
    surfaceDim = Ink.Bg,
    surfaceContainerLowest = Ink.Panel,
    surfaceContainerLow = Ink.Surface,
    surfaceContainer = Ink.Raised,
    surfaceContainerHigh = Ink.Raised2,
    surfaceContainerHighest = Ink.Raised3,
)

/**
 * Serif display type (the system serif, Noto Serif on most phones) for headlines: a calm,
 * editorial voice that sets the app apart from the all-sans AI chat apps. Body stays sans for
 * readability; labels are small, tracked caps like the legends printed on a meter's bezel.
 */
private val Serif = FontFamily.Serif
private val base = Typography()
private val NightType = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    displayMedium = base.displayMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    displaySmall = base.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
)

/** The bezel legend style: "BALANCE", "FARE", "RATE". */
val LegendStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, color = Ink.PaperFaint)

private val NightShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun StudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightMeter, typography = NightType, shapes = NightShapes, content = content)
}
