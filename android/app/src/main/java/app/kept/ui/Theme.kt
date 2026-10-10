package app.kept.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.kept.R

/**
 * Kept's look: daylight and glass. A pale lilac sky, white frosted panels, near black type and one
 * violet that always means "kept". The signature picture is a staircase of glass steps that only
 * ever goes up. Tokens live here so the look can change in one place.
 */
data class KeptColors(
    val page: Color,        // the ground
    val skyTop: Color,      // the wash behind everything, top
    val skyBottom: Color,   // and bottom
    val glass: Color,       // frosted panels that float on the page
    val raised: Color,      // quiet fills: chips, fields
    val line: Color,        // hairlines
    val text: Color,        // main text, and the main button
    val dim: Color,         // secondary text
    val accent: Color,      // the one accent: what is kept
    val accentSoft: Color,  // a tint of it, for fills
    val loss: Color,        // what the wallet gave back
    val lossSoft: Color,
    val ok: Color,
)

val LightKept = KeptColors(
    page = Color(0xFFF7F6FB),
    skyTop = Color(0xFFE9E3FF),
    skyBottom = Color(0xFFFBF1EE),
    glass = Color(0xFFFEFDFF),
    raised = Color(0xFFEFEDF6),
    line = Color(0xFFE3E0EE),
    text = Color(0xFF15131F),
    dim = Color(0xFF716E82),
    accent = Color(0xFF6A4BF4),
    accentSoft = Color(0xFFE8E1FF),
    loss = Color(0xFFDB4B3A),
    lossSoft = Color(0xFFFDE8E4),
    ok = Color(0xFF1FA971),
)

val LocalKept = staticCompositionLocalOf { LightKept }

object Kept {
    val colors: KeptColors @Composable get() = LocalKept.current

    /** The wash behind every screen: lilac at the top, clearing to pearl, warming a little at the foot. */
    val sky: Brush
        @Composable get() {
            val c = colors
            return Brush.verticalGradient(0f to c.skyTop, 0.42f to c.page, 0.8f to c.page, 1f to c.skyBottom)
        }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

// Geist for words and numbers, Geist Mono for addresses. Both are SIL OFL.
private val Sans = FontFamily(variable(R.font.geist, 400), variable(R.font.geist, 500), variable(R.font.geist, 600), variable(R.font.geist, 700))
private val Mono = FontFamily(variable(R.font.geist_mono, 400), variable(R.font.geist_mono, 500))

val KeptType = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-2.6).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 41.sp, letterSpacing = (-1.4).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.7).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = (-0.1).sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
)

/** Numbers read as numbers: every digit the same width, so a counting total does not wobble. */
val NumberStyle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

/** Addresses and keys: the one place a monospace earns its keep. */
val AddressStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal)

@Composable
fun KeptTheme(content: @Composable () -> Unit) {
    val k = LightKept
    val scheme = lightColorScheme(
        primary = k.accent, onPrimary = Color.White, background = k.page, onBackground = k.text,
        surface = k.page, onSurface = k.text, surfaceVariant = k.raised, onSurfaceVariant = k.dim, outline = k.line, error = k.loss,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalKept provides k) {
        MaterialTheme(colorScheme = scheme, typography = KeptType, content = content)
    }
}
