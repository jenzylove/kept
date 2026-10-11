package app.kept.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kept.AppViewModel
import app.kept.engine.Engine
import app.kept.engine.WalletRead
import androidx.compose.runtime.collectAsState

/** The front door: paste any wallet and see the truth about its last 90 days. */
@Composable
fun MirrorScreen(vm: AppViewModel, onStart: (() -> Unit)?, connectedWallet: String?) {
    val ui by vm.mirror.collectAsState()
    var address by rememberSaveable { mutableStateOf("") }
    val c = Kept.colors
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(top = 18.dp, bottom = TabBarSpace)) {
        if (onStart == null) Text("Mirror", style = MaterialTheme.typography.headlineMedium, color = c.text) else Wordmark()
        Spacer(Modifier.height(26.dp))
        if (onStart == null) Headline("Check a wallet before you follow it.", "follow it.") else Headline("You won more than you kept.", "kept.")
        Spacer(Modifier.height(14.dp))
        Text(
            if (onStart == null) "Paste any Solana wallet: a caller, a dev, a friend, or your own. Kept reads its real trades and shows how often it wins, how much it gives back, and what it kept."
            else "Paste any Solana wallet, yours or one you follow. Kept reads its real trades and shows what it won, what it gave back, and what would be sitting in a vault today.",
            style = MaterialTheme.typography.bodyLarge, color = c.dim,
        )
        Spacer(Modifier.height(28.dp))

        KeptField(address, { address = it.trim() }, "Wallet address", keyboard = KeyboardType.Ascii, address = true)
        if (onStart == null) {
            val list by vm.watched.collectAsState()
            if (list.isNotEmpty() && !ui.running) {
                Spacer(Modifier.height(14.dp))
                Label("Wallets you watch")
                Spacer(Modifier.height(8.dp))
                Chips(list.map { it to short(it, 4) }, address, { true }) { address = it; vm.runMirror(it) }
            }
        }
        if (connectedWallet != null && address.isBlank()) {
            Text(
                "Use my trading wallet", style = MaterialTheme.typography.labelLarge, color = c.accent,
                modifier = Modifier.clickable { address = connectedWallet }.padding(top = 12.dp, bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        KeptButton(if (ui.running) "Reading…" else "Read this wallet", onClick = { vm.runMirror(address) }, busy = ui.running, enabled = address.isNotBlank())

        ui.progress?.takeIf { ui.running }?.let { (done, total) ->
            Spacer(Modifier.height(14.dp))
            Text("Reading $done of $total trades…", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        }
        ui.error?.let { Spacer(Modifier.height(14.dp)); Notice(it, isError = true) }

        ui.result?.let { r ->
            Spacer(Modifier.height(28.dp))
            val m = r.mirror
            val series = remember(m) { Engine.series(m) }
            val read = remember(m, r.truncated) { WalletRead.of(m, r.truncated) }
            val watching by vm.watched.collectAsState()
            Label("${short(r.address, 5)} · last ${r.days} days")
            Spacer(Modifier.height(10.dp))
            if (read.verdict == WalletRead.Verdict.Nothing) {
                Headline(read.verdict.line, read.verdict.lean, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("We read ${r.scanned} transactions and found no sale we could price in the last ${r.days} days.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
            } else {
                Headline(read.verdict.line, read.verdict.lean, style = MaterialTheme.typography.headlineMedium)
                if (read.partial) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (r.truncated) "This wallet trades more than we could read, so this is its most recent stretch, not all ${r.days} days."
                        else "${read.unpricedPercent}% of its sales could not be priced, so treat this as a partial picture.",
                        style = MaterialTheme.typography.bodyMedium, color = c.dim,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Panel {
                    val up = read.net.signum() >= 0
                    Label(if (up) "Made overall" else "Lost overall")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text((if (up) "+" else "−") + fmtSol(read.net.abs(), 2), style = MaterialTheme.typography.displayLarge.merge(NumberStyle), color = if (up) c.text else c.loss)
                        Text("SOL", style = MaterialTheme.typography.titleMedium, color = c.dim, modifier = Modifier.padding(start = 10.dp, bottom = 11.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Staircase(series.wallet, series.kept, height = 150.dp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Legend(c.loss, "What the wallet made")
                        Legend(c.accent, "If half of each win was kept")
                    }
                }
                Spacer(Modifier.height(18.dp))
                StatRow("Sales that won", "${read.wins} of ${read.wins + read.losses} (${read.winPercent}%)")
                StatRow("Won on those", "+${fmtSol(m.won, 2)} SOL")
                StatRow("Gave back on the rest", "−${fmtSol(m.givenBack, 2)} SOL", valueColor = c.loss)
                read.gaveBackPercent?.let { StatRow("Share of winnings given back", "$it%", valueColor = if (it > WalletRead.LEAKY_PERCENT) c.loss else c.text) }
                StatRow("A typical win", "+${fmtSol(read.typicalWin, 3)} SOL")
                if (read.losses > 0) {
                    StatRow("A typical loss", "−${fmtSol(read.typicalLoss, 3)} SOL")
                    StatRow("Biggest single loss", "−${fmtSol(read.biggestLoss, 3)} SOL")
                }
                if (m.unpriced > 0) StatRow("Sales we could not price", "${m.unpriced}")
                StatRow("In a vault today, with Kept on", "${fmtSol(m.wouldHaveKept, 2)} SOL", valueColor = c.accent)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Worked out from ${r.scanned} of this wallet's real transactions. It is a record of what happened, not advice, and a good record does not promise the next trade.",
                    style = MaterialTheme.typography.bodyMedium, color = c.dim,
                )
                if (onStart == null) {
                    Spacer(Modifier.height(18.dp))
                    when {
                        r.address in watching -> KeptGhostButton("Stop watching this wallet", { vm.unwatch(r.address) })
                        vm.hasSkrTier() -> KeptGhostButton("Watch this wallet", { vm.watch(r.address) })
                        else -> Text(
                            "Hold ${SKR_TIER_RAW / 1_000_000} SKR in your connected wallet to look back 90 days and keep a list of wallets you watch.",
                            style = MaterialTheme.typography.bodyMedium, color = c.dim,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Label("Its biggest wins, with proof")
                Spacer(Modifier.height(6.dp))
                m.wins.sortedByDescending { it.profit }.take(5).forEach {
                    StatRow(short(it.sig), "+${fmtSol(it.profit, 3)} SOL", onClick = { openExplorer(ctx, "tx", it.sig) })
                }
            }
            onStart?.let {
                Spacer(Modifier.height(28.dp))
                KeptButton("Start keeping", it)
            }
        }
        if (ui.result == null && onStart != null) {
            Spacer(Modifier.height(12.dp))
            KeptGhostButton("Skip, set up Kept", onStart)
            if (!ui.running) {
                Spacer(Modifier.height(34.dp))
                GlassStairs(30, 30, Modifier.bleed(ScreenPadding), height = 260.dp)
                Spacer(Modifier.height(14.dp))
                Text("Each win you keep is a step. The stairs only go up.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(4.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Kept.colors.dim)
    }
}
