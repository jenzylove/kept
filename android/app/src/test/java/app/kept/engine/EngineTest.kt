package app.kept.engine

import java.io.File
import java.math.BigInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private class Vector(val owner: String, val sig: String, val tx: JsonObject, val expectedSol: String, val expectedTokens: Map<String, String>)

    private val vectors: List<Vector> by lazy {
        val path = System.getProperty("kept.vectors") ?: error("kept.vectors not set")
        Json.parseToJsonElement(File(path).readText()).jsonArray.map {
            val o = it.jsonObject
            val exp = o["expected"]!!.jsonObject
            Vector(
                owner = o["owner"]!!.jsonPrimitive.content,
                sig = o["sig"]!!.jsonPrimitive.content,
                tx = o["tx"]!!.jsonObject,
                expectedSol = exp["sol"]!!.jsonPrimitive.content,
                expectedTokens = exp["tokens"]!!.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content },
            )
        }
    }

    private fun big(n: Long) = BigInteger.valueOf(n)

    @Test
    fun deltasMatchTheIndependentParserOnEveryRealVector() {
        assertTrue("need at least 10 vectors, have ${vectors.size}", vectors.size >= 10)
        for (v in vectors) {
            val d = Engine.deltas(v.tx, v.owner)
            assertEquals("sol for ${v.sig}", v.expectedSol, d.sol.toString())
            assertEquals("tokens for ${v.sig}", v.expectedTokens, d.tokens.mapValues { it.value.raw.toString() })
        }
    }

    @Test
    fun theSpikeSellIsASellAndTheSpikeBankIsNot() {
        vectors.firstOrNull { it.sig.startsWith("29L62xCL") }?.let {
            assertTrue(Engine.classify(Engine.deltas(it.tx, it.owner)) is Engine.Classified.Sell)
        }
        vectors.firstOrNull { it.sig.startsWith("4T4ELre6") }?.let {
            assertEquals(Engine.Classified.Other, Engine.classify(Engine.deltas(it.tx, it.owner)))
        }
    }

    @Test
    fun averageCostPartialSellTakesAProportionalBasis() {
        var pos = emptyMap<String, Engine.Position>()
        pos = Engine.apply(pos, Engine.Classified.Buy("M", big(100), big(1000))).positions
        pos = Engine.apply(pos, Engine.Classified.Buy("M", big(100), big(3000))).positions // avg 20/unit
        val r = Engine.apply(pos, Engine.Classified.Sell("M", big(50), big(1500)))
        val e = r.event as Engine.Event.Sold
        assertEquals(big(1000), e.basis)
        assertEquals(big(500), e.profit)
        assertEquals(Engine.Position(big(150), big(3000)), r.positions["M"])
    }

    @Test
    fun sellingMoreThanWasBoughtCapsTheBasisAtTheKnownPosition() {
        val pos = Engine.apply(emptyMap(), Engine.Classified.Buy("M", big(10), big(100))).positions
        val e = Engine.apply(pos, Engine.Classified.Sell("M", big(20), big(400))).event as Engine.Event.Sold
        assertEquals(big(100), e.basis)
        assertEquals(big(300), e.profit)
    }

    @Test
    fun aSellWithNoKnownBuyIsUnpricedAndNeverBanked() {
        val e = Engine.apply(emptyMap(), Engine.Classified.Sell("M", big(5), big(50))).event as Engine.Event.Sold
        assertNull(e.profit)
        assertEquals(BigInteger.ZERO, Engine.bankAmount(e.profit, 5000))
    }

    @Test
    fun lossesAndZeroProfitBankNothingWinsBankTheShare() {
        assertEquals(BigInteger.ZERO, Engine.bankAmount(big(-10), 5000))
        assertEquals(BigInteger.ZERO, Engine.bankAmount(BigInteger.ZERO, 5000))
        assertEquals(big(500), Engine.bankAmount(big(1001), 5000))
    }

    @Test
    fun mirrorOverRealHistoryAddsUp() {
        val owner = "GUQPSxhbssHyiACNRD6aKJhXUAmDGViuhwcyXvRr7Jvw"
        val txs = vectors.filter { it.owner == owner }.map { it.tx }
            .sortedBy { it["slot"]!!.jsonPrimitive.content.toLong() }
        val m = Engine.mirror(txs, owner)
        assertEquals(m.wins.fold(BigInteger.ZERO) { s, w -> s + w.profit }, m.won)
        assertTrue(m.wouldHaveKept <= m.won)
    }

    @Test
    fun theKotlinMirrorMatchesTheTypeScriptMirrorOnTheSameVectors() {
        // Expected values were produced by `node engine/scripts/print-mirror.mjs` over vectors.json.
        val f = File(System.getProperty("kept.vectors")!!).parentFile.resolve("mirror-expected.json")
        if (!f.exists()) return
        val exp = Json.parseToJsonElement(f.readText()).jsonObject
        for ((owner, e) in exp) {
            val o = e.jsonObject
            val txs = vectors.filter { it.owner == owner }.map { it.tx }
                .sortedBy { it["slot"]!!.jsonPrimitive.content.toLong() }
            val m = Engine.mirror(txs, owner)
            assertEquals("won for $owner", o["won"]!!.jsonPrimitive.content, m.won.toString())
            assertEquals("givenBack for $owner", o["givenBack"]!!.jsonPrimitive.content, m.givenBack.toString())
            assertEquals("wins for $owner", o["wins"]!!.jsonPrimitive.content.toInt(), m.wins.size)
            assertEquals("kept for $owner", o["wouldHaveKept"]!!.jsonPrimitive.content, m.wouldHaveKept.toString())
        }
    }
}
