package app.kept.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kept.BuildConfig
import java.util.Locale

fun fmtSol(lamports: Long, digits: Int = 4): String = String.format(Locale.US, "%.${digits}f", lamports / 1e9)
fun fmtSol(lamports: java.math.BigInteger, digits: Int = 4): String = String.format(Locale.US, "%.${digits}f", lamports.toDouble() / 1e9)

fun fmtDuration(secs: Long): String = when {
    secs < 90 -> "$secs sec"
    secs < 5_400 -> "${(secs + 30) / 60} min"
    secs < 172_800 -> "${(secs + 1_800) / 3_600} hours"
    else -> "${(secs + 43_200) / 86_400} days"
}

fun fmtCountdown(secs: Long): String {
    if (secs <= 0) return "now"
    val d = secs / 86_400; val h = (secs % 86_400) / 3_600; val m = (secs % 3_600) / 60; val s = secs % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

fun fmtAgo(nowSec: Long, thenSec: Long): String {
    val d = (nowSec - thenSec).coerceAtLeast(0)
    return when {
        d < 60 -> "just now"
        d < 3_600 -> "${d / 60}m ago"
        d < 86_400 -> "${d / 3_600}h ago"
        else -> "${d / 86_400}d ago"
    }
}

fun short(s: String, n: Int = 4) = if (s.length > 2 * n + 1) s.take(n) + "…" + s.takeLast(n) else s

/** Opens a transaction or address in the explorer, on the right cluster. */
fun openExplorer(context: Context, kind: String, id: String) {
    val cluster = if (BuildConfig.CLUSTER == "mainnet-beta") "" else "?cluster=${BuildConfig.CLUSTER}"
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://solscan.io/$kind/$id$cluster")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun KeptButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val c = Kept.colors
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.brass, contentColor = c.ink, disabledContainerColor = c.line, disabledContentColor = c.dim),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp), color = c.ink)
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun KeptGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Kept.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.bone, disabledContentColor = c.dim),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** A small uppercase label above a value. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Kept.colors.dim, modifier = modifier)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Kept.colors.line))
}

/** A label and a number on one row, separated by a hairline below. */
@Composable
fun StatRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = Kept.colors.bone, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Kept.colors.dim)
            Text(value, style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Hairline()
    }
}

@Composable
fun Notice(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val c = Kept.colors
    Row(modifier.fillMaxWidth().background(c.raised).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.height(36.dp).padding(end = 12.dp).background(if (isError) c.loss else c.brass).padding(horizontal = 1.5.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.bone)
    }
}

val ScreenPadding: Dp = 24.dp
