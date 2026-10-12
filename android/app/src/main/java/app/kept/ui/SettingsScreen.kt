package app.kept.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kept.AppState
import app.kept.AppViewModel
import app.kept.BuildConfig

private const val SITE = "https://kept-seeker.vercel.app"

/**
 * Settings as a short menu. Each line shows what is set now; tapping it opens that one thing in
 * place, and opening another closes it, so the page never becomes a wall.
 */
@Composable
fun SettingsScreen(vm: AppViewModel, s: AppState, onExport: (onKey: (String) -> Unit) -> Unit) {
    val c = Kept.colors
    val ctx = LocalContext.current
    val v = s.vault
    val tier = s.skrRaw >= SKR_TIER_RAW
    val testing = BuildConfig.CLUSTER != "mainnet-beta"
    var open by rememberSaveable { mutableStateOf("") }
    var bps by rememberSaveable { mutableStateOf(v?.bankBps ?: 5000) }
    var cooldown by rememberSaveable { mutableStateOf(v?.cooldownSecs ?: ONE_DAY) }
    var exported by rememberSaveable { mutableStateOf<String?>(null) }
    var withdrawOpen by rememberSaveable { mutableStateOf(false) }
    val running = (ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    fun toggle(key: String) { open = if (open == key) "" else key; vm.clearMessage() }
    fun web(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(top = 18.dp, bottom = TabBarSpace)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = c.text)

        if (v != null) {
            Group("Your rules")
            Item("Share of each win to keep", "${v.bankBps / 100}%", open == "share", { toggle("share") }) {
                Chips(listOf(2500 to "25%", 5000 to "50%", 7500 to "75%", 10000 to "100%"), bps, { it == 5000 || tier }) { bps = it }
                if (!tier) Note("Everyone starts at 50%. Hold ${SKR_TIER_RAW / 1_000_000} SKR in your connected wallet to pick your own share. You hold ${String.format(java.util.Locale.US, "%.0f", s.skrRaw / 1e6)}.")
                if (bps != v.bankBps) Save(s) { vm.setRules(v.cooldownSecs, bps) }
            }
            Item("Keep wins as", if (s.bankAsset == "skr") "Staked SKR" else "SOL", open == "asset", { toggle("asset") }) {
                if (testing) {
                    Chips(listOf("sol" to "SOL"), s.bankAsset, { true }) { vm.setBankAsset(it) }
                    Note("This is a test build, so every win is kept as SOL. Staked SKR is available in the real app.")
                } else {
                    Chips(listOf("sol" to "SOL", "skr" to "Staked SKR"), s.bankAsset, { it == "sol" || tier }) { vm.setBankAsset(it) }
                    Note(
                        if (tier) "Staked SKR turns your share of each win into SKR and stakes it in your vault, where it earns. It only stakes the SKR it just bought, never SKR you hold yourself."
                        else "Staked SKR opens up when you hold ${SKR_TIER_RAW / 1_000_000} SKR in your connected wallet.",
                    )
                }
            }
            Item("Wait before money leaves", fmtDuration(v.cooldownSecs), open == "wait", { toggle("wait") }) {
                Chips(
                    buildList {
                        if (testing) add(ONE_MINUTE to "1 min (test)")
                        add(ONE_HOUR to "1 hour"); add(ONE_DAY to "24 hours"); add(3 * ONE_DAY to "3 days"); add(7 * ONE_DAY to "7 days")
                    },
                    cooldown, { true },
                ) { cooldown = it }
                Note(
                    if (cooldown < v.cooldownSecs) "A shorter wait only starts after your current wait of ${fmtDuration(v.cooldownSecs)} has passed. That is what stops a weak moment from undoing it."
                    else "This is how long money you take out of the vault waits before it reaches you. You can make it longer at any time.",
                )
                if (cooldown != v.cooldownSecs) Save(s) { vm.setRules(cooldown, v.bankBps) }
            }

            Group("Your money")
            Item(
                "Take money out of the vault",
                if (v.hasPendingWithdraw) "On its way" else "${fmtSol(s.vaultLamports)} SOL",
                open == "out", { toggle("out") },
            ) {
                when {
                    v.hasPendingWithdraw -> Note("You already asked to take money out. Its countdown is on the Vault tab, where you can also cancel it.")
                    s.vaultLamports <= 0 -> Note("There is nothing in your vault yet. Money lands there each time you sell for more than you paid.")
                    else -> {
                        Note("You ask, then wait ${fmtDuration(v.cooldownSecs)}. You can cancel while you wait.")
                        Spacer(Modifier.height(12.dp))
                        KeptButton("Take money out", { withdrawOpen = true }, enabled = s.busy == null)
                    }
                }
            }
        }

        Group("This phone")
        Item("Keep Kept running", if (running) "Allowed" else "Needs a tap", open == "run", { toggle("run") }) {
            Note("Kept watches your trading wallet in the background. Phones stop apps to save battery, so let Kept run freely and no win is missed. If it ever stops, anything it missed is kept when it wakes.")
            if (!running) {
                Spacer(Modifier.height(12.dp))
                KeptButton("Let Kept keep running", {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                })
            }
        }
        Item("Trading wallet key", "Hidden", open == "key", { toggle("key") }) {
            Note("The key to your trading wallet was made on this phone and is locked inside it. Reveal it if you want to use the same wallet in another app. Anyone who has the key can move the money in the trading wallet, so keep it private.")
            Spacer(Modifier.height(12.dp))
            KeptGhostButton("Reveal with fingerprint or PIN", { onExport { exported = it } })
        }
        Item("Your addresses", "", open == "addresses", { toggle("addresses") }) {
            Note("Kept uses three addresses, each with its own job. Tap one to copy it.")
            s.tradingWallet?.let { Address(ctx, "Trading wallet", "Lives on this phone. You trade from it.", it) }
            s.mainWallet?.let { Address(ctx, "Connected wallet", "Your own wallet app. It owns the vault.", it) }
            s.vaultAddress?.let { Address(ctx, "Vault", "Where kept money sits. Only your connected wallet can take it out.", it) }
        }

        Group("About")
        Item("How Kept works", "", false, { web(SITE) }, link = true) {}
        Item("What Kept does and does not do", "", false, { web("$SITE/limits") }, link = true) {}
        Item("See the vault program on chain", "", false, { openExplorer(ctx, "account", BuildConfig.VAULT_PROGRAM) }, link = true) {}
        Spacer(Modifier.height(18.dp))
        Text(
            "Kept ${BuildConfig.VERSION_NAME} · ${if (testing) "Solana devnet, test money" else "Solana mainnet"}",
            style = MaterialTheme.typography.labelSmall, color = c.dim,
        )
    }

    if (withdrawOpen && v != null) WithdrawDialog(s, onDismiss = { withdrawOpen = false }) { lamports, toTrading ->
        withdrawOpen = false
        vm.requestWithdraw(lamports, toTrading)
    }

    exported?.let { key ->
        // While the key is on screen, the phone refuses screenshots and screen recording of this window.
        androidx.compose.runtime.DisposableEffect(Unit) {
            val window = (ctx as? android.app.Activity)?.window
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        }
        AlertDialog(
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
            onDismissRequest = { exported = null },
            containerColor = androidx.compose.ui.graphics.Color.White,
            title = { Text("Trading wallet key", color = c.text) },
            text = { Text(key, style = MaterialTheme.typography.bodyMedium.merge(AddressStyle), color = c.text) },
            confirmButton = { TextButton(onClick = { copyToClipboard(ctx, "Trading wallet key", key); exported = null }) { Text("Copy and close", color = c.accent) } },
            dismissButton = { TextButton(onClick = { exported = null }) { Text("Close", color = c.dim) } },
        )
    }
}

