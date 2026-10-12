package app.kept.reflex

import app.kept.chain.RpcException
import app.kept.chain.SkrStaking
import app.kept.chain.VaultProgram
import app.kept.trade.Jupiter
import app.kept.trade.SwapRejected
import app.kept.trade.SwapSource
import app.kept.trade.SwapValidator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.sol4k.Keypair

const val FAKE_SWAP_TX = "c3dhcA=="

/** A swap source that quotes a fixed SKR output and "signs" a marker transaction. */
internal class FakeSwap(val outAmount: Long, val reject: SwapRejected? = null) : SwapSource {
    override suspend fun quote(inMint: String, outMint: String, amount: Long, slippageBps: Int, maxAccounts: Int, direct: Boolean) =
        Jupiter.Quote(JsonObject(emptyMap()), inMint, outMint, amount, outAmount, null)

    override suspend fun signedSwap(quote: Jupiter.Quote, trader: Keypair): String {
        reject?.let { throw it }
        return FAKE_SWAP_TX
    }
}

/** A chain where the wallet holds [balance] SKR. Sending the swap credits [swapYields]; a stake debits it. */
internal class FakeSkrChain(var balance: Long, var swapYields: Long) : SkrChain {
    val sent = ArrayList<String>()
    val simulated = ArrayList<String>()
    val stakes = ArrayList<Long>()
    var simulationError: RpcException? = null
    var stakeSendError: Exception? = null

    override suspend fun tokenBalance(owner: String, mint: String) = balance
    override suspend fun latestBlockhash() = "11111111111111111111111111111111"
    override suspend fun simulateSigned(base64Tx: String) {
        simulated += base64Tx
        simulationError?.let { throw it }
    }
    override suspend fun send(base64Tx: String): String {
        if (base64Tx == FAKE_SWAP_TX) {
            balance += swapYields
        } else {
            stakeSendError?.let { throw it }
            val amount = stakeAmount(base64Tx)
            stakes += amount
            balance -= amount
        }
        sent += base64Tx
        return "sig${sent.size}"
    }
    override suspend fun confirm(signature: String) = true

    /** The amount in the Kept stake instruction of a signed transaction. */
    private fun stakeAmount(base64Tx: String): Long {
        val parsed = SwapValidator.parse(Base64.getDecoder().decode(base64Tx))
        val ix = parsed.instructions.single { parsed.keys[it.programIndex] == SkrStaking.PROGRAM.toBase58() }
        return ByteBuffer.wrap(ix.data, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }
}

class SkrBankTest {
    private val program = VaultProgram("EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK")
    private val vaultOwner = Keypair.generate().publicKey
    private val signer = Keypair.generate()

    private fun bank(swap: FakeSwap, chain: FakeSkrChain) = JupiterSkrBank(swap, chain, signer, program)

    private class Saves { val values = ArrayList<Long>(); val save: suspend (Long) -> Unit = { values += it } }

    @Test
    fun skrTheUserPutInTheWalletThemselvesIsNeverStaked() = runBlocking {
        val chain = FakeSkrChain(balance = 50_000_000, swapYields = 2_000_000) // 50 SKR already there
        val out = bank(FakeSwap(2_000_000), chain).bank(10_000_000, vaultOwner, 0) {}

        assertEquals(listOf(2_000_000L), chain.stakes)
        assertEquals(2_000_000L, out.stakedRaw)
        assertEquals(0L, out.waitingRaw)
        assertEquals("the user's 50 SKR stays put", 50_000_000L, chain.balance)
        assertTrue("both the swap and the stake were simulated first", chain.simulated.size == 2 && chain.simulated[0] == FAKE_SWAP_TX)
    }

    @Test
    fun aBuyUnderTheMinimumIsRecordedAsWaitingAndStakedWithTheNextBuy() = runBlocking {
        val chain = FakeSkrChain(balance = 5_000_000, swapYields = 400_000) // the user holds 5 SKR of their own
        val saves = Saves()
        val first = bank(FakeSwap(400_000), chain).bank(1_000_000, vaultOwner, 0, saves.save)
        assertEquals(0L, first.stakedRaw)
        assertEquals(400_000L, first.waitingRaw)
        assertTrue("nothing staked yet", chain.stakes.isEmpty())
        assertEquals(listOf(400_000L), saves.values)

        // The next win buys 0.7 SKR. 0.4 waiting + 0.7 new = 1.1 staked. The user's 5 SKR is still not touched.
        chain.swapYields = 700_000
        val second = bank(FakeSwap(700_000), chain).bank(1_000_000, vaultOwner, first.waitingRaw, saves.save)
        assertEquals(listOf(1_100_000L), chain.stakes)
        assertEquals(1_100_000L, second.stakedRaw)
        assertEquals(0L, second.waitingRaw)
        assertEquals(5_000_000L, chain.balance)
        assertEquals("waiting is recorded, then cleared before the stake is sent", listOf(400_000L, 1_100_000L, 0L), saves.values)
    }

