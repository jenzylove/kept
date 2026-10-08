package app.kept.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupFlowTest {
    @Test
    fun furthestStepFollowsCompletedPrerequisites() {
        assertEquals(1, furthestSetupStep(mainWallet = null, tradingWallet = null))
        assertEquals(2, furthestSetupStep(mainWallet = "owner", tradingWallet = null))
        assertEquals(3, furthestSetupStep(mainWallet = "owner", tradingWallet = "trader"))
    }

    @Test
    fun requestedStepCanGoBackButCannotSkipPrerequisites() {
        assertEquals(1, boundedSetupStep(requested = 3, mainWallet = null, tradingWallet = null))
        assertEquals(1, boundedSetupStep(requested = 1, mainWallet = "owner", tradingWallet = "trader"))
        assertEquals(2, boundedSetupStep(requested = 2, mainWallet = "owner", tradingWallet = "trader"))
        assertEquals(3, boundedSetupStep(requested = 3, mainWallet = "owner", tradingWallet = "trader"))
    }

    @Test
    fun solInputUsesExactLamports() {
        assertEquals(100_000_000L, solInputToLamports("0.1"))
        assertEquals(1L, solInputToLamports("0.000000001"))
        assertEquals(1_234_567_890L, solInputToLamports("1.23456789"))
    }

    @Test
    fun rejectsUnsafeSolInputs() {
        assertNull(solInputToLamports("0"))
        assertNull(solInputToLamports("-1"))
        assertNull(solInputToLamports("0.0000000001"))
        assertNull(solInputToLamports("9223372037"))
        assertNull(solInputToLamports("not money"))
    }

    @Test
    fun percentageAmountsAreExactAndBounded() {
        assertEquals(250L, percentageOf(1_000L, "25"))
        assertEquals(333L, percentageOf(1_000L, "33.3"))
        assertEquals(1_000L, percentageOf(1_000L, "100"))
        assertNull(percentageOf(1_000L, "0"))
        assertNull(percentageOf(1_000L, "100.1"))
        assertNull(percentageOf(1_000L, "-1"))
    }
}