/** A quiet heading over a group of menu lines. */
@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(26.dp))
    Label(title)
    Spacer(Modifier.height(2.dp))
}

/** One line of the menu: what it is, what it is set to, and a chevron. Its content drops open beneath it. */
@Composable
private fun Item(title: String, value: String, open: Boolean, onTap: () -> Unit, link: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Kept.colors
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f).padding(end = 12.dp))
            if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), color = if (open) c.accent else c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Canvas(Modifier.size(14.dp).rotate(turn)) {
                val w = 1.8.dp.toPx()
                drawLine(c.dim, Offset(size.width * 0.35f, size.height * 0.15f), Offset(size.width * 0.75f, size.height * 0.5f), w, StrokeCap.Round)
                drawLine(c.dim, Offset(size.width * 0.75f, size.height * 0.5f), Offset(size.width * 0.35f, size.height * 0.85f), w, StrokeCap.Round)
            }
        }
        if (!link) {
            AnimatedVisibility(open) {
                Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), content = content)
            }
        }
        Hairline()
    }
}

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Kept.colors.dim)
}

/** Rule changes are signed by the connected wallet, so the button says so, and reports back right here. */
@Composable
private fun Save(s: AppState, onSave: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    KeptButton("Save and approve in your wallet", onSave, busy = s.busy != null)
    s.error?.let { Spacer(Modifier.height(12.dp)); Notice(it, isError = true) }
    s.message?.let { Spacer(Modifier.height(12.dp)); Notice(it, isError = false) }
}

@Composable
private fun Address(ctx: android.content.Context, name: String, what: String, address: String) {
    val c = Kept.colors
    Column(Modifier.fillMaxWidth().clickable { copyToClipboard(ctx, name, address) }.padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
            Text(short(address, 5), style = MaterialTheme.typography.bodyMedium.merge(AddressStyle), color = c.text)
        }
        Text(what, style = MaterialTheme.typography.bodyMedium, color = c.dim)
    }
}
