package app.kept.ui

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

@Composable
fun HomeScreen(vm: AppViewModel, s: AppState) {
    val c = Kept.colors
    val ctx = LocalContext.current
    val v = s.vault
    val home by vm.homeMirror.collectAsState()
    var withdrawOpen by rememberSaveable { mutableStateOf(false) }

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
        Text("${fmtSol(s.vaultLamports)}", style = MaterialTheme.typography.displayLarge.merge(NumberStyle), color = c.bone)
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

        Spacer(Modifier.height(26.dp))
        if (v != null && v.hasPendingWithdraw) PendingWithdraw(vm, s, v) else {
            KeptGhostButton("Take money out", { withdrawOpen = true }, enabled = s.vaultLamports > 0 && s.busy == null)
        }

        s.error?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = true) }
        s.message?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = false) }
        s.busy?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = c.dim) }

        Spacer(Modifier.height(30.dp))
        Label("Trading wallet")
        Spacer(Modifier.height(4.dp))
        StatRow("Balance", "${fmtSol(s.tradingLamports)} SOL")
        s.tradingWallet?.let { StatRow("Address (tap to copy)", short(it, 5), onClick = { copyToClipboard(ctx, "Trading wallet", it) }) }
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
    Column(Modifier.fillMaxWidth().background(c.raised).padding(18.dp)) {
        Label(if (ready) "Ready to take out" else "Unlocks in")
        Spacer(Modifier.height(6.dp))
        Text(if (ready) "${fmtSol(v.withdrawAmount)} SOL" else fmtCountdown(left), style = MaterialTheme.typography.headlineMedium.merge(NumberStyle), color = if (ready) c.brass else c.bone)
        Spacer(Modifier.height(4.dp))
        Text(
            "${fmtSol(v.withdrawAmount)} SOL to ${if (v.withdrawTo == v.owner) "your wallet" else "your trading wallet"}",
            style = MaterialTheme.typography.bodyMedium, color = c.dim,
        )
        Spacer(Modifier.height(16.dp))
        if (ready) {
            KeptButton("Take it out", { vm.executeWithdraw() }, busy = s.busy != null)
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
    val lamports = ((amount.toDoubleOrNull() ?: 0.0) * 1e9).toLong()
    val spendable = (s.vaultLamports - RENT_RESERVE).coerceAtLeast(0)
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
            TextButton(onClick = { onRequest(lamports, toTrading) }, enabled = lamports > 0 && lamports <= spendable) { Text("Request", color = c.brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", color = c.dim) } },
    )
}

/** Roughly the rent the vault account must keep; the program refuses to dip into it. */
private const val RENT_RESERVE = 2_100_000L
