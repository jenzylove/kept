package app.kept.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kept.AppState
import app.kept.AppViewModel
import app.kept.trade.Jupiter
import kotlinx.coroutines.delay

/**
 * Trade with the trading wallet, signed on this phone. You can also trade anywhere else with the
 * same wallet (import its key into another wallet app); Kept's reflex watches it either way.
 */
@Composable
fun TradeScreen(vm: AppViewModel, s: AppState) {
    val c = Kept.colors
    var buying by rememberSaveable { mutableStateOf(true) }
    var mint by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var held by rememberSaveable { mutableStateOf(0L) }
    var quote by androidx.compose.runtime.remember { mutableStateOf<Jupiter.Quote?>(null) }
    var quoteError by rememberSaveable { mutableStateOf<String?>(null) }
    var decimals by rememberSaveable { mutableStateOf(6) }

    val inMint = if (buying) Jupiter.SOL else mint.trim()
    val outMint = if (buying) mint.trim() else Jupiter.SOL
    // Buy: amount is SOL. Sell: amount is a share of what the wallet holds (percent).
    val rawIn: Long? = if (buying) solInputToLamports(amount) else percentageOf(held, amount)

    LaunchedEffect(mint) {
        if (mint.trim().length in 32..44) vm.tradingTokenBalance(mint.trim()) { held = it }
    }
    LaunchedEffect(inMint, outMint, rawIn) {
        quote = null; quoteError = null
        if (mint.trim().length !in 32..44 || rawIn == null) return@LaunchedEffect
        delay(500) // let typing settle
        runCatching { vm.quote(inMint, outMint, rawIn) }.onSuccess { quote = it }.onFailure { quoteError = it.message?.take(160) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 28.dp)) {
        Label("Trade")
        Spacer(Modifier.height(14.dp))
        Text("Trade from your trading wallet.", style = MaterialTheme.typography.displayMedium, color = c.bone)
        Spacer(Modifier.height(10.dp))
        Text(
            "Sell above what you paid and Kept banks your share within seconds. Balance: ${fmtSol(s.tradingLamports)} SOL.",
            style = MaterialTheme.typography.bodyLarge, color = c.dim,
        )
        Spacer(Modifier.height(24.dp))
        if (app.kept.BuildConfig.CLUSTER != "mainnet-beta") {
            Notice(
                "This build runs on Solana devnet. In app swaps use Jupiter, which only runs on mainnet. Trade with any other app or script from this wallet: Kept still banks every win.",
                isError = false,
            )
            Spacer(Modifier.height(40.dp))
            return@Column
        }
        Chips(listOf(true to "Buy", false to "Sell"), buying, { true }) { buying = it; amount = ""; quote = null }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = mint, onValueChange = { mint = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Token address (paste the CA)") }, shape = RoundedCornerShape(4.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.brass, unfocusedBorderColor = c.line, focusedLabelColor = c.brass, cursorColor = c.brass),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = amount, onValueChange = { amount = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (buying) "Spend (SOL)" else "Sell (% of what you hold)") }, shape = RoundedCornerShape(4.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.brass, unfocusedBorderColor = c.line, focusedLabelColor = c.brass, cursorColor = c.brass),
        )
        if (amount.isNotBlank() && rawIn == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (buying) "Enter a positive SOL amount with no more than 9 decimal places." else "Enter a percentage above 0 and no more than 100.",
                style = MaterialTheme.typography.labelSmall, color = c.loss,
            )
        }
        if (!buying) {
            Spacer(Modifier.height(10.dp))
            Chips(listOf("25" to "25%", "50" to "50%", "100" to "All"), amount, { held > 0 }) { amount = it }
            Text("You hold ${held} raw units of this token.", style = MaterialTheme.typography.labelSmall, color = c.dim, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(20.dp))

        quote?.let { q ->
            val out = if (buying) "${q.outAmount} units of the token" else "${fmtSol(q.outAmount)} SOL"
            StatRow("You get about", out)
            q.priceImpactPct?.let { p -> StatRow("Price impact", "${String.format(java.util.Locale.US, "%.2f", (p.toDoubleOrNull() ?: 0.0) * 100)}%") }
            Spacer(Modifier.height(16.dp))
            KeptButton(if (buying) "Buy" else "Sell", { vm.swap(q, if (buying) "Buying…" else "Selling…") }, busy = s.busy != null)
        }
        quoteError?.let { Notice("No route found: $it", isError = true) }
        s.error?.let { Spacer(Modifier.height(12.dp)); Notice(it, isError = true) }
        s.message?.let { Spacer(Modifier.height(12.dp)); Notice(it, isError = false) }
        s.busy?.let { Spacer(Modifier.height(10.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = c.dim) }
        Spacer(Modifier.height(40.dp))
    }
}
