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
        Headline("You won more than you kept.", "kept.")
        Spacer(Modifier.height(14.dp))
        Text(
            "Paste any Solana wallet. We read its real trades and show what it won, what it gave back, and what would be sitting in a vault if part of every win had been kept.",
            style = MaterialTheme.typography.bodyLarge, color = c.dim,
        )
        Spacer(Modifier.height(28.dp))

        KeptField(address, { address = it.trim() }, "Wallet address", keyboard = KeyboardType.Ascii, address = true)
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
            Label("Last ${r.days} days")
            Spacer(Modifier.height(10.dp))
            if (m.wins.isEmpty() && m.losses.isEmpty()) {
                Headline("No winning sells found.", "found.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("We read ${r.scanned} transactions and found no sale we could price in the last ${r.days} days.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
            } else {
                Panel {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(fmtSol(m.won, 2), style = MaterialTheme.typography.displayLarge.merge(NumberStyle), color = c.text)
                        Text("SOL", style = MaterialTheme.typography.titleMedium, color = c.dim, modifier = Modifier.padding(start = 10.dp, bottom = 11.dp))
                    }
                    Text("won on ${m.wins.size} winning ${if (m.wins.size == 1) "sell" else "sells"}", style = MaterialTheme.typography.bodyLarge, color = c.dim)
                    Spacer(Modifier.height(22.dp))
                    Staircase(series.wallet, series.kept, height = 150.dp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Legend(c.loss, "What the wallet made")
                        Legend(c.accent, "Kept at 50%")
                    }
                }
                Spacer(Modifier.height(22.dp))
                StatRow("Gave back on ${m.losses.size} losing ${if (m.losses.size == 1) "sell" else "sells"}", "−${fmtSol(m.givenBack, 2)} SOL", valueColor = c.loss)
                StatRow("In your vault, if Kept had been on", "${fmtSol(m.wouldHaveKept, 2)} SOL", valueColor = c.accent)
                if (m.unpriced > 0) StatRow("Sells with no known cost (not counted)", "${m.unpriced}")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Computed from this wallet's real transactions over ${r.scanned} reads. Simple sum: it ignores how banking would have changed your later trade sizes." +
                        if (r.truncated) " History was longer than we read, so older trades are not counted." else "",
                    style = MaterialTheme.typography.bodyMedium, color = c.dim,
                )
                Spacer(Modifier.height(20.dp))
                Label("Your biggest wins, with proof")
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
