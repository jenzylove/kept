package app.kept.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kept.AppState
import app.kept.AppViewModel
import app.kept.trade.Jupiter
import app.kept.trade.Token
import app.kept.trade.Tokens
import java.util.Locale
import kotlinx.coroutines.delay

/** SOL the trading wallet keeps back so it can always pay for a sell and for banking the win. */
private const val FEE_RESERVE = 8_000_000L

/**
 * Trade with the trading wallet, signed on this phone. You can also trade anywhere else with the
 * same wallet (import its key into another wallet app); Kept's reflex watches it either way.
 */
@Composable
fun TradeScreen(vm: AppViewModel, s: AppState) {
    val c = Kept.colors
    var buying by rememberSaveable { mutableStateOf(true) }
    var token by remember { mutableStateOf<Token?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    val holdings by vm.holdings.collectAsState()

    LaunchedEffect(Unit) { vm.loadHoldings() }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label("Trade")
            Text("${fmtSol(s.tradingLamports)} SOL to trade", style = MaterialTheme.typography.labelSmall, color = c.dim)
        }
        Spacer(Modifier.height(18.dp))

        if (app.kept.BuildConfig.CLUSTER != "mainnet-beta") {
            Text("Trading opens on mainnet.", style = MaterialTheme.typography.displayMedium, color = c.bone)
            Spacer(Modifier.height(12.dp))
            Text(
                "This is a test build on Solana devnet, where there is no market to trade on. Trade from this wallet with any other app and Kept still banks every win.",
                style = MaterialTheme.typography.bodyLarge, color = c.dim,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.Bottom) {
            ModeWord("Buy", buying) { if (!buying) { buying = true; token = null; amount = ""; vm.clearMessage() } }
            Spacer(Modifier.width(28.dp))
            ModeWord("Sell", !buying) { if (buying) { buying = false; token = null; amount = ""; vm.clearMessage(); vm.loadHoldings() } }
        }
        Spacer(Modifier.height(26.dp))

        // The outgoing screen is still drawn while it fades, so each side works from the token it was shown with.
        AnimatedContent(buying to token, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "trade") { (isBuy, shown) ->
            Column {
                when {
                    shown == null && isBuy -> TokenSearch(vm) { token = it; amount = "" }
                    shown == null -> HoldingList(holdings) { token = it.token; amount = "" }
                    isBuy -> BuyForm(vm, s, shown, amount, { amount = it }) { token = null; amount = ""; vm.clearMessage() }
                    else -> SellForm(vm, s, shown, holdings?.firstOrNull { it.token.mint == shown.mint }, amount, { amount = it }) { token = null; amount = ""; vm.clearMessage() }
                }
            }
        }

        s.error?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = true) }
        s.message?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = false) }
        Spacer(Modifier.height(40.dp))
    }
}

/** Buy and Sell as two words to choose between, in the display face. */
@Composable
private fun ModeWord(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Kept.colors
    Column(Modifier.width(IntrinsicSize.Max).clickable(onClick = onClick)) {
        Text(text, style = MaterialTheme.typography.displayMedium, color = if (on) c.bone else c.line)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) c.brass else c.ink))
    }
}

@Composable
private fun TokenSearch(vm: AppViewModel, onPick: (Token) -> Unit) {
    val c = Kept.colors
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Token>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        failed = false
        if (query.isBlank()) { results = null; searching = false; return@LaunchedEffect }
        searching = true
        delay(350) // let typing settle
        try {
            results = vm.searchTokens(query).take(12)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            results = emptyList(); failed = true
        }
        searching = false
    }

    Field(query, { query = it }, "Search a token, or paste its address", KeyboardType.Text)
    Spacer(Modifier.height(22.dp))
    val list = results
    when {
        list == null -> {
            Label("Popular")
            Tokens.STARTERS.forEach { TokenRow(it, trailing = null) { onPick(it) } }
        }
        searching && list.isEmpty() -> Text("Searching…", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        failed -> Text("Couldn't search right now. Check your connection and try again.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        list.isEmpty() -> Text("No token matches that.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        else -> list.forEach { t -> TokenRow(t, trailing = t.usdPrice?.let(::fmtUsd)) { onPick(t) } }
    }
}

@Composable
private fun HoldingList(holdings: List<AppViewModel.Held>?, onPick: (AppViewModel.Held) -> Unit) {
    val c = Kept.colors
    when {
        holdings == null -> Text("Reading your trading wallet…", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        holdings.isEmpty() -> {
            Text("Nothing to sell yet.", style = MaterialTheme.typography.headlineMedium, color = c.bone)
            Spacer(Modifier.height(8.dp))
            Text("Buy a token and it shows up here, with what you paid for it.", style = MaterialTheme.typography.bodyLarge, color = c.dim)
        }
        else -> {
            Label("You hold")
            holdings.forEach { h ->
                TokenRow(
                    h.token,
                    trailing = Tokens.amount(h.raw, h.token.decimals),
                    sub = h.costLamports?.let { "paid ${fmtSol(it)} SOL" },
                ) { onPick(h) }
            }
        }
    }
}

@Composable
private fun TokenRow(t: Token, trailing: String?, sub: String? = null, onClick: () -> Unit) {
    val c = Kept.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.symbol, style = MaterialTheme.typography.titleMedium, color = c.bone, maxLines = 1)
                    if (!t.verified) {
                        Spacer(Modifier.width(8.dp))
                        Text("unverified", style = MaterialTheme.typography.labelSmall, color = c.loss)
                    }
                }
                val under = sub ?: t.name
                if (under.isNotBlank()) Text(under, style = MaterialTheme.typography.bodyMedium, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.let { Text(it, style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), color = c.bone, maxLines = 1) }
        }
        Hairline()
    }
}

