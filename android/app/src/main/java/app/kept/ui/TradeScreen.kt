package app.kept.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch

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

    val scroll = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // When a price arrives the button appears below it; bring it into view so nobody has to hunt for it.
    val reveal: () -> Unit = { scope.launch { delay(120); scroll.animateScrollTo(scroll.maxValue) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = ScreenPadding).padding(top = 18.dp, bottom = TabBarSpace)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Trade", style = MaterialTheme.typography.headlineMedium, color = c.text)
            Text(
                "${fmtSol(s.tradingLamports)} SOL to trade", style = MaterialTheme.typography.labelSmall.merge(NumberStyle), color = c.text,
                modifier = Modifier.background(c.glass, CircleShape).border(1.dp, Color.White, CircleShape).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        Spacer(Modifier.height(20.dp))

        if (app.kept.BuildConfig.CLUSTER != "mainnet-beta") {
            Headline("Trading opens on mainnet.", "mainnet.")
            Spacer(Modifier.height(12.dp))
            Text(
                "This is a test build on Solana devnet, where there is no market to trade on. Trade from this wallet with any other app and Kept still banks every win.",
                style = MaterialTheme.typography.bodyLarge, color = c.dim,
            )
            return@Column
        }

        Row(Modifier.fillMaxWidth().background(c.raised, CircleShape).padding(5.dp)) {
            ModeHalf("Buy", buying, Modifier.weight(1f)) { if (!buying) { buying = true; token = null; amount = ""; vm.clearMessage() } }
            ModeHalf("Sell", !buying, Modifier.weight(1f)) { if (buying) { buying = false; token = null; amount = ""; vm.clearMessage(); vm.loadHoldings() } }
        }
        Spacer(Modifier.height(24.dp))

        // The outgoing screen is still drawn while it fades, so each side works from the token it was shown with.
        AnimatedContent(buying to token, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "trade") { (isBuy, shown) ->
            Column {
                when {
                    shown == null && isBuy -> TokenSearch(vm) { token = it; amount = "" }
                    shown == null -> HoldingList(holdings) { token = it.token; amount = "" }
                    isBuy -> BuyForm(vm, s, shown, amount, { amount = it }, reveal) { token = null; amount = ""; vm.clearMessage() }
                    else -> SellForm(vm, s, shown, holdings?.firstOrNull { it.token.mint == shown.mint }, amount, { amount = it }, reveal) { token = null; amount = ""; vm.clearMessage() }
                }
            }
        }

        s.error?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = true) }
        s.message?.let { Spacer(Modifier.height(16.dp)); Notice(it, isError = false) }
    }
}

/** One half of the Buy or Sell switch. The chosen half is a white pill that lifts off the track. */
@Composable
private fun ModeHalf(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Kept.colors
    val fill by animateColorAsState(if (on) Color.White else Color.Transparent, tween(200), label = "mode")
    Box(
        modifier.height(46.dp).shadow(if (on) 6.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.16f))
            .background(fill, CircleShape).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp), color = if (on) c.text else c.dim) }
}

@Composable
private fun TokenSearch(vm: AppViewModel, onPick: (Token) -> Unit) {
    val c = Kept.colors
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Token>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var starters by remember { mutableStateOf(Tokens.STARTERS) }
    LaunchedEffect(Unit) { runCatching { vm.starterTokens() }.onSuccess { starters = it } }

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

    KeptField(query, { query = it }, "Search a token, or paste its address")
    Spacer(Modifier.height(22.dp))
    val list = results
    when {
        list == null -> {
            Label("Popular")
            starters.forEach { t -> TokenRow(t, trailing = t.usdPrice?.let(::fmtUsd)) { onPick(t) } }
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
            Headline("Nothing to sell yet.", "yet.", style = MaterialTheme.typography.headlineMedium)
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
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(t.symbol, t.mint, t.icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.symbol, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1)
                    if (!t.verified) {
                        Spacer(Modifier.width(8.dp))
                        Text("unverified", style = MaterialTheme.typography.labelSmall, color = c.loss)
                    }
                }
                val under = sub ?: t.name
                if (under.isNotBlank()) Text(under, style = MaterialTheme.typography.bodyMedium, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.let { Text(it, style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), color = c.text, maxLines = 1) }
        }
        Hairline()
    }
}

