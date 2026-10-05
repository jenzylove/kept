package app.kept.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The signature picture. Two lines over the same trades: the wallet's balance (jagged, gives wins
 * back) and what was kept (a staircase that steps up on every win and never steps down).
 * Values are cumulative SOL after each trade, oldest first.
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

        // zero line
        drawLine(c.line, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())

        // wallet: jagged
        if (wallet.size >= 2) {
            val p = Path()
            wallet.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
            drawPath(p, c.loss.copy(alpha = 0.85f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // kept: stair steps, drawn up to `progress`
        if (kept.size >= 2) {
            val limit = size.width * progress.value
            val p = Path()
            kept.forEachIndexed { i, v ->
                val px = x(i)
                if (i == 0) p.moveTo(px, y(v)) else { p.lineTo(px, y(kept[i - 1])); p.lineTo(px, y(v)) }
            }
            clipRect(right = limit) { drawPath(p, c.brass, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter)) }
        }
    }
}
