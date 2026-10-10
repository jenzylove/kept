package app.kept.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label("Your vault")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(8.dp).height(8.dp).background(if (s.reflexOn) c.ok else c.dim, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(if (s.reflexOn) "Kept is on" else "Kept is off", style = MaterialTheme.typography.labelSmall, color = c.dim)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = s.reflexOn, onCheckedChange = { vm.setReflex(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.brass, checkedThumbColor = c.ink, uncheckedTrackColor = c.raised, uncheckedThumbColor = c.dim, uncheckedBorderColor = c.line),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // Count up to the new total, and flash brass for a moment when money has just arrived.
        val shown by animateFloatAsState(s.vaultLamports.toFloat(), tween(900), label = "vault")
        var flash by remember { mutableStateOf(false) }
        var last by remember { mutableStateOf(-1L) }
        LaunchedEffect(s.vaultLamports, s.loaded) {
            if (!s.loaded) return@LaunchedEffect
            val rose = last >= 0 && s.vaultLamports > last
            last = s.vaultLamports
            if (rose) { flash = true; delay(1600); flash = false }
        }
        val numberColor by animateColorAsState(if (flash) c.brass else c.bone, tween(700), label = "flash")
        Text(fmtSol(shown.toLong()), style = MaterialTheme.typography.displayLarge.merge(NumberStyle), color = numberColor)
        Text(
            if (v == null) "SOL" else "SOL kept across ${v.bankCount} ${if (v.bankCount == 1L) "win" else "wins"}",
            style = MaterialTheme.typography.bodyLarge, color = c.dim,
        )

        home?.let { r ->
            val series = remember(r) { Engine.series(r.mirror, v?.bankBps ?: 5000) }
            if (series.kept.size >= 2) {
                Spacer(Modifier.height(22.dp))
                Staircase(series.wallet, series.kept, height = 120.dp)
            }
        }

        if (v != null && v.bankCount > 0) {
            Spacer(Modifier.height(14.dp))
            val r = home
            TextButton(onClick = {
                val series = r?.let { Engine.series(it.mirror, v.bankBps) }
                ShareCard.share(ctx, r?.mirror?.won?.toLong() ?: 0L, v.totalBanked, v.bankCount.toInt(), series?.wallet ?: emptyList(), series?.kept ?: emptyList())
            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Share what I kept", color = c.brass, style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(26.dp))
        // One clear next step until the first win is banked, then the way out.
        val needsSol = s.loaded && s.tradingLamports < MIN_TRADING_LAMPORTS
        when {
            v != null && v.hasPendingWithdraw -> PendingWithdraw(vm, s, v)
            s.vaultLamports > 0 -> KeptGhostButton("Take money out", { withdrawOpen = true }, enabled = s.busy == null)
            needsSol -> {
                Label("Next step")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your vault is open. Now put some SOL in your trading wallet. That is the wallet you trade from, and it lives on this phone.",
                    style = MaterialTheme.typography.bodyLarge, color = c.bone,
                )
                Spacer(Modifier.height(14.dp))
                KeptButton("Add SOL to trade with", { fundOpen = true }, enabled = s.busy == null)
            }
            v != null -> {
                Label("You are set")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Trade from the Trade tab. Each time you sell for more than you paid, ${v.bankBps / 100}% of the profit moves here by itself. Nothing moves on a loss.",
                    style = MaterialTheme.typography.bodyLarge, color = c.bone,
                )
            }
        }

        s.error?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = true) }
        s.message?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = false) }
        s.busy?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = c.dim) }

        val skr = s.skr
        if (v != null && skr != null && (skr.shares > 0 || skr.unstakingRaw > 0 || skr.vaultHeldRaw > 0)) {
            Spacer(Modifier.height(30.dp))
            SkrSection(vm, s, v, skr)
        }

        Spacer(Modifier.height(30.dp))
        Label("Trading wallet")
        Spacer(Modifier.height(4.dp))
        StatRow("Balance", "${fmtSol(s.tradingLamports)} SOL")
        s.tradingWallet?.let { StatRow("Address (tap to copy)", short(it, 5), onClick = { copyToClipboard(ctx, "Trading wallet", it) }) }
        Spacer(Modifier.height(10.dp))
        if (!(needsSol && s.vaultLamports == 0L && v?.hasPendingWithdraw != true)) TextButton(onClick = { fundOpen = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("Add SOL from my wallet", color = c.brass, style = MaterialTheme.typography.labelLarge)
        }
        if (v != null) {
            StatRow("Banks ${v.bankBps / 100}% of each win", "wait ${fmtDuration(v.cooldownSecs)}")
            if (v.pendingCooldownAt > 0) StatRow("Wait shortens to ${fmtDuration(v.pendingCooldownSecs)} in", fmtCountdown(v.pendingCooldownAt - s.nowSec))
        }

        Spacer(Modifier.height(30.dp))
        Label("Receipts")
        Spacer(Modifier.height(4.dp))
        if (s.log.isEmpty()) {
            Text(
                "Nothing banked yet. When the trading wallet sells above its cost, you'll see it here within seconds, with the transaction as proof.",
                style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        s.log.take(30).forEach { Receipt(it, s.nowSec) }
        Spacer(Modifier.height(40.dp))
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
        BankEntry.Status.Pending -> "checking chain" to c.dim
        BankEntry.Status.Confirmed -> "+${fmtSol(e.amountLamports)} SOL" to c.brass
        BankEntry.Status.Failed -> "failed" to c.loss
        BankEntry.Status.NoVault -> "not banked" to c.dim
        BankEntry.Status.TooSmall -> "too small" to c.dim
    }
    val sub = buildString {
        append(short(e.mint)); append(" · win +${fmtSol(e.profitLamports, 3)}"); append(" · ${fmtAgo(now, e.bankedAt)}")
        if (e.late) append(" · found late")
        e.reason?.let { append(" · $it") }
    }
    Column(Modifier.fillMaxWidth().clickable(enabled = e.bankSignature != null) { openExplorer(ctx, "tx", e.bankSignature!!) }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
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
    Column(Modifier.fillMaxWidth().background(c.raised).padding(18.dp)) {
        Label(if (ready) (if (isSkr) "Ready to unstake" else "Ready to take out") else "Unlocks in")
        Spacer(Modifier.height(6.dp))
        Text(
            if (ready) (if (isSkr) "Staked SKR" else "${fmtSol(v.withdrawAmount)} SOL") else fmtCountdown(left),
            style = MaterialTheme.typography.headlineMedium.merge(NumberStyle), color = if (ready) c.brass else c.bone,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (isSkr) "Your staked SKR, back to your wallet" else "${fmtSol(v.withdrawAmount)} SOL to ${if (v.withdrawTo == v.owner) "your wallet" else "your trading wallet"}",
            style = MaterialTheme.typography.bodyMedium, color = c.dim,
        )
        Spacer(Modifier.height(16.dp))
        if (ready) {
            KeptButton(if (isSkr) "Start unstaking" else "Take it out", { if (isSkr) vm.executeSkrUnstake() else vm.executeWithdraw() }, busy = s.busy != null)
            Spacer(Modifier.height(10.dp))
        }
        KeptGhostButton("Cancel, keep it in the vault", { vm.cancelWithdraw() }, enabled = s.busy == null)
    }
}

@Composable
private fun WithdrawDialog(s: AppState, onDismiss: () -> Unit, onRequest: (Long, Boolean) -> Unit) {
    val c = Kept.colors
    val v = s.vault!!
    var amount by remember { mutableStateOf("") }
    var toTrading by remember { mutableStateOf(false) }
    val lamports = solInputToLamports(amount)
    val spendable = s.vaultLamports
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.raised,
        title = { Text("Take money out", color = c.bone) },
        text = {
            Column {
                Text("It unlocks after ${fmtDuration(v.cooldownSecs)}. You can cancel while you wait.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, singleLine = true, label = { Text("Amount in SOL (up to ${fmtSol(spendable)})") },
                    shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.brass, unfocusedBorderColor = c.line, focusedLabelColor = c.brass, cursorColor = c.brass),
                )
                Spacer(Modifier.height(12.dp))
                Chips(listOf(false to "To my wallet", true to "To trading wallet"), toTrading, { true }) { toTrading = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { lamports?.let { onRequest(it, toTrading) } }, enabled = lamports != null && lamports <= spendable) { Text("Request", color = c.brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = c.dim) } },
    )
}

/** Under this the trading wallet cannot pay for a trade and its fees. */
private const val MIN_TRADING_LAMPORTS = 2_000_000L

@Composable
private fun SkrSection(vm: AppViewModel, s: AppState, v: VaultProgram.VaultState, skr: app.kept.SkrPosition) {
    val c = Kept.colors
    val skrPending = v.hasPendingWithdraw && v.withdrawAsset == VaultProgram.Asset.StakedSkr
    Label("Staked SKR")
    Spacer(Modifier.height(4.dp))
    if (skr.shares > 0) StatRow("Staked in your vault", String.format(java.util.Locale.US, "%.2f SKR", skr.valueRaw / 1e6), valueColor = c.brass)
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
        skrPending -> Text("Your unstake request is in the panel above.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
    }
}

@Composable
private fun FundDialog(onDismiss: () -> Unit, onSend: (Long) -> Unit) {
    val c = Kept.colors
    var amount by remember { mutableStateOf("0.1") }
    val lamports = solInputToLamports(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.raised,
        title = { Text("Add SOL to your trading wallet", color = c.bone) },
        text = {
            Column {
                Text("This sends SOL from the wallet that owns your vault. Your wallet app will ask you to confirm.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, singleLine = true, label = { Text("Amount in SOL") },
                    shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.brass, unfocusedBorderColor = c.line, focusedLabelColor = c.brass, cursorColor = c.brass),
                )
            }
        },
        confirmButton = { TextButton(onClick = { lamports?.let(onSend) }, enabled = lamports != null) { Text("Send", color = c.brass) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = c.dim) } },
    )
}
