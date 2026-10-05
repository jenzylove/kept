package app.kept.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.kept.R

/**
 * Kept's look: a ratchet. Dark ink and warm bone, one brass accent. The signature visual is a
 * staircase that only steps up (what you kept) beside a jagged line (what the wallet did).
 * Tokens live here so the look can change in one place.
 */
data class KeptColors(
    val ink: Color,        // page
    val raised: Color,     // surfaces that sit above the page
    val line: Color,       // hairlines
    val bone: Color,       // main text
    val dim: Color,        // secondary text
    val brass: Color,      // the one accent: what is kept
    val loss: Color,       // what the wallet gave back
    val ok: Color,
)

val DarkKept = KeptColors(
    ink = Color(0xFF0E0F0C),
    raised = Color(0xFF171813),
    line = Color(0xFF2A2B24),
    bone = Color(0xFFF2EDE4),
    dim = Color(0xFF9A978D),
    brass = Color(0xFFD6A94A),
    loss = Color(0xFFCB5B4A),
    ok = Color(0xFF7FB37A),
)

val LightKept = KeptColors(
    ink = Color(0xFFF4EFE6),
    raised = Color(0xFFEAE4D8),
    line = Color(0xFFD3CCBC),
    bone = Color(0xFF14150F),
    dim = Color(0xFF6B685E),
    brass = Color(0xFF8A6512),
    loss = Color(0xFFA63D2E),
    ok = Color(0xFF3F7A3A),
)

val LocalKept = staticCompositionLocalOf { DarkKept }

object Kept {
    val colors: KeptColors @Composable get() = LocalKept.current
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

// Fraunces (display) and JetBrains Mono (numbers): the same pair the site uses. Both are SIL OFL.
private val Mono = FontFamily(variable(R.font.jetbrains_mono, 400), variable(R.font.jetbrains_mono, 500), variable(R.font.jetbrains_mono, 600))
private val Display = FontFamily(variable(R.font.fraunces, 400), variable(R.font.fraunces, 500), variable(R.font.fraunces, 600))

val KeptType = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

/** Numbers read as numbers: monospace, tabular. */
val NumberStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium)

@Composable
fun KeptTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val k = if (dark) DarkKept else LightKept
    val scheme: ColorScheme = if (dark) {
        darkColorScheme(primary = k.brass, onPrimary = k.ink, background = k.ink, onBackground = k.bone, surface = k.ink, onSurface = k.bone, surfaceVariant = k.raised, onSurfaceVariant = k.dim, outline = k.line, error = k.loss)
    } else {
        lightColorScheme(primary = k.brass, onPrimary = k.ink, background = k.ink, onBackground = k.bone, surface = k.ink, onSurface = k.bone, surfaceVariant = k.raised, onSurfaceVariant = k.dim, outline = k.line, error = k.loss)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalKept provides k) {
        MaterialTheme(colorScheme = scheme, typography = KeptType, content = content)
    }
}
