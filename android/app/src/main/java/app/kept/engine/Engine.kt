package app.kept.engine

import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Kept profit engine. Pure functions, no network. Same logic and the same test vectors as the
 * TypeScript engine in /engine. Amounts are exact integers: lamports for SOL, raw base units for
 * tokens.
 */
object Engine {
    const val WSOL = "So11111111111111111111111111111111111111112"

    private val ZERO = BigInteger.ZERO

    data class TokenDelta(val raw: BigInteger, val decimals: Int)
    data class Deltas(val sol: BigInteger, val tokens: Map<String, TokenDelta>)

    sealed interface Classified {
        data object Other : Classified
        data class Buy(val mint: String, val raw: BigInteger, val lamports: BigInteger) : Classified
        data class Sell(val mint: String, val raw: BigInteger, val lamports: BigInteger) : Classified
    }

    data class Position(val raw: BigInteger, val cost: BigInteger)

    /** What one applied trade did. profit/basis are null when the sell had no known buy. */
    sealed interface Event {
        data object None : Event
        data class Bought(val mint: String, val raw: BigInteger, val lamports: BigInteger) : Event
        data class Sold(
            val mint: String,
            val raw: BigInteger,
            val proceeds: BigInteger,
            val basis: BigInteger?,
            val profit: BigInteger?,
        ) : Event
    }

    data class Applied(val positions: Map<String, Position>, val event: Event)

    data class Win(val sig: String, val mint: String, val profit: BigInteger, val time: Long?)

    data class Mirror(
        val swaps: Int,
        val sellCount: Int,
        val wins: List<Win>,
        val losses: List<Win>,
        /** Every priced sell, in the order it happened (wins and losses together). */
        val sells: List<Win>,
        val unpriced: Int,
        val won: BigInteger,
        val givenBack: BigInteger,
        val wouldHaveKept: BigInteger,
    )

    private fun JsonElement?.big(): BigInteger = when (this) {
        null, JsonNull -> ZERO
        is JsonPrimitive -> BigInteger(content)
        else -> ZERO
    }

