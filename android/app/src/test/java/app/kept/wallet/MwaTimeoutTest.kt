package app.kept.wallet

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MwaTimeoutTest {
    @Test
    fun walletOperationReturnsBeforeTheDeadline() = runTest {
        assertEquals("signature", withWalletOperationTimeout(1_000) { "signature" })
    }

    @Test
    fun walletOperationStopsAndExplainsThatNoSignatureWasReceived() = runTest {
        val error = runCatching {
            withWalletOperationTimeout(100) {
                delay(101)
                "too late"
            }
        }.exceptionOrNull()

        assertTrue(error is WalletRejected)
        assertTrue(error!!.message!!.contains("no submitted signature", ignoreCase = true))
        assertTrue(error.message!!.contains("Do not retry", ignoreCase = true))
    }
}