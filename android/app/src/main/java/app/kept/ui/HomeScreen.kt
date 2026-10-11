package app.kept.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kept.AppState
import app.kept.AppViewModel
import app.kept.chain.VaultProgram
import app.kept.engine.Engine
import app.kept.reflex.BankEntry
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: AppViewModel, s: AppState) {
    val c = Kept.colors
    val ctx = LocalContext.current
    val v = s.vault
    val home by vm.homeMirror.collectAsState()
    var withdrawOpen by rememberSaveable { mutableStateOf(false) }
    var fundOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(top = 18.dp, bottom = TabBarSpace)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Row(
                Modifier.background(c.glass, CircleShape).border(1.dp, c.line, CircleShape).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(if (s.reflexOn) c.ok else c.dim, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(if (s.reflexOn) "Watching" else "Paused", style = MaterialTheme.typography.labelSmall, color = c.text)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = s.reflexOn, onCheckedChange = { vm.setReflex(it) }, modifier = Modifier.scale(0.78f),
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White, uncheckedTrackColor = c.raised, uncheckedThumbColor = c.dim, uncheckedBorderColor = c.line),
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // Count up to the new total, and glow for a moment when money has just arrived.
        val shown by animateFloatAsState(s.vaultLamports.toFloat(), tween(900), label = "vault")
        var flash by remember { mutableStateOf(false) }
        var last by remember { mutableStateOf(-1L) }
        LaunchedEffect(s.vaultLamports, s.loaded) {
            if (!s.loaded) return@LaunchedEffect
            val rose = last >= 0 && s.vaultLamports > last
            last = s.vaultLamports
            if (rose) { flash = true; delay(1600); flash = false }
        }
        val numberColor by animateColorAsState(if (flash) c.accent else c.text, tween(700), label = "flash")
        val series = remember(home, v?.bankBps) { home?.let { Engine.series(it.mirror, v?.bankBps ?: 5000) } }
        val wins = v?.bankCount ?: 0L
        val (lit, total) = stairCounts(wins)

        Spacer(Modifier.height(10.dp))
        Label("In your vault")
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtSol(shown.toLong()), style = MaterialTheme.typography.displayLarge.merge(NumberStyle).copy(fontSize = 76.sp, lineHeight = 78.sp), color = numberColor)
            Text("SOL", style = MaterialTheme.typography.titleMedium, color = c.dim, modifier = Modifier.padding(start = 10.dp, bottom = 15.dp))
        }
        if (v != null) {
            if (wins == 0L) Headline("Nothing kept yet. Your first win lights the first step.", "first step.", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp))
            else Headline("Kept from $wins ${if (wins == 1L) "win" else "wins"}. It only goes up.", "only goes up.", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp))
        }
        // The staircase runs off both edges of the screen, the way a real one keeps going.
        GlassStairs(lit, total, Modifier.bleed(ScreenPadding).padding(top = 6.dp), height = 230.dp)
        if (v != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Keeps ${v.bankBps / 100}% of each win · ${fmtDuration(v.cooldownSecs)} wait to take out", style = MaterialTheme.typography.labelSmall, color = c.dim, modifier = Modifier.weight(1f))
                if (wins > 0) {
                    Text("Share", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clickable {
                        ShareCard.share(ctx, home?.mirror?.won?.toLong() ?: 0L, v.totalBanked, wins.toInt(), series?.wallet ?: emptyList(), series?.kept ?: emptyList())
                    }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        // One clear next step until the first win is banked, then the way out.
        val needsSol = s.loaded && s.tradingLamports < MIN_TRADING_LAMPORTS
        when {
            v != null && v.hasPendingWithdraw -> PendingWithdraw(vm, s, v)
            s.vaultLamports > 0 -> KeptGhostButton("Take money out", { withdrawOpen = true }, enabled = s.busy == null)
            needsSol -> {
                Headline("Next, put some SOL in your trading wallet.", "trading wallet.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("That is the wallet you trade from. It lives on this phone.", style = MaterialTheme.typography.bodyLarge, color = c.dim)
                Spacer(Modifier.height(16.dp))
                KeptButton("Add SOL to trade with", { fundOpen = true }, enabled = s.busy == null)
            }
            v != null -> {
                Headline("You are set. Go trade.", "Go trade.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Each time you sell for more than you paid, ${v.bankBps / 100}% of the profit moves here by itself. Nothing moves on a loss.",
                    style = MaterialTheme.typography.bodyLarge, color = c.dim,
                )
            }
        }

        s.error?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = true) }
        s.message?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = false) }
        s.busy?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = c.dim) }

        val skr = s.skr
        if (v != null && skr != null && (skr.shares > 0 || skr.unstakingRaw > 0 || skr.vaultHeldRaw > 0)) {
            Spacer(Modifier.height(34.dp))
            SkrSection(vm, s, v, skr)
        }

        Spacer(Modifier.height(34.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Trading wallet", style = MaterialTheme.typography.titleMedium, color = c.text)
            if (!(needsSol && s.vaultLamports == 0L && v?.hasPendingWithdraw != true)) {
                Text("Add SOL", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clickable { fundOpen = true }.padding(vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        StatRow("Balance", "${fmtSol(s.tradingLamports)} SOL")
        s.tradingWallet?.let { StatRow("Address, tap to copy", short(it, 5), onClick = { copyToClipboard(ctx, "Trading wallet", it) }) }
        if (v != null) {
            StatRow("Wait before money leaves the vault", fmtDuration(v.cooldownSecs))
            if (v.pendingCooldownAt > 0) StatRow("Wait shortens to ${fmtDuration(v.pendingCooldownSecs)} in", fmtCountdown(v.pendingCooldownAt - s.nowSec))
        }

        Spacer(Modifier.height(34.dp))
        Text("Receipts", style = MaterialTheme.typography.titleMedium, color = c.text)
        Spacer(Modifier.height(4.dp))
        if (s.log.isEmpty()) {
            Text(
                "Nothing kept yet. When you sell for more than you paid, the receipt shows up here within seconds, with a link to prove it.",
                style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        s.log.take(30).forEach { Receipt(it, s.nowSec) }
    }

    if (fundOpen) FundDialog(onDismiss = { fundOpen = false }) { lamports -> fundOpen = false; vm.fundTrading(lamports) }

    if (withdrawOpen && v != null) WithdrawDialog(s, onDismiss = { withdrawOpen = false }) { lamports, toTrading ->
        withdrawOpen = false
        vm.requestWithdraw(lamports, toTrading)
    }
}

@Composable
private fun Receipt(e: BankEntry, now: Long) {
    val c = Kept.colors
    val ctx = LocalContext.current
    val (label, color) = when (e.status) {
        BankEntry.Status.Pending -> "checking" to c.dim
        BankEntry.Status.Confirmed -> "+${fmtSol(e.amountLamports)} SOL" to c.accent
        BankEntry.Status.Failed -> "failed" to c.loss
        BankEntry.Status.NoVault -> "not kept" to c.dim
        BankEntry.Status.TooSmall -> "too small" to c.dim
    }
    val sub = buildString {
        append("Win of ${fmtSol(e.profitLamports, 3)} SOL"); append(" · ${fmtAgo(now, e.bankedAt)}")
        if (e.late) append(" · found late")
        e.reason?.let { append(" · $it") }
    }
    Column(Modifier.fillMaxWidth().clickable(enabled = e.bankSignature != null) { openExplorer(ctx, "tx", e.bankSignature!!) }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), color = color)
        }
        Hairline()
    }
}

@Composable
private fun PendingWithdraw(vm: AppViewModel, s: AppState, v: VaultProgram.VaultState) {
    val c = Kept.colors
    val left = v.withdrawUnlockAt - s.nowSec
    val ready = left <= 0
    val isSkr = v.withdrawAsset == VaultProgram.Asset.StakedSkr
    Panel {
        Label(if (ready) (if (isSkr) "Ready to unstake" else "Ready to take out") else "On its way out. Unlocks in")
        Spacer(Modifier.height(6.dp))
        Text(
            if (ready) (if (isSkr) "Staked SKR" else "${fmtSol(v.withdrawAmount)} SOL") else fmtCountdown(left),
            style = MaterialTheme.typography.displayMedium.merge(NumberStyle), color = if (ready) c.accent else c.text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (isSkr) "Your staked SKR, back to your wallet" else "${fmtSol(v.withdrawAmount)} SOL to ${if (v.withdrawTo == v.owner) "your wallet" else "your trading wallet"}",
            style = MaterialTheme.typography.bodyMedium, color = c.dim,
        )
        Spacer(Modifier.height(18.dp))
        if (ready) {
            KeptButton(if (isSkr) "Start unstaking" else "Take it out", { if (isSkr) vm.executeSkrUnstake() else vm.executeWithdraw() }, busy = s.busy != null)
            Spacer(Modifier.height(10.dp))
        }
        KeptGhostButton("Cancel, keep it in the vault", { vm.cancelWithdraw() }, enabled = s.busy == null)
    }
}

/** A sheet of glass over the page for one small decision. */
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Kept.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(30.dp)).padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = c.text)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun WithdrawDialog(s: AppState, onDismiss: () -> Unit, onRequest: (Long, Boolean) -> Unit) {
    val c = Kept.colors
    val v = s.vault!!
    var amount by remember { mutableStateOf("") }
    var toTrading by remember { mutableStateOf(false) }
    val lamports = solInputToLamports(amount)
    val spendable = s.vaultLamports
    Sheet("Take money out", onDismiss) {
        Text("It unlocks after ${fmtDuration(v.cooldownSecs)}. You can cancel while you wait.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        Spacer(Modifier.height(16.dp))
        KeptField(amount, { amount = it }, "Amount in SOL, up to ${fmtSol(spendable)}", keyboard = KeyboardType.Decimal)
        Spacer(Modifier.height(14.dp))
        Chips(listOf(false to "To my wallet", true to "To trading wallet"), toTrading, { true }) { toTrading = it }
        Spacer(Modifier.height(20.dp))
        KeptButton("Start the wait", { lamports?.let { onRequest(it, toTrading) } }, enabled = lamports != null && lamports <= spendable)
        Spacer(Modifier.height(8.dp))
        KeptGhostButton("Not now", onDismiss)
    }
}

/** Under this the trading wallet cannot pay for a trade and its fees. */
private const val MIN_TRADING_LAMPORTS = 2_000_000L

@Composable
private fun SkrSection(vm: AppViewModel, s: AppState, v: VaultProgram.VaultState, skr: app.kept.SkrPosition) {
    val c = Kept.colors
    val skrPending = v.hasPendingWithdraw && v.withdrawAsset == VaultProgram.Asset.StakedSkr
    Text("Staked SKR", style = MaterialTheme.typography.titleMedium, color = c.text)
    Spacer(Modifier.height(4.dp))
    if (skr.shares > 0) StatRow("Staked in your vault", String.format(java.util.Locale.US, "%.2f SKR", skr.valueRaw / 1e6), valueColor = c.accent)
    if (skr.unstakingRaw > 0) {
        val ready = s.nowSec >= skr.unstakeStartedAt + skr.skrCooldownSecs
        StatRow("Unstaking", String.format(java.util.Locale.US, "%.2f SKR", skr.unstakingRaw / 1e6))
        StatRow("Free in", if (ready) "now" else fmtCountdown(skr.unstakeStartedAt + skr.skrCooldownSecs - s.nowSec))
    }
    if (skr.vaultHeldRaw > 0) StatRow("Waiting in the vault", String.format(java.util.Locale.US, "%.2f SKR", skr.vaultHeldRaw / 1e6))
    Spacer(Modifier.height(14.dp))
    val collectReady = skr.vaultHeldRaw > 0 || (skr.unstakingRaw > 0 && s.nowSec >= skr.unstakeStartedAt + skr.skrCooldownSecs)
    when {
        collectReady -> KeptButton("Send my SKR to my wallet", { vm.collectSkr() }, busy = s.busy != null)
        skr.shares > 0 && !v.hasPendingWithdraw -> KeptGhostButton("Unstake my SKR", { vm.requestSkrUnstake() }, enabled = s.busy == null)
        skrPending -> Text("Your unstake request is shown above.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
    }
}

@Composable
private fun FundDialog(onDismiss: () -> Unit, onSend: (Long) -> Unit) {
    val c = Kept.colors
    var amount by remember { mutableStateOf("0.1") }
    val lamports = solInputToLamports(amount)
    Sheet("Add SOL to trade with", onDismiss) {
        Text("This sends SOL from your connected wallet to your trading wallet. Your wallet app will ask you to confirm.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        Spacer(Modifier.height(16.dp))
        KeptField(amount, { amount = it }, "Amount in SOL", keyboard = KeyboardType.Decimal)
        Spacer(Modifier.height(20.dp))
        KeptButton("Send", { lamports?.let(onSend) }, enabled = lamports != null)
        Spacer(Modifier.height(8.dp))
        KeptGhostButton("Not now", onDismiss)
    }
}
