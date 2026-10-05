package app.kept.trade

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.sol4k.Keypair

/** Swaps for the trading wallet through Jupiter, signed on this phone. */
class Jupiter(private val client: OkHttpClient = OkHttpClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Quote(val raw: JsonObject, val inMint: String, val outMint: String, val inAmount: Long, val outAmount: Long, val priceImpactPct: String?)

    suspend fun quote(inMint: String, outMint: String, amount: Long, slippageBps: Int = 300): Quote = withContext(Dispatchers.IO) {
        val url = "$BASE/quote?inputMint=$inMint&outputMint=$outMint&amount=$amount&slippageBps=$slippageBps"
        val body = get(url)
        val o = json.parseToJsonElement(body).jsonObject
        if (o["error"] != null) error(o["error"]!!.jsonPrimitive.content)
        Quote(
            raw = o,
            inMint = inMint,
            outMint = outMint,
            inAmount = o["inAmount"]!!.jsonPrimitive.content.toLong(),
            outAmount = o["outAmount"]!!.jsonPrimitive.content.toLong(),
            priceImpactPct = o["priceImpactPct"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** A signed swap transaction, base64, ready to send. */
    suspend fun signedSwap(quote: Quote, trader: Keypair): String = withContext(Dispatchers.IO) {
        val req = buildJsonObject {
            put("quoteResponse", quote.raw)
            put("userPublicKey", trader.publicKey.toBase58())
            put("dynamicComputeUnitLimit", true)
            put("prioritizationFeeLamports", "auto")
        }.toString()
        val r = Request.Builder().url("$BASE/swap").post(req.toRequestBody("application/json".toMediaType())).build()
        val text = client.newCall(r).execute().use { it.body?.string().orEmpty() }
        val o = json.parseToJsonElement(text).jsonObject
        val tx = o["swapTransaction"]?.jsonPrimitive?.contentOrNull ?: error(o["error"]?.jsonPrimitive?.contentOrNull ?: "Jupiter returned no transaction")
        signVersioned(tx, trader)
    }

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }

    companion object {
        const val BASE = "https://lite-api.jup.ag/swap/v1"
        const val SOL = "So11111111111111111111111111111111111111112"

        /**
         * Sign a serialized (legacy or versioned) transaction as its first signer. The wire form is
         * a signature count, that many 64 byte slots, then the message; we sign the message bytes
         * as they are, so nothing is re-serialized.
         */
        fun signVersioned(base64: String, signer: Keypair): String {
            val bytes = Base64.getDecoder().decode(base64)
            val (count, lenBytes) = readShortVec(bytes)
            require(count >= 1) { "transaction has no signature slots" }
            val sigStart = lenBytes
            val msgStart = sigStart + count * 64
            val message = bytes.copyOfRange(msgStart, bytes.size)
            val sig = signer.sign(message)
            require(sig.size == 64)
            sig.copyInto(bytes, sigStart)
            return Base64.getEncoder().encodeToString(bytes)
        }

        private fun readShortVec(b: ByteArray): Pair<Int, Int> {
            var value = 0
            var shift = 0
            var i = 0
            while (true) {
                val byte = b[i].toInt() and 0xFF
                value = value or ((byte and 0x7F) shl shift)
                i++
                if (byte and 0x80 == 0) return value to i
                shift += 7
            }
        }
    }
}
