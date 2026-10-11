package app.kept.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The light a glass slab catches: it runs from aqua through blue and lilac to pink and back. */
private val IRIS = listOf(Color(0xFF63E2F1), Color(0xFF8CA6FF), Color(0xFFC3A6FF), Color(0xFFF6B4E4), Color(0xFFA9F0DD))

/** A colour on the iris wheel. [p] wraps, so p and p + 1 are the same colour. */
internal fun iris(p: Float): Color {
    val x = ((p % 1f) + 1f) % 1f * IRIS.size
    val i = x.toInt() % IRIS.size
    return lerp(IRIS[i], IRIS[(i + 1) % IRIS.size], x - x.toInt())
}

/**
 * The signature picture: a staircase of thin glass slabs climbing across the screen, leaning like
 * a run of dominoes caught mid turn. Each win kept is one slab of coloured glass; the slabs still to
 * come are clear. [lit] of the [total] slabs are coloured, counted from the bottom.
 */
@Composable
fun GlassStairs(lit: Int, total: Int, modifier: Modifier = Modifier, height: Dp = 210.dp) {
    val c = Kept.colors
    val grow = remember { Animatable(0f) }
    LaunchedEffect(lit, total) { grow.snapTo(0f); grow.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }
    val shimmer by rememberInfiniteTransition(label = "iris").animateFloat(
        0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "shimmer",
    )
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = total.coerceAtLeast(2)
        val slabH = size.height * 0.36f
        val pitch = size.width * 0.98f / (n - 1)
        // Each tile is far wider than the gap to the next, so they overlap like dominoes mid fall.
        val wide = pitch * 2.3f
        for (i in 0 until n) {
            val t = i / (n - 1f)
            // each tile falls into place a beat after the one before it
            val g = ((grow.value * (n + 9f) - i) / 9f).coerceIn(0f, 1f)
            if (g <= 0f) continue
            val base = Offset(
                size.width * 0.09f + pitch * i,
                size.height * (0.93f - 0.52f * t) + sin(t * 2f * PI.toFloat()) * size.height * 0.05f,
            )
            // the lean turns over along the run, which is what makes it read as one twisting ribbon
            val lean = (-54f + 92f * t + sin(t * 2f * PI.toFloat()) * 12f + (1f - g) * 30f) * (PI.toFloat() / 180f)
            slab(base, lean, wide, slabH * (0.6f + 0.4f * g), g, on = i < lit, phase = t * 1.2f + shimmer, c = c)
        }
    }
}

private fun DrawScope.slab(base: Offset, lean: Float, thick: Float, h: Float, alpha: Float, on: Boolean, phase: Float, c: KeptColors) {
    val up = Offset(sin(lean), -cos(lean))
    val across = Offset(cos(lean), sin(lean))
    val a = base - across * (thick / 2f)
    val b = base + across * (thick / 2f)
    val top = up * h
    // the slab has a little depth: its far edge shows as a sliver down one side
    val d = minOf(thick * 0.16f, 5.dp.toPx())
    val depth = Offset(d, -d * 0.45f)
    val face = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b.x + top.x, b.y + top.y); lineTo(a.x + top.x, a.y + top.y); close() }
    val edge = Path().apply {
        moveTo(b.x, b.y); lineTo(b.x + depth.x, b.y + depth.y); lineTo(b.x + depth.x + top.x, b.y + depth.y + top.y); lineTo(b.x + top.x, b.y + top.y); close()
    }
    val hair = Stroke(0.8.dp.toPx(), join = StrokeJoin.Round)
    // A tile not earned yet is the same glass with the colour almost drained out of it.
    val wash = if (on) 0f else 0.66f
    val low = lerp(iris(phase), Color.White, wash)
    val high = lerp(iris(phase + 0.34f), Color.White, wash)
    val body = if (on) 0.9f else 0.6f
    drawPath(edge, Brush.linearGradient(listOf(lerp(low, c.accent, if (on) 0.38f else 0.06f), lerp(high, c.accent, if (on) 0.22f else 0.04f)), b, b + top), alpha = alpha)
    drawPath(face, Brush.linearGradient(listOf(low, high), a, a + top), alpha = body * alpha)
    // light sliding across the glass
    drawPath(face, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0f)), a + top, b), alpha = alpha)
    drawPath(face, if (on) Color.White.copy(alpha = 0.9f) else Color(0xFFD9DDEA), alpha = alpha, style = hair)
}

/** How many slabs to draw for a number of wins: every win is one coloured slab, with a few clear ones ahead. */
fun stairCounts(wins: Long): Pair<Int, Int> {
    val lit = wins.coerceIn(0, 30).toInt()
    return lit to (lit + 8).coerceIn(26, 34)
}

/** Kept's mark: three glass slabs, each a step above the last. */
@Composable
fun KeptMark(size: Dp = 30.dp, modifier: Modifier = Modifier) {
    val c = Kept.colors
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        for (i in 0..2) {
            val base = Offset(w * (0.24f + 0.24f * i), h * (0.98f - 0.2f * i))
            slab(base, lean = 16f * (PI.toFloat() / 180f), thick = w * 0.26f, h = h * (0.5f + 0.05f * i), alpha = 1f, on = true, phase = 0.02f + 0.24f * i, c = c)
        }
    }
}

/**
 * Two lines over the same trades: the wallet's balance (jagged, gives wins back) and what was kept
 * (steps that go up on every win and never come down). Values are running totals in SOL, oldest first.
 */
@Composable
fun Staircase(
    wallet: List<Double>,
    kept: List<Double>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    animate: Boolean = true,
) {
    val c = Kept.colors
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(wallet, kept) {
        if (animate) { progress.snapTo(0f); progress.animateTo(1f, tween(1400, easing = LinearEasing)) }
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = maxOf(wallet.size, kept.size)
        if (n < 2) return@Canvas
        val hi = maxOf(wallet.maxOrNull() ?: 0.0, kept.maxOrNull() ?: 0.0)
        val lo = minOf(wallet.minOrNull() ?: 0.0, 0.0)
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = size.width * i / (n - 1)
        fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat()

        drawLine(c.line, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())

        if (wallet.size >= 2) {
            val p = Path()
            wallet.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
            drawPath(p, c.loss.copy(alpha = 0.8f), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        if (kept.size >= 2) {
            val limit = size.width * progress.value
            val line = Path()
            kept.forEachIndexed { i, v ->
                val px = x(i)
                if (i == 0) line.moveTo(px, y(v)) else { line.lineTo(px, y(kept[i - 1])); line.lineTo(px, y(v)) }
            }
            val fill = Path().apply { addPath(line); lineTo(x(kept.size - 1), y(0.0)); lineTo(x(0), y(0.0)); close() }
            clipRect(right = limit) {
                drawPath(fill, Brush.horizontalGradient(listOf(iris(0f), iris(0.25f), iris(0.5f), iris(0.75f))), alpha = 0.32f)
                drawPath(line, c.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
