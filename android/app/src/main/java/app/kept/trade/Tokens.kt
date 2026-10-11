package app.kept.trade

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A token as a person knows it: a ticker and a name, not an address. */
data class Token(val mint: String, val symbol: String, val name: String, val decimals: Int, val usdPrice: Double?, val verified: Boolean, val icon: String? = null)

/** Names, tickers and prices for tokens, from Jupiter's public token search. Display only: nothing here is signed. */
class Tokens(private val client: OkHttpClient = app.kept.chain.Net.client()) {
    private val known = HashMap<String, Token>()

    /** Search by ticker, name or address. Verified tokens first. */
    suspend fun search(query: String): List<Token> = withContext(Dispatchers.IO) {
        val url = "$BASE/search?query=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        parse(body).also { list -> synchronized(known) { list.forEach { known[it.mint] = it } } }
    }

    /** The tokens offered before the user types anything, with their pictures once the service answers. */
    suspend fun starters(): List<Token> {
        val found = byMint(STARTERS.map { it.mint })
        return STARTERS.map { s -> found[s.mint]?.let { s.copy(icon = it.icon, usdPrice = it.usdPrice) } ?: s }
    }

    /** Look up tokens by address; anything the service does not know is left out. */
    suspend fun byMint(mints: List<String>): Map<String, Token> {
        val want = mints.distinct()
        if (want.isEmpty()) return emptyMap()
        runCatching { want.chunked(50).forEach { search(it.joinToString(",")) } }
        return synchronized(known) { want.mapNotNull { m -> known[m]?.let { m to it } }.toMap() }
    }

    companion object {
        const val BASE = "https://lite-api.jup.ag/tokens/v2"
        private val json = Json { ignoreUnknownKeys = true }

        /** The tokens offered before the user types anything. */
        val STARTERS = listOf(
            Token("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", "SKR", "Seeker", 6, null, true),
            Token("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", "USD Coin", 6, null, true),
            Token("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", "Jupiter", 6, null, true),
            Token("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "BONK", "Bonk", 5, null, true),
        )

        fun parse(body: String): List<Token> {
            val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val mint = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val decimals = o["decimals"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (mint.length !in 32..44 || decimals !in 0..18 || mint == Jupiter.SOL) return@mapNotNull null
                Token(
                    mint = mint,
                    // Tickers and names are whatever a token's maker typed, so keep them short and on one line.
                    symbol = clean(o["symbol"]?.jsonPrimitive?.contentOrNull, 12).ifBlank { mint.take(4) },
                    name = clean(o["name"]?.jsonPrimitive?.contentOrNull, 28),
                    decimals = decimals,
                    usdPrice = o["usdPrice"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 && it.isFinite() },
                    verified = o["isVerified"]?.jsonPrimitive?.booleanOrNull == true,
                    // Pictures are only ever loaded over https.
                    icon = o["icon"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") && it.length < 400 },
                )
            }.sortedByDescending { it.verified }
        }

        private fun clean(s: String?, max: Int) = s.orEmpty().filter { !it.isISOControl() }.trim().take(max)

        /** Raw units as a readable amount: 61234567 at 6 decimals is "61.23", small amounts keep more digits. */
        fun amount(raw: Long, decimals: Int): String {
            val v = BigDecimal.valueOf(raw).movePointLeft(decimals)
            val digits = when {
                v >= BigDecimal(1000) -> 0
                v >= BigDecimal.ONE -> 2
                v >= BigDecimal("0.01") -> 4
                else -> 6
            }
            val s = v.setScale(minOf(digits, decimals), RoundingMode.DOWN).toPlainString()
            val whole = s.substringBefore('.')
            val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
            return if ('.' in s) grouped + "." + s.substringAfter('.') else grouped
        }
    }
}
