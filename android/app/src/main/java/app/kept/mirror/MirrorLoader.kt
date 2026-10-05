package app.kept.mirror

import app.kept.chain.Rpc
import app.kept.engine.Engine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Mirror: replays a wallet's real history through the engine and says what it won, what it gave
 * back, and what a vault would hold if Kept had banked a share of every win.
 *
 * With a history endpoint (our proxy, which holds the API key) it reads up to 100 parsed
 * transactions per request, so a busy wallet takes seconds. Without one it falls back to reading
 * each transaction over plain RPC, which is correct but slow.
 */
class MirrorLoader(private val rpc: Rpc, private val historyUrl: String = "") {
    data class Progress(val fetched: Int, val total: Int)

    data class Result(
        val address: String,
        val days: Int,
        val scanned: Int,
        val mirror: Engine.Mirror,
        /** True when the history was longer than we read, so older trades are not counted. */
        val truncated: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()

    suspend fun load(
        address: String,
        days: Int = 90,
        maxTx: Int = 1000,
        now: Long = System.currentTimeMillis() / 1000,
        keptAtBps: Int = 5000,
        onProgress: (Progress) -> Unit = {},
    ): Result = if (historyUrl.isNotBlank()) loadBulk(address, days, maxTx, now, keptAtBps, onProgress)
    else loadRaw(address, days, minOf(maxTx, 600), now, keptAtBps, onProgress)

    private suspend fun loadBulk(address: String, days: Int, maxTx: Int, now: Long, bps: Int, onProgress: (Progress) -> Unit): Result {
        val cutoff = now - days * 86_400L
        val list = ArrayList<JsonObject>()
        var before: String? = null
        var truncated = false
        paging@ for (page in 0 until maxTx / 100) {
            val batch = fetchPage(address, before)
            if (batch.isEmpty()) break
            for (h in batch) {
                val t = (h["timestamp"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
                if (t != null && t < cutoff) break@paging
                list += h
            }
            onProgress(Progress(list.size, list.size))
            if (batch.size < 100) break
            before = batch.last()["signature"]!!.jsonPrimitive.content
            if (page == maxTx / 100 - 1) truncated = true
        }
        return Result(address, days, list.size, Engine.mirrorEnhanced(list, address, bps), truncated)
    }

    private suspend fun fetchPage(address: String, before: String?): List<JsonObject> = withContext(Dispatchers.IO) {
        val sep = if (historyUrl.contains('?')) '&' else '?'
        val url = "$historyUrl${sep}address=$address" + (before?.let { "&before=$it" } ?: "")
        for (attempt in 0 until 6) {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 429) { /* busy: back off and retry */ }
                else if (!r.isSuccessful) throw java.io.IOException("history ${r.code}: ${body.take(120)}")
                else return@withContext json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            }
            delay(1200L * (attempt + 1))
        }
        throw java.io.IOException("The history service is busy. Try again in a moment.")
    }

    private suspend fun loadRaw(address: String, days: Int, maxTx: Int, now: Long, bps: Int, onProgress: (Progress) -> Unit): Result {
        val cutoff = now - days * 86_400L
        val sigs = ArrayList<Rpc.SigInfo>()
        var before: String? = null
        var truncated = false
        paging@ while (true) {
            val page = rpc.signatures(address, 100, before)
            if (page.isEmpty()) break
            for (s in page) {
                if (s.blockTime != null && s.blockTime < cutoff) break@paging
                if (!s.failed) sigs += s
                if (sigs.size >= maxTx) { truncated = true; break@paging }
            }
            if (page.size < 100) break
            before = page.last().signature
        }
        var fetched = 0
        onProgress(Progress(0, sigs.size))
        val txs = ArrayList<JsonObject>(sigs.size)
        for (chunk in sigs.chunked(PARALLEL)) {
            val got = coroutineScope { chunk.map { s -> async { rpc.transaction(s.signature) } }.awaitAll() }
            got.filterNotNull().forEach { txs += it }
            fetched += chunk.size
            onProgress(Progress(fetched, sigs.size))
        }
        txs.sortBy { it["slot"]!!.jsonPrimitive.content.toLong() }
        return Result(address, days, sigs.size, Engine.mirror(txs, address, bps), truncated)
    }

    companion object {
        const val PARALLEL = 4
    }
}
