package app.kept.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kept.AppState
import app.kept.AppViewModel
import app.kept.BuildConfig

const val ONE_MINUTE = 60L
const val ONE_HOUR = 3_600L
const val ONE_DAY = 86_400L

/** SKR needed to unlock the custom bank share. 100 SKR (6 decimals). One constant, shown in the app. */
const val SKR_TIER_RAW = 100_000_000L

fun copyToClipboard(context: Context, label: String, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Setup, one step at a time: connect the wallet that owns the vault, make the trading wallet, open the vault. */
@Composable
fun SetupScreen(vm: AppViewModel, s: AppState) {
    val c = Kept.colors
    var tradingConfirmed by rememberSaveable { mutableStateOf(false) }
    val step = when {
        s.mainWallet == null -> 1
        s.tradingWallet == null || !tradingConfirmed -> 2
        else -> 3
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 28.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 1..3) Box(Modifier.width(if (i == step) 28.dp else 14.dp).height(3.dp).background(if (i <= step) c.brass else c.line))
            Spacer(Modifier.width(8.dp))
            Label("Step $step of 3")
        }
        Spacer(Modifier.height(28.dp))

        if (step > 1 && s.mainWallet != null) {
            val ctx = LocalContext.current
            Label("Connected wallet")
            Spacer(Modifier.height(4.dp))
            Text(
                s.mainWallet, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = c.dim,
                modifier = Modifier.clickable { copyToClipboard(ctx, "Wallet", s.mainWallet) },
            )
            Spacer(Modifier.height(24.dp))
        }

        when (step) {
            1 -> ConnectStep(vm, s)
            2 -> TradingStep(vm, s) { tradingConfirmed = true }
            else -> VaultStep(vm, s)
        }

        s.error?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = true) }
        s.message?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = false) }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ConnectStep(vm: AppViewModel, s: AppState) {
    val c = Kept.colors
    Text("Connect the wallet that owns your vault.", style = MaterialTheme.typography.displayMedium, color = c.bone)
    Spacer(Modifier.height(14.dp))
    Text(
        "On a Seeker this is your Seed Vault: you'll confirm with a double tap. On any other Android phone, use Phantom or Solflare. Kept never sees this wallet's key.",
        style = MaterialTheme.typography.bodyLarge, color = c.dim,
    )
    Spacer(Modifier.height(28.dp))
    KeptButton("Connect wallet", { vm.connectWallet() }, busy = s.busy != null)
    s.busy?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = c.dim) }
}

@Composable
private fun TradingStep(vm: AppViewModel, s: AppState, onContinue: () -> Unit) {
    val c = Kept.colors
    val ctx = LocalContext.current
    var amount by rememberSaveable { mutableStateOf("0.1") }
    Text("Make your trading wallet.", style = MaterialTheme.typography.displayMedium, color = c.bone)
    Spacer(Modifier.height(14.dp))
    Text(
        "A new wallet created on this phone. Its key is sealed by Android Keystore and never leaves unless you reveal it. Trade with it anywhere: in Kept, or import it into Phantom. Kept watches it and banks part of every win.",
        style = MaterialTheme.typography.bodyLarge, color = c.dim,
    )
    Spacer(Modifier.height(28.dp))
    if (s.tradingWallet == null) {
        KeptButton("Create trading wallet", { vm.createTradingWallet() }, busy = s.busy != null)
        return
    }
    Label("Trading wallet")
    Spacer(Modifier.height(6.dp))
    Text(s.tradingWallet, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = c.bone, modifier = Modifier.clickable { copyToClipboard(ctx, "Trading wallet", s.tradingWallet) })
    Text("Tap the address to copy it", style = MaterialTheme.typography.labelSmall, color = c.dim, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(18.dp))
    StatRow("Balance", "${fmtSol(s.tradingLamports)} SOL")
    Spacer(Modifier.height(22.dp))
    Label("Put some SOL in it (optional now)")
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = amount, onValueChange = { amount = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Amount in SOL") },
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.brass, unfocusedBorderColor = c.line, focusedLabelColor = c.brass, cursorColor = c.brass),
    )
    Spacer(Modifier.height(10.dp))
    val lamports = ((amount.toDoubleOrNull() ?: 0.0) * 1e9).toLong()
    KeptGhostButton("Send from my wallet", { vm.fundTrading(lamports) }, enabled = lamports > 0 && s.busy == null)
    Spacer(Modifier.height(14.dp))
    KeptButton("Continue", onContinue, enabled = s.busy == null)
}

@Composable
private fun VaultStep(vm: AppViewModel, s: AppState) {
    val c = Kept.colors
    var bps by rememberSaveable { mutableStateOf(5000) }
    var cooldown by rememberSaveable { mutableStateOf(ONE_DAY) }
    val tier = s.skrRaw >= SKR_TIER_RAW
    Text("Open your vault.", style = MaterialTheme.typography.displayMedium, color = c.bone)
    Spacer(Modifier.height(14.dp))
    Text(
        "Every time the trading wallet sells for a profit, Kept moves part of the profit into your vault. Money goes in instantly. To take it out, you ask, then wait. You can cancel any time.",
        style = MaterialTheme.typography.bodyLarge, color = c.dim,
    )
    Spacer(Modifier.height(26.dp))

    Label("Share of each win to bank")
    Spacer(Modifier.height(8.dp))
    Chips(
        options = listOf(2500 to "25%", 5000 to "50%", 7500 to "75%", 10000 to "100%"),
        selected = bps,
        enabled = { it == 5000 || tier },
        onSelect = { bps = it },
    )
    if (!tier) {
        Spacer(Modifier.height(6.dp))
        Text("50% is the standard share. Hold ${SKR_TIER_RAW / 1_000_000} SKR in your wallet to choose another.", style = MaterialTheme.typography.labelSmall, color = c.dim)
    }
    Spacer(Modifier.height(22.dp))

    Label("Wait before money leaves")
    Spacer(Modifier.height(8.dp))
    val testing = BuildConfig.CLUSTER != "mainnet-beta"
    Chips(
        options = buildList {
            if (testing) add(ONE_MINUTE to "1 min (test)")
            add(ONE_HOUR to "1 hour"); add(ONE_DAY to "24 hours"); add(3 * ONE_DAY to "3 days"); add(7 * ONE_DAY to "7 days")
        },
        selected = cooldown,
        enabled = { true },
        onSelect = { cooldown = it },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Shortening this later only takes effect after the current wait, so a bad moment can't talk you out of it.",
        style = MaterialTheme.typography.labelSmall, color = c.dim,
    )
    Spacer(Modifier.height(28.dp))
    KeptButton("Open vault and turn Kept on", { vm.createVault(bps, cooldown) }, busy = s.busy != null)
    s.busy?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = c.dim) }
}

/** A single choice row of text chips. Selected is brass; others are hairline. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(options: List<Pair<T, String>>, selected: T, enabled: (T) -> Boolean, onSelect: (T) -> Unit) {
    val c = Kept.colors
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            val on = value == selected
            val ok = enabled(value)
            Box(
                Modifier
                    .background(if (on) c.brass else c.raised, RoundedCornerShape(3.dp))
                    .clickable(enabled = ok) { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) c.ink else if (ok) c.bone else c.dim)
            }
        }
    }
}