    private fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)
    private fun JsonObject.arr(key: String): JsonArray? = (this[key] as? JsonArray)

    /**
     * What one transaction did to [owner]'s balances. `sol` excludes the network fee (the fee is a
     * cost of using Solana, not part of a trade's price) and wrapped SOL counts as SOL.
     * [tx] is a `getTransaction` result with `jsonParsed` encoding.
     */
    fun deltas(tx: JsonObject, owner: String): Deltas {
        val meta = tx.obj("meta") ?: return Deltas(ZERO, emptyMap())
        val keys = tx.obj("transaction")?.obj("message")?.arr("accountKeys")
            ?.map { k -> if (k is JsonPrimitive) k.content else k.jsonObject["pubkey"]!!.jsonPrimitive.content }
            ?: emptyList()
        val i = keys.indexOf(owner)
        val pre = meta.arr("preBalances")
        val post = meta.arr("postBalances")
        var sol = if (i >= 0 && pre != null && post != null) post[i].big() - pre[i].big() else ZERO
        if (i == 0) sol += meta["fee"].big()

        val tokens = LinkedHashMap<String, TokenDelta>()
        fun add(b: JsonElement, sign: Int) {
            val o = b.jsonObject
            if (o["owner"]?.jsonPrimitive?.contentOrNull != owner) return
            val mint = o["mint"]!!.jsonPrimitive.content
            val ui = o.obj("uiTokenAmount")!!
            val amount = ui["amount"].big()
            val decimals = (ui["decimals"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
            val prev = tokens[mint]?.raw ?: ZERO
            tokens[mint] = TokenDelta(if (sign > 0) prev + amount else prev - amount, decimals)
        }
        meta.arr("preTokenBalances")?.forEach { add(it, -1) }
        meta.arr("postTokenBalances")?.forEach { add(it, 1) }

        tokens.remove(WSOL)?.let { sol += it.raw }
        tokens.entries.removeAll { it.value.raw == ZERO }
        return Deltas(sol, tokens)
    }

    /**
     * buy: SOL out, exactly one token in. sell: exactly one token out, SOL in.
     * Anything else (transfers, multi-token routes, airdrops) is Other and never banked.
     */
    fun classify(d: Deltas): Classified {
        if (d.tokens.size != 1) return Classified.Other
        val (mint, t) = d.tokens.entries.first()
        return when {
            t.raw.signum() > 0 && d.sol.signum() < 0 -> Classified.Buy(mint, t.raw, d.sol.negate())
            t.raw.signum() < 0 && d.sol.signum() > 0 -> Classified.Sell(mint, t.raw.negate(), d.sol)
            else -> Classified.Other
        }
    }

    /** Average-cost ledger. [positions] is not mutated. */
    fun apply(positions: Map<String, Position>, c: Classified): Applied {
        when (c) {
            is Classified.Buy -> {
                val p = positions[c.mint] ?: Position(ZERO, ZERO)
                val next = positions + (c.mint to Position(p.raw + c.raw, p.cost + c.lamports))
                return Applied(next, Event.Bought(c.mint, c.raw, c.lamports))
            }
            is Classified.Sell -> {
                val p = positions[c.mint]
                if (p == null || p.raw.signum() <= 0) {
                    // Sold something we never saw bought (airdrop, transfer in): no basis, never banked.
                    return Applied(positions, Event.Sold(c.mint, c.raw, c.lamports, null, null))
                }
                val soldRaw = c.raw.min(p.raw)
                val basis = p.cost.multiply(soldRaw).divide(p.raw)
                val next = positions + (c.mint to Position(p.raw - soldRaw, p.cost - basis))
                return Applied(next, Event.Sold(c.mint, c.raw, c.lamports, basis, c.lamports - basis))
            }
            Classified.Other -> return Applied(positions, Event.None)
        }
    }

    /** How much of a profit to bank, in lamports. [bps] is basis points (5000 = 50%). */
    fun bankAmount(profit: BigInteger?, bps: Int): BigInteger {
        if (profit == null || profit.signum() <= 0) return ZERO
        return profit.multiply(BigInteger.valueOf(bps.toLong())).divide(BigInteger.valueOf(10_000))
    }

    /** Signature of a getTransaction result. */
    fun signature(tx: JsonObject): String =
        tx.obj("transaction")!!.arr("signatures")!![0].jsonPrimitive.content

    fun blockTime(tx: JsonObject): Long? = (tx["blockTime"] as? JsonPrimitive)?.longOrNull

    fun failed(tx: JsonObject): Boolean {
        val err = tx.obj("meta")?.get("err")
        return err != null && err !is JsonNull
    }

    /** One parsed trade ready to replay: its signature, time, and what it did to the wallet. */
    data class Item(val sig: String, val time: Long?, val d: Deltas)

    /**
     * What one Helius "enhanced" transaction did to [owner]. Same result as [deltas] on the raw
     * transaction (the tests prove it on real vectors), but one bulk request returns up to 100 of them.
     */
    fun enhancedDeltas(h: JsonObject, owner: String): Deltas {
        var sol = ZERO
        val tokens = LinkedHashMap<String, TokenDelta>()
        for (a in h.arr("accountData") ?: JsonArray(emptyList())) {
            val o = a.jsonObject
            if (o["account"]?.jsonPrimitive?.contentOrNull == owner) sol += o["nativeBalanceChange"].big()
            for (t in o.arr("tokenBalanceChanges") ?: JsonArray(emptyList())) {
                val to = t.jsonObject
                if (to["userAccount"]?.jsonPrimitive?.contentOrNull != owner) continue
                val raw = to.obj("rawTokenAmount")!!
                val amount = raw["tokenAmount"].big()
                val mint = to["mint"]!!.jsonPrimitive.content
                if (mint == WSOL) { sol += amount; continue }
                val decimals = (raw["decimals"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                tokens[mint] = TokenDelta((tokens[mint]?.raw ?: ZERO) + amount, decimals)
            }
        }
        if (h["feePayer"]?.jsonPrimitive?.contentOrNull == owner) sol += h["fee"].big()
        tokens.entries.removeAll { it.value.raw == ZERO }
        return Deltas(sol, tokens)
    }

    /**
     * Replay parsed trades (oldest first) and summarise. [keptAtBps] is what the vault would hold
     * if Kept had banked that share of every win (a plain sum; it ignores how banking would have
     * changed later trade sizes).
     */
    fun replay(items: List<Item>, keptAtBps: Int = 5000): Mirror {
        var positions = emptyMap<String, Position>()
        val wins = ArrayList<Win>()
        val losses = ArrayList<Win>()
        val sells = ArrayList<Win>()
        var unpriced = 0
        var swaps = 0
        for (it in items) {
            val c = classify(it.d)
            if (c is Classified.Other) continue
            swaps++
            val r = apply(positions, c)
            positions = r.positions
            val e = r.event as? Event.Sold ?: continue
            val profit = e.profit
            when {
                profit == null -> unpriced++
                profit.signum() > 0 -> Win(it.sig, e.mint, profit, it.time).also { w -> wins += w; sells += w }
                else -> Win(it.sig, e.mint, profit, it.time).also { w -> losses += w; sells += w }
            }
        }
        val won = wins.fold(ZERO) { s, w -> s + w.profit }
        return Mirror(
            swaps = swaps,
            sellCount = wins.size + losses.size + unpriced,
            wins = wins,
            losses = losses,
            sells = sells,
            unpriced = unpriced,
            won = won,
            givenBack = losses.fold(ZERO) { s, w -> s - w.profit },
            wouldHaveKept = wins.fold(ZERO) { s, w -> s + bankAmount(w.profit, keptAtBps) },
        )
    }

    /** Mirror from raw `getTransaction` results (jsonParsed), oldest first. */
    fun mirror(txs: List<JsonObject>, owner: String, keptAtBps: Int = 5000): Mirror =
        replay(
            txs.filter { it.obj("meta") != null && !failed(it) }
                .map { Item(signature(it), blockTime(it), deltas(it, owner)) },
            keptAtBps,
        )

    /** Mirror from Helius enhanced transactions (any order; failed ones are skipped). */
    fun mirrorEnhanced(list: List<JsonObject>, owner: String, keptAtBps: Int = 5000): Mirror =
        replay(
            list.filter { (it["transactionError"] ?: JsonNull) is JsonNull }
                .sortedBy { (it["slot"] as? JsonPrimitive)?.longOrNull ?: 0L }
                .map { Item(it["signature"]!!.jsonPrimitive.content, (it["timestamp"] as? JsonPrimitive)?.longOrNull, enhancedDeltas(it, owner)) },
            keptAtBps,
        )

    /** Cumulative profit and cumulative kept, in SOL, after each priced sell. Feeds the Staircase. */
    data class Series(val wallet: List<Double>, val kept: List<Double>)

    fun series(m: Mirror, keptAtBps: Int = 5000): Series {
        var pnl = ZERO
        var kept = ZERO
        val w = arrayListOf(0.0)
        val k = arrayListOf(0.0)
        for (s in m.sells) {
            pnl += s.profit
            kept += bankAmount(s.profit, keptAtBps)
            w += pnl.toDouble() / 1e9
            k += kept.toDouble() / 1e9
        }
        return Series(w, k)
    }
}