@Composable
private fun Chosen(t: Token, onChange: () -> Unit) {
    val c = Kept.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(t.symbol, style = MaterialTheme.typography.headlineMedium, color = c.bone, maxLines = 1)
            if (t.name.isNotBlank()) Text(t.name, style = MaterialTheme.typography.bodyMedium, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Change", style = MaterialTheme.typography.labelLarge, color = c.brass, modifier = Modifier.clickable(onClick = onChange).padding(vertical = 6.dp))
    }
    if (!t.verified) {
        Spacer(Modifier.height(10.dp))
        Text("This token is not verified. Anyone can make a token with any name, so check the address before you buy.", style = MaterialTheme.typography.bodyMedium, color = c.loss)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun BuyForm(vm: AppViewModel, s: AppState, t: Token, amount: String, onAmount: (String) -> Unit, onChange: () -> Unit) {
    val c = Kept.colors
    val spendable = (s.tradingLamports - FEE_RESERVE).coerceAtLeast(0)
    val lamports = solInputToLamports(amount)
    val tooMuch = lamports != null && lamports > spendable
    val q = rememberQuote(vm, Jupiter.SOL, t.mint, lamports.takeIf { !tooMuch })

    Chosen(t, onChange)
    Label("Spend")
    Spacer(Modifier.height(6.dp))
    BigAmount(amount, onAmount, "SOL")
    Spacer(Modifier.height(14.dp))
    Chips(
        listOf("0.01" to "0.01", "0.05" to "0.05", "0.1" to "0.1", lamportsToInput(spendable) to "Max"),
        amount, { solInputToLamports(it)?.let { v -> v <= spendable } == true },
    ) { onAmount(it) }
    Spacer(Modifier.height(18.dp))
    when {
        spendable == 0L -> Text("Your trading wallet needs more SOL before it can buy. Add some from the Vault tab.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        tooMuch -> Text("You can spend up to ${fmtSol(spendable)} SOL. The rest stays back to pay network fees.", style = MaterialTheme.typography.bodyMedium, color = c.loss)
        q.error != null -> Text(q.error, style = MaterialTheme.typography.bodyMedium, color = c.loss)
        q.quote != null -> {
            StatRow("You get about", "${Tokens.amount(q.quote.outAmount, t.decimals)} ${t.symbol}")
            Impact(q.quote)
            Spacer(Modifier.height(18.dp))
            KeptButton("Buy ${t.symbol}", { vm.swap(q.quote, "Buying…", "Bought ${t.symbol}. Sell it for more than you paid and Kept banks your share.") }, busy = s.busy != null)
        }
        lamports != null -> Text("Finding the best price…", style = MaterialTheme.typography.bodyMedium, color = c.dim)
    }
}

@Composable
private fun SellForm(vm: AppViewModel, s: AppState, t: Token, held: AppViewModel.Held?, percent: String, onPercent: (String) -> Unit, onChange: () -> Unit) {
    val c = Kept.colors
    val raw = held?.raw ?: 0L
    val rawIn = percentageOf(raw, percent)
    val q = rememberQuote(vm, t.mint, Jupiter.SOL, rawIn)

    Chosen(t, onChange)
    StatRow("You hold", "${Tokens.amount(raw, t.decimals)} ${t.symbol}")
    held?.costLamports?.let { StatRow("You paid", "${fmtSol(it)} SOL") }
    Spacer(Modifier.height(20.dp))
    Label("Sell how much")
    Spacer(Modifier.height(10.dp))
    Chips(listOf("25" to "25%", "50" to "50%", "100" to "All of it"), percent, { raw > 0 }) { onPercent(it) }
    Spacer(Modifier.height(18.dp))
    when {
        raw == 0L -> Text("You don't hold any ${t.symbol} right now.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        q.error != null -> Text(q.error, style = MaterialTheme.typography.bodyMedium, color = c.loss)
        q.quote != null && rawIn != null -> {
            StatRow("You get about", "${fmtSol(q.quote.outAmount)} SOL")
            Impact(q.quote)
            Spacer(Modifier.height(16.dp))
            KeptPreview(s, held?.costLamports, raw, rawIn, q.quote.outAmount)
            Spacer(Modifier.height(18.dp))
            val label = if (percent == "100") "Sell all ${t.symbol}" else "Sell $percent% of ${t.symbol}"
            KeptButton(label, { vm.swap(q.quote, "Selling…", "Sold. If that was a win, your share lands in the vault in a few seconds.") }, busy = s.busy != null)
        }
        rawIn != null -> Text("Finding the best price…", style = MaterialTheme.typography.bodyMedium, color = c.dim)
    }
}

/** Before the sell: what this sale means for the vault. */
@Composable
private fun KeptPreview(s: AppState, costLamports: Long?, heldRaw: Long, sellRaw: Long, outLamports: Long) {
    val c = Kept.colors
    val bps = s.vault?.bankBps ?: 5000
    val text: String
    val win: Boolean
    if (costLamports == null || heldRaw <= 0) {
        text = "Kept did not see you buy this, so it can't tell if this sale is a win. Nothing moves to the vault."
        win = false
    } else {
        val costPart = java.math.BigInteger.valueOf(costLamports).multiply(java.math.BigInteger.valueOf(sellRaw)).divide(java.math.BigInteger.valueOf(heldRaw)).toLong()
        val profit = outLamports - costPart
        if (profit > 0) {
            text = "That is about ${fmtSol(profit)} SOL more than you paid. Kept moves ${fmtSol(profit * bps / 10_000)} SOL of it to your vault."
            win = true
        } else {
            text = "That is about ${fmtSol(-profit)} SOL less than you paid, so nothing moves to the vault."
            win = false
        }
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxSize().background(if (win) c.brass else c.line))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (win) c.bone else c.dim)
    }
}

@Composable
private fun Impact(q: Jupiter.Quote) {
    val pct = (q.priceImpactPct?.toDoubleOrNull() ?: 0.0) * 100
    if (pct >= 1.0) StatRow("Price moves against you by", String.format(Locale.US, "%.1f%%", pct), valueColor = Kept.colors.loss)
}

private class QuoteState(val quote: Jupiter.Quote?, val error: String?)

@Composable
private fun rememberQuote(vm: AppViewModel, inMint: String, outMint: String, rawIn: Long?): QuoteState {
    var quote by remember { mutableStateOf<Jupiter.Quote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inMint, outMint, rawIn) {
        quote = null; error = null
        if (rawIn == null || rawIn <= 0) return@LaunchedEffect
        delay(450) // let typing settle
        runCatching { vm.quote(inMint, outMint, rawIn) }
            .onSuccess { quote = it }
            .onFailure { error = "No one is offering a price for that right now. Try a different amount." }
    }
    return QuoteState(quote, error)
}

/** A single line to type into, with a hairline under it. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, keyboard: KeyboardType) {
    val c = Kept.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = c.dim)
            BasicTextField(
                value = value, onValueChange = { onChange(it.take(60)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.bone), cursorBrush = SolidColor(c.brass),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (value.isEmpty()) c.line else c.brass))
    }
}

/** The amount, set large in the number face, with its unit beside it. */
@Composable
private fun BigAmount(value: String, onChange: (String) -> Unit, unit: String) {
    val c = Kept.colors
    val style = MaterialTheme.typography.displayMedium.merge(NumberStyle)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text("0.00", style = style, color = c.line)
                BasicTextField(
                    value = value,
                    onValueChange = { v -> if (v.length <= 12 && v.all { it.isDigit() || it == '.' } && v.count { it == '.' } <= 1) onChange(v) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = style.copy(color = c.bone), cursorBrush = SolidColor(c.brass),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            Text(unit, style = MaterialTheme.typography.titleMedium, color = c.dim, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (value.isEmpty()) c.line else c.brass))
    }
}

private fun fmtUsd(v: Double): String = when {
    v >= 1 -> String.format(Locale.US, "$%,.2f", v)
    v >= 0.01 -> String.format(Locale.US, "$%.4f", v)
    else -> "$" + java.math.BigDecimal(v).round(java.math.MathContext(3)).toPlainString()
}

private fun lamportsToInput(lamports: Long): String =
    java.math.BigDecimal.valueOf(lamports).movePointLeft(9).setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
