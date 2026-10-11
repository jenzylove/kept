package app.kept.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

val ScreenPadding: Dp = 22.dp

/** Room at the foot of a scrolling screen so nothing hides under the floating tab bar. */
val TabBarSpace: Dp = 150.dp

/** The main action: a near black pill. It dips a little when pressed. */
@Composable
fun KeptButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val c = Kept.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 900f), label = "press")
    val on = enabled && !busy
    Box(
        modifier.fillMaxWidth().height(58.dp).scale(scale)
            .shadow(if (on) 14.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.28f))
            .background(if (enabled) c.text else c.raised, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = on, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = Color.White)
        else Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp), color = if (enabled) Color.White else c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The quieter action: a frosted pill. */
@Composable
fun KeptGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Kept.colors
    Box(
        modifier.fillMaxWidth().height(54.dp)
            .background(c.glass, CircleShape).border(1.dp, c.line, CircleShape).clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.text else c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/**
 * A headline with one word leaning: [lean] (which must appear in [text]) is set in the italic serif
 * and the kept colour, the way the brand writes "You won more than you kept."
 */
@Composable
fun Headline(text: String, lean: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayMedium) {
    val c = Kept.colors
    val at = text.lastIndexOf(lean)
    val built = androidx.compose.ui.text.buildAnnotatedString {
        if (at < 0) { append(text); return@buildAnnotatedString }
        append(text.substring(0, at))
        pushStyle(LeanStyle.toSpanStyle().copy(color = c.accent, fontSize = style.fontSize * 1.12f))
        append(lean)
        pop()
        append(text.substring(at + lean.length))
    }
    Text(built, style = style, color = c.text, modifier = modifier)
}

/** The mark and the name, as they sit at the top of a screen. */
@Composable
fun Wordmark(name: String = "Kept") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KeptMark(30.dp)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium, color = Kept.colors.text)
    }
}

/** Let something run past the screen's side padding, edge to edge. */
fun Modifier.bleed(pad: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = (pad * 2).roundToPx()
        val wide = constraints.maxWidth + extra
        val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-pad.roundToPx(), 0) }
    },
)

/** A small quiet label above a value or a group. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Kept.colors.dim, modifier = modifier)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Kept.colors.line))
}

/** A frosted sheet that floats on the page. Used sparingly: one or two to a screen, never a grid. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 22.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = Kept.colors
    val shape = RoundedCornerShape(30.dp)
    Column(
        modifier.fillMaxWidth()
            .shadow(26.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
            .background(c.glass, shape).border(1.dp, Color.White, shape).clip(shape).padding(padding),
        content = content,
    )
}

/** A label and a value on one row, separated by a hairline below. */
@Composable
fun StatRow(label: String, value: String, valueColor: Color = Kept.colors.text, onClick: (() -> Unit)? = null, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Kept.colors.dim, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!last) Hairline()
    }
}

/** Something the app needs to tell the user, sitting where it happened. */
@Composable
fun Notice(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val c = Kept.colors
    Row(
        modifier.fillMaxWidth().background(if (isError) c.lossSoft else c.accentSoft, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(if (isError) c.loss else c.accent, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text)
    }
}

/** One choice out of a few, as pills. The chosen one is solid. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(options: List<Pair<T, String>>, selected: T, enabled: (T) -> Boolean, onSelect: (T) -> Unit) {
    val c = Kept.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            val on = value == selected
            val ok = enabled(value)
            Box(
                Modifier
                    .background(if (on) c.text else c.glass, CircleShape)
                    .border(1.dp, if (on) c.text else c.line, CircleShape)
                    .clip(CircleShape)
                    .clickable(enabled = ok) { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else if (ok) c.text else c.dim.copy(alpha = 0.55f))
            }
        }
    }
}

/** A single line to type into, in a soft rounded well. */
@Composable
fun KeptField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, address: Boolean = false) {
    val c = Kept.colors
    val style = if (address) MaterialTheme.typography.bodyMedium.merge(AddressStyle) else MaterialTheme.typography.bodyLarge
    Box(
        modifier.fillMaxWidth().background(c.glass, RoundedCornerShape(20.dp))
            .border(1.dp, if (value.isEmpty()) c.line else c.accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 17.dp),
    ) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = c.dim)
        BasicTextField(
            value = value, onValueChange = { onChange(it.take(90)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = style.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        )
    }
}

/** An amount, set large, with its unit beside it. */
@Composable
fun BigAmount(value: String, onChange: (String) -> Unit, unit: String) {
    val c = Kept.colors
    // Long amounts step down in size so they stay on one line.
    val style = MaterialTheme.typography.displayLarge.merge(NumberStyle).copy(fontSize = when { value.length > 13 -> 30.sp; value.length > 9 -> 40.sp; else -> 56.sp })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("0", style = style, color = c.line)
            BasicTextField(
                value = value,
                onValueChange = { v -> if (v.length <= 20 && v.all { it.isDigit() || it == '.' } && v.count { it == '.' } <= 1) onChange(v) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = style.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        Text(unit, style = MaterialTheme.typography.titleMedium, color = c.dim, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
    }
}

/** A token's own picture in a circle. Until it loads, or if it has none, a tinted letter stands in. */
@Composable
fun TokenIcon(symbol: String, seed: String, icon: String?, size: Dp = 42.dp) {
    val tints = listOf(0xFF4A3AE8, 0xFF2E8BF0, 0xFF18A46B, 0xFFE9843A, 0xFFD94F8C, 0xFF14A6A6)
    val tint = Color(tints[(seed.hashCode() and 0x7FFFFFFF) % tints.size])
    Box(Modifier.size(size).background(tint.copy(alpha = 0.12f), CircleShape).clip(CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = tint)
        if (!icon.isNullOrBlank()) {
            coil.compose.AsyncImage(model = icon, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
    }
}
