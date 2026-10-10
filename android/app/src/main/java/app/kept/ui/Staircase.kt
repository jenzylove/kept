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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The signature picture: a staircase of glass steps, one for each win kept, that only ever goes up.
 * A pearl rests on the top step. With nothing kept yet the steps are drawn as faint outlines, waiting.
 * [steps] are heights from 0 to 1, lowest first.
 */
@Composable
fun GlassStairs(steps: List<Float>, modifier: Modifier = Modifier, height: Dp = 170.dp) {
    val c = Kept.colors
    val ghost = steps.isEmpty()
    val bars = if (ghost) listOf(0.22f, 0.36f, 0.52f, 0.7f, 0.9f) else steps
    val grow = remember { Animatable(0f) }
    LaunchedEffect(bars) { grow.snapTo(0f); grow.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }
    val float by rememberInfiniteTransition(label = "pearl").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
    )

    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = bars.size
        val depth = 14.dp.toPx()          // how far the top and side faces lean back
        val rise = depth * 0.55f
        val gap = 10.dp.toPx()
        val pearlR = 15.dp.toPx()
        val usable = size.width - depth
        val w = ((usable - gap * (n - 1)) / n).coerceAtMost(64.dp.toPx())
        val left = (usable - (w * n + gap * (n - 1))) / 2f
        val floor = size.height - 6.dp.toPx()
        val tallest = floor - rise - pearlR * 2.6f

        var topOfLast = Offset(left + w / 2f, floor)
        bars.forEachIndexed { i, h ->
            // each step starts a beat after the one before it
            val p = ((grow.value * (n + 2f) - i) / 3f).coerceIn(0f, 1f)
            val x = left + i * (w + gap)
            val top = floor - (h.coerceIn(0.08f, 1f) * tallest) * p
            step(x, top, floor, w, depth, rise, c, ghost, emphasised = !ghost && i == n - 1)
            if (i == n - 1) topOfLast = Offset(x + w / 2f + depth / 2f, top - rise / 2f)
        }
        if (!ghost) pearl(Offset(topOfLast.x, topOfLast.y - pearlR * 1.15f - float * 5.dp.toPx()), pearlR, c)
    }
}

private fun DrawScope.step(x: Float, top: Float, floor: Float, w: Float, depth: Float, rise: Float, c: KeptColors, ghost: Boolean, emphasised: Boolean) {
    val front = Path().apply { moveTo(x, top); lineTo(x + w, top); lineTo(x + w, floor); lineTo(x, floor); close() }
    val lid = Path().apply { moveTo(x, top); lineTo(x + depth, top - rise); lineTo(x + w + depth, top - rise); lineTo(x + w, top); close() }
    val side = Path().apply { moveTo(x + w, top); lineTo(x + w + depth, top - rise); lineTo(x + w + depth, floor - rise); lineTo(x + w, floor); close() }
    // Steps not earned yet are the same glass, almost clear.
    val strong = if (ghost) 0.16f else if (emphasised) 1f else 0.72f
    drawPath(side, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.62f * strong), c.accent.copy(alpha = 0.22f * strong)), top - rise, floor))
    drawPath(front, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.92f * strong), c.accent.copy(alpha = 0.30f * strong)), top, floor))
    drawPath(lid, Brush.linearGradient(listOf(Color.White, c.accentSoft), Offset(x, top - rise), Offset(x + w + depth, top)))
    // the light catching the near edges
    drawLine(Color.White.copy(alpha = 0.75f), Offset(x, top), Offset(x + w, top), 1.2.dp.toPx(), StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.35f), Offset(x + 1.dp.toPx(), top), Offset(x + 1.dp.toPx(), floor), 1.dp.toPx())
    if (ghost) drawPath(lid, c.accent.copy(alpha = 0.10f))
}

private fun DrawScope.pearl(center: Offset, r: Float, c: KeptColors) {
    // a small soft shadow where it rests
    drawOval(c.accent.copy(alpha = 0.22f), topLeft = center + Offset(-r * 0.7f, r * 0.92f), size = androidx.compose.ui.geometry.Size(r * 1.4f, r * 0.34f))
    drawCircle(
        Brush.radialGradient(listOf(Color.White, c.accentSoft, c.accent.copy(alpha = 0.85f)), center + Offset(-r * 0.35f, -r * 0.4f), r * 1.7f),
        r, center,
    )
    drawCircle(Color.White.copy(alpha = 0.9f), r * 0.22f, center + Offset(-r * 0.38f, -r * 0.42f))
}

/** Heights for [GlassStairs] from the running total kept after each trade: one step for each time it rose. */
fun stairSteps(keptTotals: List<Double>, max: Int = 6): List<Float> {
    val rises = ArrayList<Double>()
    var last = 0.0
    for (v in keptTotals) if (v > last + 1e-12) { rises.add(v); last = v }
    val shown = rises.takeLast(max)
    val top = shown.lastOrNull() ?: return emptyList()
    if (shown.size == 1) return listOf(0.55f)
    // Keep the lowest step visible even when the first win was small next to the total.
    return shown.map { (0.18 + 0.82 * (it / top)).toFloat() }
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
                drawPath(fill, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.20f), c.accent.copy(alpha = 0.02f))))
                drawPath(line, c.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