@Composable
private fun Chosen(t: Token, onChange: () -> Unit) {
    val c = Kept.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TokenIcon(t.symbol, t.mint, t.icon, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.symbol, style = MaterialTheme.typography.headlineMedium, color = c.text, maxLines = 1)
            if (t.name.isNotBlank()) Text(t.name, style = MaterialTheme.typography.bodyMedium, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "Change", style = MaterialTheme.typography.labelLarge, color = c.text,
            modifier = Modifier.background(c.glass, CircleShape).border(1.dp, c.line, CircleShape).clip(CircleShape).clickable(onClick = onChange).padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
    if (!t.verified) {
        Spacer(Modifier.height(10.dp))
        Text("This token is not verified. Anyone can make a token with any name, so check the address before you buy.", style = MaterialTheme.typography.bodyMedium, color = c.loss)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun BuyForm(vm: AppViewModel, s: AppState, t: Token, amount: String, onAmount: (String) -> Unit, reveal: () -> Unit, onChange: () -> Unit) {
    val c = Kept.colors
    val spendable = (s.tradingLamports - FEE_RESERVE).coerceAtLeast(0)
    val lamports = solInputToLamports(amount)
    val tooMuch = lamports != null && lamports > spendable
    val q = rememberQuote(vm, Jupiter.SOL, t.mint, lamports.takeIf { !tooMuch })
    LaunchedEffect(q.quote != null) { if (q.quote != null) reveal() }

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
private fun SellForm(vm: AppViewModel, s: AppState, t: Token, held: AppViewModel.Held?, amount: String, onAmount: (String) -> Unit, reveal: () -> Unit, onChange: () -> Unit) {
    val c = Kept.colors
    val raw = held?.raw ?: 0L
    val all = rawToInput(raw, t.decimals)
    // "All of it" sells exactly what is held, down to the last unit.
    val typed = if (amount == all) raw else tokenInputToRaw(amount, t.decimals)
    val tooMuch = typed != null && typed > raw
    val rawIn = typed?.takeIf { it in 1..raw }
    val q = rememberQuote(vm, t.mint, Jupiter.SOL, rawIn)
    LaunchedEffect(q.quote != null) { if (q.quote != null) reveal() }

    Chosen(t, onChange)
    Label("Sell")
    Spacer(Modifier.height(6.dp))
    BigAmount(amount, onAmount, t.symbol)
    Spacer(Modifier.height(8.dp))
    Text("You hold ${Tokens.amount(raw, t.decimals)} ${t.symbol}" + (held?.costLamports?.let { ", bought for ${fmtSol(it)} SOL" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = c.dim)
    Spacer(Modifier.height(14.dp))
    Chips(
        listOf(rawToInput(raw / 4, t.decimals) to "25%", rawToInput(raw / 2, t.decimals) to "Half", all to "All of it"),
        amount, { raw > 0 },
    ) { onAmount(it) }
    Spacer(Modifier.height(18.dp))
    when {
        raw == 0L -> Text("You don't hold any ${t.symbol} right now.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        tooMuch -> Text("That is more than you hold. The most you can sell is ${Tokens.amount(raw, t.decimals)} ${t.symbol}.", style = MaterialTheme.typography.bodyMedium, color = c.loss)
        q.error != null -> Text(q.error, style = MaterialTheme.typography.bodyMedium, color = c.loss)
        q.quote != null && rawIn != null -> {
            StatRow("You get about", "${fmtSol(q.quote.outAmount)} SOL")
            Impact(q.quote)
            KeptPreview(s, held?.costLamports, raw, rawIn, q.quote.outAmount)
            Spacer(Modifier.height(18.dp))
            val label = if (rawIn == raw) "Sell all ${t.symbol}" else "Sell ${Tokens.amount(rawIn, t.decimals)} ${t.symbol}"
            KeptButton(label, { vm.swap(q.quote, "Selling…", "Sold. If that was a win, your share lands in the vault in a few seconds.") }, busy = s.busy != null)
        }
        rawIn != null -> Text("Finding the best price…", style = MaterialTheme.typography.bodyMedium, color = c.dim)
    }
}

/** Under this much either way, a sale is called even: it is smaller than the fees around it. */
private const val EVEN_LAMPORTS = 50_000L

/**
 * Before the sale: the sum Kept will do. What this part cost, what the sale brings, the profit, and
 * so what goes to the vault. Shown as rows so the answer does not hide inside a sentence.
 */
@Composable
private fun KeptPreview(s: AppState, costLamports: Long?, heldRaw: Long, sellRaw: Long, outLamports: Long) {
    val c = Kept.colors
    val bps = s.vault?.bankBps ?: 5000
    if (costLamports == null || heldRaw <= 0) {
        Spacer(Modifier.height(16.dp))
        VaultLine("Nothing goes to your vault. Kept did not see you buy this token, so it cannot tell whether this sale is a win.", win = false)
        return
    }
    val costPart = java.math.BigInteger.valueOf(costLamports).multiply(java.math.BigInteger.valueOf(sellRaw)).divide(java.math.BigInteger.valueOf(heldRaw)).toLong()
    val profit = outLamports - costPart
    StatRow("You paid for this part", "${fmtSol(costPart)} SOL")
    StatRow(
        "Profit on this sale",
        when {
            profit > EVEN_LAMPORTS -> "+${fmtSol(profit)} SOL"
            profit < -EVEN_LAMPORTS -> "−${fmtSol(-profit)} SOL"
            else -> "About even"
        },
        valueColor = if (profit > EVEN_LAMPORTS) c.accent else if (profit < -EVEN_LAMPORTS) c.loss else c.dim,
    )
    Spacer(Modifier.height(16.dp))
    if (profit > 0) {
        VaultLine("${fmtSol(profit * bps / 10_000)} SOL goes to your vault. That is ${bps / 100}% of the profit, moved by Kept right after the sale.", win = true)
    } else {
        VaultLine("Nothing goes to your vault. Kept only keeps a share when you sell for more than you paid.", win = false)
    }
}

@Composable
private fun VaultLine(text: String, win: Boolean) {
    val c = Kept.colors
    Row(Modifier.fillMaxWidth().background(if (win) c.accentSoft else c.raised, RoundedCornerShape(20.dp)).padding(16.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(if (win) c.accent else c.dim, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.text)
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

private fun fmtUsd(v: Double): String = when {
    v >= 1 -> String.format(Locale.US, "$%,.2f", v)
    v >= 0.01 -> String.format(Locale.US, "$%.4f", v)
    else -> "$" + java.math.BigDecimal(v).round(java.math.MathContext(3)).toPlainString()
}

/** A typed token amount as raw units, or null if it is not a positive number with at most [decimals] places. */
private fun tokenInputToRaw(input: String, decimals: Int): Long? {
    val v = input.trim()
    if (!v.matches(Regex("^[0-9]+(?:\\.[0-9]*)?$"))) return null
    return runCatching {
        java.math.BigDecimal(v.trimEnd('.')).movePointRight(decimals).setScale(0, java.math.RoundingMode.DOWN).longValueExact().takeIf { it > 0 }
    }.getOrNull()
}

/** Raw units as a number someone could have typed: no grouping, no trailing zeros. */
private fun rawToInput(raw: Long, decimals: Int): String =
    java.math.BigDecimal.valueOf(raw).movePointLeft(decimals).stripTrailingZeros().toPlainString()

private fun lamportsToInput(lamports: Long): String =
    java.math.BigDecimal.valueOf(lamports).movePointLeft(9).setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
