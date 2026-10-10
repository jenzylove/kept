package app.kept.trade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {
    @Test
    fun amountsReadLikeAmounts() {
        assertEquals("61.23", Tokens.amount(61_234_567, 6))
        assertEquals("1,234,567", Tokens.amount(1_234_567_890_000, 6))
        assertEquals("0.0512", Tokens.amount(51_234, 6))
        assertEquals("0.000012", Tokens.amount(12, 6))
        assertEquals("7", Tokens.amount(7, 0))
    }

    @Test
    fun parsesSearchResultsAndPutsVerifiedFirst() {
        val body = """[
          {"id":"CKfatsPMUf8SkiURsDXs7eK6GWb4Jsd6UDbs7twMCWxo","symbol":"FAKE\nSKR","name":"x","decimals":5,"usdPrice":0.001},
          {"id":"SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3","symbol":"SKR","name":"Seeker","decimals":6,"usdPrice":0.0163,"isVerified":true},
          {"id":"So11111111111111111111111111111111111111112","symbol":"SOL","name":"Solana","decimals":9,"isVerified":true},
          {"id":"short","symbol":"BAD","decimals":6},
          {"symbol":"NOID","decimals":6}
        ]"""
        val list = Tokens.parse(body)
        assertEquals(listOf("SKR", "FAKESKR"), list.map { it.symbol })
        assertTrue(list[0].verified && !list[1].verified)
        assertEquals(6, list[0].decimals)
    }

    @Test
    fun garbageIsAnEmptyList() {
        assertEquals(emptyList<Token>(), Tokens.parse("<html>busy</html>"))
        assertEquals(emptyList<Token>(), Tokens.parse("{\"error\":\"x\"}"))
    }
}