    @Test
    fun aZeroDeltaFailsTheBankAndStakesNothing() = runBlocking {
        val chain = FakeSkrChain(balance = 30_000_000, swapYields = 0)
        val saves = Saves()
        try {
            bank(FakeSwap(2_000_000), chain).bank(10_000_000, vaultOwner, 0, saves.save)
            fail("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("did not rise"))
        }
        assertTrue(chain.stakes.isEmpty())
        assertTrue(saves.values.isEmpty())
    }

    @Test
    fun aNegativeDeltaFailsTheBankToo() = runBlocking {
        val chain = FakeSkrChain(balance = 30_000_000, swapYields = -1_000_000) // the user moved SKR out during the swap
        try {
            bank(FakeSwap(2_000_000), chain).bank(10_000_000, vaultOwner, 0) {}
            fail("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("did not rise"))
        }
        assertTrue(chain.stakes.isEmpty())
    }

    @Test
    fun aBalanceRiseFarBeyondTheQuoteIsNotStaked() = runBlocking {
        // Someone sent the wallet 100 SKR while the swap was in flight.
        val chain = FakeSkrChain(balance = 0, swapYields = 100_000_000)
        try {
            bank(FakeSwap(2_000_000), chain).bank(10_000_000, vaultOwner, 0) {}
            fail("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("more than the swap could buy"))
        }
        assertTrue(chain.stakes.isEmpty())
    }

    @Test
    fun neverStakesMoreThanTheWalletHolds() = runBlocking {
        // 2 SKR was recorded as waiting but the user has since moved it out; only the new 0.4 is there.
        val chain = FakeSkrChain(balance = 0, swapYields = 400_000)
        val out = bank(FakeSwap(400_000), chain).bank(1_000_000, vaultOwner, 2_000_000) {}
        assertTrue(chain.stakes.isEmpty())
        assertEquals(400_000L, out.waitingRaw)
    }

    @Test
    fun aSwapThatTheValidatorRefusesIsNeverSentAndNothingIsRecorded() = runBlocking {
        val chain = FakeSkrChain(balance = 0, swapYields = 2_000_000)
        val saves = Saves()
        try {
            bank(FakeSwap(2_000_000, reject = SwapRejected("Swap refused: test")), chain).bank(10_000_000, vaultOwner, 0, saves.save)
            fail("expected a refusal")
        } catch (e: SwapRejected) {
            assertTrue(e.message!!.startsWith("Swap refused"))
        }
        assertTrue(chain.sent.isEmpty())
        assertTrue(saves.values.isEmpty())
    }

    @Test
    fun aSwapThatFailsSimulationIsNeverSent() = runBlocking {
        val chain = FakeSkrChain(balance = 0, swapYields = 2_000_000).also { it.simulationError = RpcException(-3, "Transaction simulation failed") }
        try {
            bank(FakeSwap(2_000_000), chain).bank(10_000_000, vaultOwner, 0) {}
            fail("expected a failure")
        } catch (_: RpcException) {
        }
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun aStakeTheNodeRefusesPutsTheAmountBackOnTheWaitingList() = runBlocking {
        val chain = FakeSkrChain(balance = 0, swapYields = 2_000_000).also { it.stakeSendError = RpcException(-32002, "preflight failed") }
        val saves = Saves()
        try {
            bank(FakeSwap(2_000_000), chain).bank(10_000_000, vaultOwner, 0, saves.save)
            fail("expected a failure")
        } catch (_: RpcException) {
        }
        assertEquals("recorded, cleared for sending, restored when refused", listOf(2_000_000L, 0L, 2_000_000L), saves.values)
    }

    @Test
    fun anUncertainStakeStaysClearedSoItCanNeverBeStakedTwice() = runBlocking {
        val chain = FakeSkrChain(balance = 0, swapYields = 2_000_000).also { it.stakeSendError = java.io.IOException("timeout") }
        val saves = Saves()
        try {
            bank(FakeSwap(2_000_000), chain).bank(10_000_000, vaultOwner, 0, saves.save)
            fail("expected a failure")
        } catch (_: java.io.IOException) {
        }
        assertEquals(0L, saves.values.last())
    }

    @Test
    fun theWaitingAmountSurvivesARestartAndOldFilesWithoutItLoadAsZero() {
        val f = File.createTempFile("skr-waiting", ".json").also { it.delete() }
        JsonFileStore(f).save(ReflexState(lastSignature = "x", waitingSkrRaw = 650_000))
        assertEquals(650_000L, JsonFileStore(f).load().waitingSkrRaw)

        val old = File.createTempFile("skr-old", ".json")
        old.writeText("""{"lastSignature":"x","activatedAt":5,"positions":{},"log":[]}""")
        assertEquals(0L, JsonFileStore(old).load().waitingSkrRaw)
    }
}
