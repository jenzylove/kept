package app.kept.chain

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

/** Minimal Solana JSON-RPC client. Raw JSON so version 1 transactions decode (web3 libraries can't yet). */
class Rpc(val url: String, private val client: OkHttpClient = defaultClient()) {
    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1

    suspend fun call(method: String, params: JsonArray): JsonElement = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", nextId++)
                put("method", method)
                put("params", params)
            }.toString().toRequestBody(JSON)
            val req = Request.Builder().url(url).post(body).build()
            val text: String
            val status: Int
            try {
                client.newCall(req).execute().use { r ->
                    status = r.code
                    text = r.body?.string().orEmpty()
                }
            } catch (e: java.io.IOException) {
                if (++attempt >= 4) throw e
                delay(400L * attempt)
                continue
            }
            if (status == 429 || status in 500..599) {
                if (++attempt >= 5) throw RpcException(status, "RPC busy ($status)")
                delay(600L * attempt)
                continue
            }
            val o = json.parseToJsonElement(text).jsonObject
            o["error"]?.let { err ->
                val eo = err.jsonObject
                throw RpcException(
                    eo["code"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1,
                    eo["message"]?.jsonPrimitive?.contentOrNull ?: "RPC error",
                    eo["data"],
                )
            }
            return@withContext o["result"] ?: JsonNull
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    data class SigInfo(val signature: String, val slot: Long, val failed: Boolean, val blockTime: Long?)
    data class BlockhashInfo(val blockhash: String, val lastValidBlockHeight: Long)
    enum class SignatureStatus { Confirmed, Failed, Processing, Missing }

    suspend fun signatures(address: String, limit: Int = 100, before: String? = null, until: String? = null): List<SigInfo> {
        val cfg = buildJsonObject {
            put("limit", limit)
            put("commitment", "confirmed")
            before?.let { put("before", it) }
            until?.let { put("until", it) }
        }
        val r = call("getSignaturesForAddress", buildJsonArray { add(JsonPrimitive(address)); add(cfg) }).jsonArray
        return r.map {
            val o = it.jsonObject
            SigInfo(
                o["signature"]!!.jsonPrimitive.content,
                o["slot"]!!.jsonPrimitive.long(),
                o["err"].let { e -> e != null && e !is JsonNull },
                o["blockTime"]?.jsonPrimitive?.longOrNull,
            )
        }
    }

    /** Parsed transaction, or null if the node doesn't have it yet. */
    suspend fun transaction(signature: String): JsonObject? {
        val cfg = buildJsonObject {
            put("encoding", "jsonParsed")
            put("maxSupportedTransactionVersion", 1)
            put("commitment", "confirmed")
        }
        val r = call("getTransaction", buildJsonArray { add(JsonPrimitive(signature)); add(cfg) })
        return r as? JsonObject
    }

    /** Fetch a transaction, waiting briefly for the node to index it after we saw it on the websocket. */
    suspend fun transactionWhenReady(signature: String, tries: Int = 20, waitMs: Long = 300): JsonObject? {
        repeat(tries) {
            transaction(signature)?.let { return it }
            delay(waitMs)
        }
        return null
    }

    /** The cluster's current slot at confirmed commitment. */
    suspend fun slot(): Long {
        val cfg = buildJsonObject { put("commitment", "confirmed") }
        return call("getSlot", buildJsonArray { add(cfg) }).jsonPrimitive.content.toLong()
    }

    suspend fun latestBlockhash(): String = latestBlockhashInfo().blockhash

    suspend fun latestBlockhashInfo(): BlockhashInfo {
        val cfg = buildJsonObject { put("commitment", "confirmed") }
        val r = call("getLatestBlockhash", buildJsonArray { add(cfg) }).jsonObject
        val value = r["value"]!!.jsonObject
        return BlockhashInfo(
            value["blockhash"]!!.jsonPrimitive.content,
            value["lastValidBlockHeight"]!!.jsonPrimitive.long(),
        )
    }

    suspend fun blockHeight(): Long {
        val cfg = buildJsonObject { put("commitment", "confirmed") }
        return call("getBlockHeight", buildJsonArray { add(cfg) }).jsonPrimitive.long()
    }

    suspend fun signatureStatus(signature: String): SignatureStatus {
        val cfg = buildJsonObject { put("searchTransactionHistory", true) }
        val r = call(
            "getSignatureStatuses",
            buildJsonArray { add(buildJsonArray { add(JsonPrimitive(signature)) }); add(cfg) },
        ).jsonObject
        val status = r["value"]!!.jsonArray[0]
        if (status !is JsonObject) return SignatureStatus.Missing
        val err = status["err"]
        if (err != null && err !is JsonNull) return SignatureStatus.Failed
        return when (status["confirmationStatus"]?.jsonPrimitive?.contentOrNull) {
            "confirmed", "finalized" -> SignatureStatus.Confirmed
            else -> SignatureStatus.Processing
        }
    }

    suspend fun balance(address: String): Long {
        val cfg = buildJsonObject { put("commitment", "confirmed") }
        val r = call("getBalance", buildJsonArray { add(JsonPrimitive(address)); add(cfg) }).jsonObject
        return r["value"]!!.jsonPrimitive.long()
    }

    suspend fun minimumBalanceForRentExemption(accountSize: Int): Long =
        call("getMinimumBalanceForRentExemption", buildJsonArray { add(JsonPrimitive(accountSize)) })
            .jsonPrimitive.long()

    /** Raw account data, or null if the account doesn't exist. */
    suspend fun accountData(address: String): ByteArray? {
        val cfg = buildJsonObject { put("encoding", "base64"); put("commitment", "confirmed") }
        val r = call("getAccountInfo", buildJsonArray { add(JsonPrimitive(address)); add(cfg) }).jsonObject
        val v = r["value"] as? JsonObject ?: return null
        return java.util.Base64.getDecoder().decode(v["data"]!!.jsonArray[0].jsonPrimitive.content)
    }

    /** Total token balance (raw units) the owner holds of a mint, across token accounts. */
    suspend fun tokenBalance(owner: String, mint: String): Long {
        val cfg = buildJsonObject { put("encoding", "jsonParsed"); put("commitment", "confirmed") }
        val filter = buildJsonObject { put("mint", mint) }
        val r = call("getTokenAccountsByOwner", buildJsonArray { add(JsonPrimitive(owner)); add(filter); add(cfg) }).jsonObject
        return r["value"]!!.jsonArray.sumOf {
            it.jsonObject["account"]!!.jsonObject["data"]!!.jsonObject["parsed"]!!.jsonObject["info"]!!
                .jsonObject["tokenAmount"]!!.jsonObject["amount"]!!.jsonPrimitive.content.toLong()
        }
    }

    suspend fun send(base64Tx: String): String {
        val cfg = buildJsonObject {
            put("encoding", "base64")
            put("skipPreflight", false)
            put("preflightCommitment", "confirmed")
            put("maxRetries", 3)
        }
        return call("sendTransaction", buildJsonArray { add(JsonPrimitive(base64Tx)); add(cfg) }).jsonPrimitive.content
    }

    /** Fail closed before opening a wallet approval for a transaction the current cluster rejects. */
    suspend fun simulateUnsigned(transaction: ByteArray) =
        simulate(java.util.Base64.getEncoder().encodeToString(transaction), verifySignatures = false)

    /** Verify and simulate a locally signed transaction before broadcasting it. */
    suspend fun simulateSigned(base64Transaction: String) = simulate(base64Transaction, verifySignatures = true)

    private suspend fun simulate(base64Transaction: String, verifySignatures: Boolean) {
        val cfg = buildJsonObject {
            put("encoding", "base64")
            put("sigVerify", verifySignatures)
            put("replaceRecentBlockhash", false)
            put("commitment", "confirmed")
        }
        val value = call("simulateTransaction", buildJsonArray { add(JsonPrimitive(base64Transaction)); add(cfg) })
            .jsonObject["value"]!!.jsonObject
        val err = value["err"]
        if (err != null && err !is JsonNull) {
            throw RpcException(-3, "Transaction simulation failed: $err", value["logs"])
        }
    }

    /** Wait until the signature is confirmed. Returns false on timeout, throws if the transaction failed. */
    suspend fun confirm(signature: String, timeoutMs: Long = 45_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cfg = buildJsonObject { put("searchTransactionHistory", true) }
            val r = call("getSignatureStatuses", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(signature)) }); add(cfg) }).jsonObject
            val s = r["value"]!!.jsonArray[0]
            if (s is JsonObject) {
                val err = s["err"]
                if (err != null && err !is JsonNull) throw RpcException(-2, "Transaction failed: $err")
                val c = s["confirmationStatus"]?.jsonPrimitive?.contentOrNull
                if (c == "confirmed" || c == "finalized") return true
            }
            delay(500)
        }
        return false
    }

    private fun JsonElement.long(): Long = jsonPrimitive.longOrNull ?: jsonPrimitive.content.toLong()
    private fun JsonPrimitive.long(): Long = longOrNull ?: content.toLong()

    companion object {
        private val JSON = "application/json".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}
