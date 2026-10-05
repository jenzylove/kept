package app.kept.mirror

import app.kept.chain.Rpc
import app.kept.engine.Engine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Mirror: replays a wallet's real history through the engine and says what it won, what it gave
 * back, and what a vault would hold if Kept had banked a share of every win.
 */
class MirrorLoader(private val rpc: Rpc) {
    data class Progress(val fetched: Int, val total: Int)

    data class Result(
        val address: String,
        val days: Int,
        val scanned: Int,
        val mirror: Engine.Mirror,
        /** True when the history was longer than we read, so older trades are not counted. */
        val truncated: Boolean,
    )

    suspend fun load(
        address: String,
        days: Int = 90,
        maxTx: Int = 600,
        now: Long = System.currentTimeMillis() / 1000,
        keptAtBps: Int = 5000,
        onProgress: (Progress) -> Unit = {},
    ): Result {
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
        return Result(address, days, sigs.size, Engine.mirror(txs, address, keptAtBps), truncated)
    }

    companion object {
        const val PARALLEL = 8
    }
}
