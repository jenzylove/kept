package app.kept.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kept.AppState
import app.kept.AppViewModel
import app.kept.BuildConfig

@Composable
fun SettingsScreen(vm: AppViewModel, s: AppState, onExport: (onKey: (String) -> Unit) -> Unit) {
    val c = Kept.colors
    val ctx = LocalContext.current
    val v = s.vault
    val tier = s.skrRaw >= SKR_TIER_RAW
    var bps by rememberSaveable { mutableStateOf(v?.bankBps ?: 5000) }
    var cooldown by rememberSaveable { mutableStateOf(v?.cooldownSecs ?: ONE_DAY) }
    var exported by rememberSaveable { mutableStateOf<String?>(null) }
    val testing = BuildConfig.CLUSTER != "mainnet-beta"
    val ignoring = (ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 28.dp)) {
        Label("Settings")
        Spacer(Modifier.height(14.dp))
        Text("Your rules.", style = MaterialTheme.typography.displayMedium, color = c.bone)
        Spacer(Modifier.height(24.dp))

        if (v != null) {
            Label("Share of each win to bank")
            Spacer(Modifier.height(8.dp))
            Chips(listOf(2500 to "25%", 5000 to "50%", 7500 to "75%", 10000 to "100%"), bps, { it == 5000 || tier }) { bps = it }
            if (!tier) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Everyone starts at 50%. Hold ${SKR_TIER_RAW / 1_000_000} SKR in your connected wallet to pick your own share and to bank wins as staked SKR. You hold ${String.format(java.util.Locale.US, "%.0f", s.skrRaw / 1e6)}.",
                    style = MaterialTheme.typography.bodyMedium, color = c.dim,
                )
            }
            Spacer(Modifier.height(24.dp))
            Label("Bank wins as")
            Spacer(Modifier.height(8.dp))
            if (!testing) {
                Chips(listOf("sol" to "SOL", "skr" to "Staked SKR"), s.bankAsset, { it == "sol" || tier }) { vm.setBankAsset(it) }
                if (tier) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Staked SKR turns your share of each win into SKR and stakes it in your vault, where it earns. It only stakes the SKR it just bought, never SKR you hold yourself. Small buys wait for the next win until they reach 1 SKR.",
                        style = MaterialTheme.typography.bodyMedium, color = c.dim,
                    )
                }
            } else {
                Chips(listOf("sol" to "SOL"), s.bankAsset, { true }) { vm.setBankAsset(it) }
                Spacer(Modifier.height(10.dp))
                Text(
                    "This is a test build, so every win is banked as SOL. Staked SKR is available in the real app.",
                    style = MaterialTheme.typography.bodyMedium, color = c.dim,
                )
            }
            Spacer(Modifier.height(24.dp))
            Label("Wait before money leaves")
            Spacer(Modifier.height(8.dp))
            Chips(
                buildList {
                    if (testing) add(ONE_MINUTE to "1 min (test)")
                    add(ONE_HOUR to "1 hour"); add(ONE_DAY to "24 hours"); add(3 * ONE_DAY to "3 days"); add(7 * ONE_DAY to "7 days")
                },
                cooldown, { true },
            ) { cooldown = it }
            Spacer(Modifier.height(10.dp))
            Text(
                if (cooldown < v.cooldownSecs) "A shorter wait only starts after your current wait of ${fmtDuration(v.cooldownSecs)} has passed. That is what stops a weak moment from undoing it."
                else "This is how long money you take out of the vault waits before it reaches you. You can make it longer at any time.",
                style = MaterialTheme.typography.bodyMedium, color = c.dim,
            )
            val changed = bps != v.bankBps || cooldown != v.cooldownSecs
            if (changed) {
                Spacer(Modifier.height(16.dp))
                KeptButton("Save and approve in your wallet", { vm.setRules(cooldown, bps) }, enabled = s.busy == null)
            }
            s.error?.let { Spacer(Modifier.height(14.dp)); Notice(it, isError = true) }
            s.message?.let { Spacer(Modifier.height(14.dp)); Notice(it, isError = false) }
            Spacer(Modifier.height(28.dp))
        }

        Label("Keep it running")
        Spacer(Modifier.height(4.dp))
        Text(
            "Kept watches your trading wallet in the background. Phones stop apps to save battery, so let Kept run freely and no win is missed. If it ever stops, anything it missed is banked when it wakes and marked as found late.",
            style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(vertical = 10.dp),
        )
        KeptGhostButton(if (ignoring) "Kept is allowed to keep running" else "Let Kept keep running", {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }, enabled = !ignoring)
        Spacer(Modifier.height(28.dp))

        Label("Your trading wallet key")
        Text(
            "The key to your trading wallet was made on this phone and is locked inside it. Reveal it if you want to use the same wallet in another app. It asks for your fingerprint or PIN first. Anyone who has the key can move the money in the trading wallet, so keep it private.",
            style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(vertical = 10.dp),
        )
        KeptGhostButton("Reveal key", { onExport { exported = it } })
        Spacer(Modifier.height(28.dp))

        Label("Check it yourself")
        Spacer(Modifier.height(4.dp))
        s.vaultAddress?.let { StatRow("Your vault", short(it, 5), onClick = { openExplorer(ctx, "account", it) }) }
        s.mainWallet?.let { StatRow("Your connected wallet", short(it, 5), onClick = { openExplorer(ctx, "account", it) }) }
        StatRow("Vault program", short(BuildConfig.VAULT_PROGRAM, 5), onClick = { openExplorer(ctx, "account", BuildConfig.VAULT_PROGRAM) })
        StatRow("Network", if (testing) "Solana devnet (test)" else "Solana mainnet")
        Spacer(Modifier.height(28.dp))

        Label("What Kept does not do")
        Text(
            "It does not stop you moving money out of the trading wallet by hand. That wallet is yours, and the vault is the part that is protected. It does not pick trades or promise profit. A win is banked only when you sell for more than you paid. Tokens Kept never saw you buy are not counted.",
            style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(vertical = 10.dp),
        )
        Spacer(Modifier.height(40.dp))
    }

    exported?.let { key ->
        AlertDialog(
            onDismissRequest = { exported = null },
            containerColor = c.raised,
            title = { Text("Trading wallet key", color = c.bone) },
            text = { Text(key, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = c.bone) },
            confirmButton = { TextButton(onClick = { copyToClipboard(ctx, "Trading wallet key", key); exported = null }) { Text("Copy and close", color = c.brass) } },
            dismissButton = { TextButton(onClick = { exported = null }) { Text("Close", color = c.dim) } },
        )
    }
}
