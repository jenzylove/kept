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
 * Kept's look: a white room and coloured glass. Near black type on an off white page, one indigo
 * that always means "kept", and one word of each headline set in an italic serif. The signature
 * picture is a staircase of glass slabs that catch the light and only ever climb. Tokens live here
 * so the look can change in one place.
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
    page = Color(0xFFF6F7F9),
    skyTop = Color(0xFFFFFFFF),
    skyBottom = Color(0xFFF1F2F6),
    glass = Color(0xFFFFFFFF),
    raised = Color(0xFFECEEF3),
    line = Color(0xFFE1E4EB),
    text = Color(0xFF121217),
    dim = Color(0xFF6A6E7A),
    accent = Color(0xFF4A3AE8),
    accentSoft = Color(0xFFEAE8FF),
    loss = Color(0xFFD8452F),
    lossSoft = Color(0xFFFCE9E5),
    ok = Color(0xFF18A46B),
)

val LocalKept = staticCompositionLocalOf { LightKept }

object Kept {
    val colors: KeptColors @Composable get() = LocalKept.current

    /** The wash behind every screen: white at the top, settling to a soft grey at the foot. */
    val sky: Brush
        @Composable get() {
            val c = colors
            return Brush.verticalGradient(0f to c.skyTop, 0.5f to c.page, 1f to c.skyBottom)
        }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

// Geist for words and numbers, Instrument Serif Italic for the one word a headline leans on,
// Geist Mono for addresses. All SIL OFL.
private val Sans = FontFamily(variable(R.font.geist, 400), variable(R.font.geist, 500), variable(R.font.geist, 600), variable(R.font.geist, 700))
private val Serif = FontFamily(Font(R.font.instrument_serif_italic))
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

/** The one word a headline leans on: italic serif, in the kept colour. */
val LeanStyle = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)

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
