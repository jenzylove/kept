package app.kept.trade

import java.util.Base64
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.sol4k.PublicKey

/**
 * Real transactions returned by Jupiter's swap endpoint on 2026-10-10, saved in test resources as
 * "<input mint> <input amount> <base64 transaction>". The validator must accept every one of them
 * for the wallet they were built for, and refuse them for any other wallet or a smaller amount.
 */
class SwapValidatorRealTest {
    private val trader = PublicKey("EzB5Svn5CmdafRdT3m7ugDe9ck2gKoaMkEcjp4gV2dFy")
    private val names = listOf("sol_skr", "skr_sol", "sol_usdc", "usdc_sol", "sol_jup")

    private class Real(val inMint: String, val inAmount: Long, val tx: ByteArray)

    private fun load(name: String): Real {
        val parts = javaClass.getResource("/jupiter/$name.b64")!!.readText().trim().split(" ")
        return Real(parts[0], parts[1].toLong(), Base64.getDecoder().decode(parts[2]))
    }

    @Test
    fun acceptsRealJupiterSwaps() {
        for (name in names) {
            val r = load(name)
            try {
                SwapValidator.validate(r.tx, trader, r.inMint, r.inAmount)
            } catch (e: SwapRejected) {
                fail("$name: ${e.message}")
            }
        }
    }

    @Test
    fun refusesARealSwapBuiltForAnotherWallet() {
        val other = PublicKey("J8zCXXr2rEwzuFPikyCGo1fkBpJUpTrTnSiNJPmtjyZk")
        for (name in names) {
            val r = load(name)
            val refused = runCatching { SwapValidator.validate(r.tx, other, r.inMint, r.inAmount) }.exceptionOrNull() is SwapRejected
            assertTrue(name, refused)
        }
    }

    @Test
    fun refusesARealSolSwapThatSpendsMoreThanAsked() {
        for (name in listOf("sol_skr", "sol_usdc", "sol_jup")) {
            val r = load(name)
            val refused = runCatching { SwapValidator.validate(r.tx, trader, r.inMint, r.inAmount - 1) }.exceptionOrNull() is SwapRejected
            assertTrue(name, refused)
        }
    }
}
