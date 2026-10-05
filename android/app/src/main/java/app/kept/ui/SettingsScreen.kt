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
            Spacer(Modifier.height(20.dp))
            Label("Wait before money leaves")
            Spacer(Modifier.height(8.dp))
            Chips(
                buildList {
                    if (testing) add(ONE_MINUTE to "1 min (test)")
                    add(ONE_HOUR to "1 hour"); add(ONE_DAY to "24 hours"); add(3 * ONE_DAY to "3 days"); add(7 * ONE_DAY to "7 days")
                },
                cooldown, { true },
            ) { cooldown = it }
            Spacer(Modifier.height(8.dp))
            Text(
                if (cooldown < v.cooldownSecs) "A shorter wait takes effect after your current wait (${fmtDuration(v.cooldownSecs)}), not now."
                else "A longer wait takes effect immediately.",
                style = MaterialTheme.typography.labelSmall, color = c.dim,
            )
            Spacer(Modifier.height(14.dp))
            KeptButton("Save rules", { vm.setRules(cooldown, bps) }, enabled = (bps != v.bankBps || cooldown != v.cooldownSecs) && s.busy == null)
            Spacer(Modifier.height(28.dp))
        }

        Label("SKR")
        StatRow("SKR in your wallet", String.format(java.util.Locale.US, "%.2f", s.skrRaw / 1e6))
        StatRow("Needed to choose your own share", "${SKR_TIER_RAW / 1_000_000}")
        StatRow("Status", if (tier) "Unlocked" else "Standard 50%", valueColor = if (tier) c.brass else c.dim)
        Spacer(Modifier.height(28.dp))

        Label("Keep it running")
        Spacer(Modifier.height(4.dp))
        Text(
            "Kept watches your trading wallet in the background. Android may stop apps to save battery; allow Kept to run freely so no win is missed. If it ever stops, anything it missed is banked when it wakes, marked as found late.",
            style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(vertical = 10.dp),
        )
        KeptGhostButton(if (ignoring) "Battery limits are off for Kept" else "Allow Kept to run in the background", {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }, enabled = !ignoring)
        Spacer(Modifier.height(28.dp))

        Label("Your trading wallet key")
        Text(
            "The key was made on this phone and sealed in its secure hardware. You can reveal it to import the wallet into another app. Anyone with it can move the money in the trading wallet, so keep it private.",
            style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(vertical = 10.dp),
        )
        KeptGhostButton("Reveal key (needs your fingerprint or PIN)", { onExport { exported = it } })
        Spacer(Modifier.height(28.dp))

        Label("On chain")
        Spacer(Modifier.height(4.dp))
        s.vaultAddress?.let { StatRow("Your vault", short(it, 5), onClick = { openExplorer(ctx, "account", it) }) }
        s.mainWallet?.let { StatRow("Your wallet", short(it, 5), onClick = { openExplorer(ctx, "account", it) }) }
        StatRow("Vault program", short(BuildConfig.VAULT_PROGRAM, 5), onClick = { openExplorer(ctx, "account", BuildConfig.VAULT_PROGRAM) })
        StatRow("Network", BuildConfig.CLUSTER)
        Spacer(Modifier.height(28.dp))

        Label("What Kept does not do")
        Text(
            "It does not stop you moving money out of the trading wallet by hand: that wallet is yours. The vault is the part that's protected. It does not pick trades or guarantee profit. A win is banked only when the wallet sells above its cost; coins with no known cost are never banked.",
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
