package app.kept.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val furthest = furthestSetupStep(s.mainWallet, s.tradingWallet)
    var requestedStep by rememberSaveable { mutableStateOf(furthest) }
    LaunchedEffect(furthest) { requestedStep = furthest }
    val step = boundedSetupStep(requestedStep, s.mainWallet, s.tradingWallet)
    val goBack = { vm.clearMessage(); requestedStep = (step - 1).coerceAtLeast(1) }
    BackHandler(enabled = step > 1, onBack = goBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(top = 18.dp, bottom = 40.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            if (step > 1) Text("Back", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clickable(onClick = goBack).padding(8.dp))
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 1..3) Box(Modifier.width(if (i == step) 34.dp else 16.dp).height(6.dp).background(if (i <= step) c.accent else c.line, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(8.dp))
            Label("Step $step of 3" + if (BuildConfig.CLUSTER == "mainnet-beta") "" else " · test network")
        }
        Spacer(Modifier.height(26.dp))

        if (step > 1 && s.mainWallet != null) {
            val ctx = LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label("Connected wallet")
                Text("Change", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clickable {
                    vm.resetWalletConnection()
                    requestedStep = 1
                }.padding(8.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                s.mainWallet, style = MaterialTheme.typography.bodyMedium.merge(AddressStyle), color = c.dim,
                modifier = Modifier.clickable { copyToClipboard(ctx, "Wallet", s.mainWallet) },
            )
            Spacer(Modifier.height(24.dp))
        }

        when (step) {
            1 -> ConnectStep(vm, s) { requestedStep = furthestSetupStep(s.mainWallet, s.tradingWallet) }
            2 -> TradingStep(vm, s) { vm.clearMessage(); requestedStep = 3 }
            else -> VaultStep(vm, s)
        }

        s.error?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = true) }
        s.message?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = false) }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ConnectStep(vm: AppViewModel, s: AppState, onContinue: () -> Unit) {
    val c = Kept.colors
    Headline("Connect the wallet that owns your vault.", "your vault.")
    Spacer(Modifier.height(14.dp))
    Text(
        "On a Seeker this is your Seed Vault, and you confirm with a double tap. On any other Android phone, use a wallet app like Phantom or Solflare. Kept never sees this wallet's key.",
        style = MaterialTheme.typography.bodyLarge, color = c.dim,
    )
    Spacer(Modifier.height(28.dp))
    if (s.mainWallet == null) {
        KeptButton("Connect wallet", { vm.connectWallet() }, busy = s.busy != null)
    } else {
        KeptButton("Continue with ${s.mainWallet.take(4)}…${s.mainWallet.takeLast(4)}", onContinue, busy = s.busy != null)
        Spacer(Modifier.height(10.dp))
        KeptGhostButton("Connect a different wallet", { vm.resetWalletConnection() }, enabled = s.busy == null)
    }
    s.busy?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = c.dim) }
}

@Composable
private fun TradingStep(vm: AppViewModel, s: AppState, onContinue: () -> Unit) {
    val c = Kept.colors
    val ctx = LocalContext.current
    var amount by rememberSaveable { mutableStateOf("0.1") }
    Headline("Make your trading wallet.", "trading wallet.")
    Spacer(Modifier.height(14.dp))
    Text(
        "A new wallet made on this phone, for trading. Its key is locked inside the phone and never leaves unless you reveal it. Trade with it in Kept or in any other app. Kept watches it and keeps part of every win.",
        style = MaterialTheme.typography.bodyLarge, color = c.dim,
    )
    Spacer(Modifier.height(28.dp))
    if (s.tradingWallet == null) {
        KeptButton("Create trading wallet", { vm.createTradingWallet() }, busy = s.busy != null)
        return
    }
    Label("Trading wallet")
    Spacer(Modifier.height(6.dp))
    Text(s.tradingWallet, style = MaterialTheme.typography.bodyMedium.merge(AddressStyle), color = c.text, modifier = Modifier.clickable { copyToClipboard(ctx, "Trading wallet", s.tradingWallet) })
    Text("Tap the address to copy it", style = MaterialTheme.typography.labelSmall, color = c.dim, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(18.dp))
    StatRow("Balance (${if (BuildConfig.CLUSTER == "mainnet-beta") "mainnet" else "devnet"})", "${fmtSol(s.tradingLamports)} SOL")
    Spacer(Modifier.height(22.dp))
    Text("Put some SOL in it. You can also do this later.", style = MaterialTheme.typography.titleMedium, color = c.text)
    Spacer(Modifier.height(8.dp))
    KeptField(amount, { amount = it }, "Amount in SOL", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
    Spacer(Modifier.height(10.dp))
    val lamports = solInputToLamports(amount)
    if (amount.isNotBlank() && lamports == null) {
        Text("Enter an amount of SOL, like 0.05.", style = MaterialTheme.typography.bodyMedium, color = c.loss)
        Spacer(Modifier.height(8.dp))
    }
    KeptGhostButton("Send from my wallet", { lamports?.let(vm::fundTrading) }, enabled = lamports != null && s.busy == null)
    Spacer(Modifier.height(14.dp))
    KeptButton("Continue", onContinue, enabled = s.busy == null)
}

@Composable
private fun VaultStep(vm: AppViewModel, s: AppState) {
    val c = Kept.colors
    var bps by rememberSaveable { mutableStateOf(5000) }
    var cooldown by rememberSaveable { mutableStateOf(ONE_DAY) }
    val tier = s.skrRaw >= SKR_TIER_RAW
    Headline("Open your vault.", "vault.")
    Spacer(Modifier.height(14.dp))
    Text(
        "Every time you sell for more than you paid, Kept moves part of the profit into your vault. Money goes in at once. To take it out you ask, then wait. You can cancel any time.",
        style = MaterialTheme.typography.bodyLarge, color = c.dim,
    )
    Spacer(Modifier.height(26.dp))

    Text("Share of each win to keep", style = MaterialTheme.typography.titleMedium, color = c.text)
    Spacer(Modifier.height(8.dp))
    Chips(
        options = listOf(2500 to "25%", 5000 to "50%", 7500 to "75%", 10000 to "100%"),
        selected = bps,
        enabled = { it == 5000 || tier },
        onSelect = { bps = it },
    )
    if (!tier) {
        Spacer(Modifier.height(6.dp))
        Text("Everyone starts at 50%. Hold ${SKR_TIER_RAW / 1_000_000} SKR in your wallet to pick another share.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
    }
    Spacer(Modifier.height(22.dp))

    Text("Wait before money leaves", style = MaterialTheme.typography.titleMedium, color = c.text)
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
        "You can make this longer at any time. Making it shorter only takes effect after the current wait, so a weak moment can't undo it.",
        style = MaterialTheme.typography.bodyMedium, color = c.dim,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Kept checks this step before it opens your wallet. If your wallet app still warns that it may fail or looks unsafe, cancel.",
        style = MaterialTheme.typography.bodyMedium, color = c.dim,
    )
    Spacer(Modifier.height(28.dp))
    KeptButton("Open vault and turn Kept on", { vm.createVault(bps, cooldown) }, busy = s.busy != null)
    s.busy?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = c.dim) }
}
