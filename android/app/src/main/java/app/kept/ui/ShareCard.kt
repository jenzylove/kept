package app.kept.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import app.kept.R
import java.io.File
import java.util.Locale

/**
 * The shareable card: what the wallet won, what Kept banked, and the staircase between them.
 * Drawn with plain Canvas so it needs nothing from the network and looks the same everywhere.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1350

    private val INK = 0xFF0E0F0C.toInt()
    private val BONE = 0xFFF2EDE4.toInt()
    private val DIM = 0xFF9A978D.toInt()
    private val BRASS = 0xFFD6A94A.toInt()
    private val LOSS = 0xFFCB5B4A.toInt()
    private val LINE = 0xFF2A2B24.toInt()

    fun render(context: Context, wonLamports: Long, keptLamports: Long, wins: Int, wallet: List<Double>, kept: List<Double>): Bitmap {
        val display = variable(context, R.font.fraunces, "'wght' 500, 'opsz' 144, 'SOFT' 0, 'WONK' 0") ?: Typeface.SERIF
        val mono = variable(context, R.font.jetbrains_mono, "'wght' 400") ?: ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(INK)

        fun paint(color: Int, size: Float, face: Typeface, spacing: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = face; letterSpacing = spacing
        }
        fun sol(l: Long) = String.format(Locale.US, if (l < 1_000_000_000L) "%.3f" else "%.2f", l / 1e9)

        // mark and name
        val mark = Path().apply {
            moveTo(80f, 140f); lineTo(80f, 110f); lineTo(122f, 68f); lineTo(122f, 104f); lineTo(164f, 68f); lineTo(164f, 140f); close()
        }
        c.drawPath(mark, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRASS })
        c.drawText("Kept", 190f, 128f, paint(BONE, 52f, display))

        // headline numbers
        c.drawText("WON", 80f, 330f, paint(DIM, 30f, mono, 0.14f))
        c.drawText("${sol(wonLamports)} SOL", 80f, 440f, paint(BONE, 120f, display, -0.03f))
        c.drawText("KEPT", 80f, 560f, paint(DIM, 30f, mono, 0.14f))
        c.drawText("${sol(keptLamports)} SOL", 80f, 670f, paint(BRASS, 120f, display, -0.03f))
        c.drawText("across $wins ${if (wins == 1) "win" else "wins"}", 80f, 730f, paint(DIM, 36f, Typeface.SANS_SERIF))

        // the staircase
        val left = 80f; val right = W - 80f; val top = 800f; val bottom = 1120f
        val n = maxOf(wallet.size, kept.size)
        if (n >= 2) {
            val hi = maxOf(wallet.maxOrNull() ?: 0.0, kept.maxOrNull() ?: 0.0)
            val lo = minOf(wallet.minOrNull() ?: 0.0, 0.0)
            val span = (hi - lo).takeIf { it > 0 } ?: 1.0
            fun x(i: Int) = left + (right - left) * i / (n - 1)
            fun y(v: Double) = (bottom - (v - lo) / span * (bottom - top)).toFloat()
            c.drawLine(left, y(0.0), right, y(0.0), Paint().apply { color = LINE; strokeWidth = 2f })
            val jag = Path()
            wallet.forEachIndexed { i, v -> if (i == 0) jag.moveTo(x(i), y(v)) else jag.lineTo(x(i), y(v)) }
            c.drawPath(jag, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LOSS; style = Paint.Style.STROKE; strokeWidth = 4f; alpha = 220 })
            val stairs = Path()
            kept.forEachIndexed { i, v ->
                if (i == 0) stairs.moveTo(x(i), y(v)) else { stairs.lineTo(x(i), y(kept[i - 1])); stairs.lineTo(x(i), y(v)) }
            }
            c.drawPath(stairs, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRASS; style = Paint.Style.STROKE; strokeWidth = 8f; strokeJoin = Paint.Join.MITER })
        }

        c.drawText("A TRADING WALLET WITH A REFLEX", 80f, 1250f, paint(DIM, 28f, mono, 0.14f))
        c.drawText("kept-seeker.vercel.app", 80f, 1295f, paint(DIM, 30f, mono))
        return bmp
    }

    /** A variable font at specific axis settings, from the bundled resource. Null if the platform can't. */
    private fun variable(context: Context, res: Int, settings: String): Typeface? = runCatching {
        val f = File(context.cacheDir, "font-$res.ttf")
        if (!f.exists()) context.resources.openRawResource(res).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        Typeface.Builder(f).setFontVariationSettings(settings).build()
    }.getOrNull()

    /** Render the card and open the share sheet. */
    fun share(context: Context, wonLamports: Long, keptLamports: Long, wins: Int, wallet: List<Double>, kept: List<Double>) {
        val bmp = render(context, wonLamports, keptLamports, wins, wallet, kept)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "kept-card.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val text = "I kept ${String.format(Locale.US, if (keptLamports < 1_000_000_000L) "%.3f" else "%.2f", keptLamports / 1e9)} SOL of my trading wins with Kept. kept-seeker.vercel.app"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share your keeping").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
