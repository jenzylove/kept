package app.kept.engine

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletReadTest {
    private fun sale(p: Long) = Engine.Win("s$p", "mint", BigInteger.valueOf(p), null)
    private fun mirror(wins: List<Long>, losses: List<Long>, unpriced: Int = 0): Engine.Mirror {
        val w = wins.map(::sale); val l = losses.map { sale(-it) }
        return Engine.Mirror(
            swaps = w.size + l.size, sellCount = w.size + l.size + unpriced, wins = w, losses = l, sells = w + l, unpriced = unpriced,
            won = wins.sum().toBigInteger(), givenBack = losses.sum().toBigInteger(), wouldHaveKept = (wins.sum() / 2).toBigInteger(),
        )
    }

    @Test
    fun aWalletThatKeepsMostOfItsWins() {
        val r = WalletRead.of(mirror(wins = listOf(100, 300, 600), losses = listOf(100)))
        assertEquals(WalletRead.Verdict.KeepsMost, r.verdict)
        assertEquals(BigInteger.valueOf(900), r.net)
        assertEquals(75, r.winPercent)
        assertEquals(10, r.gaveBackPercent)
        assertEquals(BigInteger.valueOf(300), r.typicalWin)
        assertFalse(r.partial)
    }

    @Test
    fun aheadButGivingALotBack() {
        val r = WalletRead.of(mirror(wins = listOf(1000), losses = listOf(300, 200)))
        assertEquals(WalletRead.Verdict.AheadButLeaky, r.verdict)
        assertEquals(50, r.gaveBackPercent)
        assertEquals(BigInteger.valueOf(300), r.biggestLoss)
        assertEquals(BigInteger.valueOf(250), r.typicalLoss)
    }

    @Test
    fun winsThenGivesItAllBack() {
        val r = WalletRead.of(mirror(wins = listOf(500), losses = listOf(400, 400)))
        assertEquals(WalletRead.Verdict.GivesItBack, r.verdict)
        assertEquals(BigInteger.valueOf(-300), r.net)
        assertEquals(160, r.gaveBackPercent)
    }

    @Test
    fun noWinsAndNothingAtAll() {
        assertEquals(WalletRead.Verdict.NoWins, WalletRead.of(mirror(wins = emptyList(), losses = listOf(5))).verdict)
        val empty = WalletRead.of(mirror(emptyList(), emptyList()))
        assertEquals(WalletRead.Verdict.Nothing, empty.verdict)
        assertEquals(null, empty.gaveBackPercent)
    }

    @Test
    fun manyUnpricedSalesOrCutHistoryMakeItAPartialPicture() {
        assertTrue(WalletRead.of(mirror(listOf(10), listOf(1), unpriced = 2)).partial)
        assertFalse(WalletRead.of(mirror(listOf(10, 10, 10), listOf(1), unpriced = 1)).partial)
        assertTrue(WalletRead.of(mirror(listOf(10), listOf(1)), truncated = true).partial)
    }
}
