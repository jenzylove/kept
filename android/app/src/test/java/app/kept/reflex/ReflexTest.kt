package app.kept.reflex

import app.kept.chain.Rpc
import app.kept.chain.VaultProgram
import app.kept.engine.Engine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Base58
import org.sol4k.Keypair

class ReflexTest {
    private val program = VaultProgram("EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK")
    private val vaultOwner = Keypair.generate().publicKey

    /** A node that serves a fixed list of real transactions, newest first, and records what we send. */
    private class FakeChain(
        private val txs: List<JsonObject>,
        private val failSend: Boolean = false,
        private val failBlockhash: Boolean = false,
        private var crashAfterAccept: Boolean = false,
    ) : Chain {
        val sent = ArrayList<String>()
        var confirmCalls = 0
        override suspend fun signatures(address: String, limit: Int, before: String?, until: String?): List<Rpc.SigInfo> {
            var list = txs.asReversed().map { Rpc.SigInfo(Engine.signature(it), it["slot"]!!.jsonPrimitive.content.toLong(), false, Engine.blockTime(it)) }
            before?.let { b -> list = list.dropWhile { it.signature != b }.drop(1) }
            until?.let { u -> list = list.takeWhile { it.signature != u } }
            return list.take(limit)
        }
        override suspend fun transactionWhenReady(signature: String) = txs.firstOrNull { Engine.signature(it) == signature }
        override suspend fun latestBlockhash(): String {
            if (failBlockhash) error("RPC unavailable before signing")
            return "11111111111111111111111111111111"
        }
        override suspend fun send(base64Tx: String): String {
            if (failSend) error("preflight failed")
            sent += base64Tx
            if (crashAfterAccept) {
                crashAfterAccept = false
                throw SimulatedProcessDeath()
            }
            return Base58.encode(ByteArray(64) { (sent.size).toByte() })
        }
        override suspend fun confirm(signature: String): Boolean { confirmCalls++; return true }
    }

    private class SimulatedProcessDeath : Error("process died after the RPC accepted the transaction")

    private class MemoryStore : StateStore {
        var state = ReflexState()
        override fun load() = state
        override fun save(state: ReflexState) { this.state = state }
    }

    private fun vectors(owner: String): List<JsonObject> {
        val path = System.getProperty("kept.vectors")!!
        return Json.parseToJsonElement(File(path).readText()).jsonArray
            .map { it.jsonObject }
            .filter { it["owner"]!!.jsonPrimitive.content == owner }
            .map { it["tx"]!!.jsonObject }
            .sortedBy { it["slot"]!!.jsonPrimitive.content.toLong() }
    }

    // A real trader's history with one winning sell (see engine/mirror-expected.json).
    private val trader = "GUQPSxhbssHyiACNRD6aKJhXUAmDGViuhwcyXvRr7Jvw"

    private fun reflex(chain: Chain, store: StateStore, signer: Keypair = Keypair.generate(), bps: Int = 5000, now: Long = 1_900_000_000L) =
        Reflex(chain, signer, program, store, { Rules(vaultOwner, bps) }, watched = trader, clock = { now }) to signer

    @Test
    fun aWinningSellBanksTheShareOfItsProfitAndTiesItToTheSell() = runBlocking {
        val txs = vectors(trader)
        val expected = Engine.mirror(txs, trader)
        val win = expected.wins.single()
        val chain = FakeChain(txs)
        val (r, signer) = reflex(chain, MemoryStore())
        val made = r.drain()

        val confirmed = made.single { it.status == BankEntry.Status.Confirmed }
        assertEquals(win.sig, confirmed.sellSignature)
        assertEquals(win.profit.toLong(), confirmed.profitLamports)
        assertEquals(win.profit.toLong() / 2, confirmed.amountLamports)
        assertEquals(1, chain.sent.size)

        // The transaction we sent is a bank instruction for that amount, signed by the trading key.
        val bytes = Base64.getDecoder().decode(chain.sent.single())
        assertEquals(1, bytes[0].toInt())
        assertEquals(Base58.encode(bytes.copyOfRange(1, 65)), VaultProgram.firstSignature(chain.sent.single()))
        assertTrue(signer.publicKey.verify(bytes.copyOfRange(1, 65), bytes.copyOfRange(65, bytes.size)))
        val b = ByteBuffer.wrap(findBankData(bytes)).order(ByteOrder.LITTLE_ENDIAN)
        b.position(8) // skip the instruction discriminator
        assertEquals(confirmed.amountLamports, b.long)
        assertEquals(confirmed.profitLamports, b.long)
        assertEquals(win.sig, Base58.encode(ByteArray(64).also { b.get(it) }))
    }

    /** Pull the program instruction data out of a one instruction legacy transaction. */
    private fun findBankData(tx: ByteArray): ByteArray {
        var i = 1 + 64 // signatures
        i += 3 // message header
        val nKeys = tx[i].toInt(); i += 1 + 32 * nKeys + 32 // account keys + blockhash
        i += 1 // instruction count (1)
        i += 1 // program id index
        val nAcc = tx[i].toInt(); i += 1 + nAcc
        val dataLen = tx[i].toInt() and 0xFF; i += 1
        return tx.copyOfRange(i, i + dataLen)
    }

    @Test
    fun runningAgainDoesNotBankTheSameSellTwice() = runBlocking {
        val txs = vectors(trader)
        val chain = FakeChain(txs)
        val store = MemoryStore()
        val (r, _) = reflex(chain, store)
        r.drain()
        assertEquals(1, chain.sent.size)
        assertEquals(Engine.signature(txs.last()), store.state.lastSignature)
        assertTrue(r.drain().isEmpty())
        // and a fresh Reflex over the saved state (a restart) does nothing either
        val (again, _) = reflex(chain, store)
        assertTrue(again.drain().isEmpty())
        assertEquals(1, chain.sent.size)
    }

    @Test
    fun sellsBeforeKeptWasTurnedOnUpdateCostBasisButAreNeverBanked() = runBlocking {
        val txs = vectors(trader)
        val chain = FakeChain(txs)
        val store = MemoryStore().apply { state = ReflexState(activatedAt = 2_000_000_000L) } // after every vector
        val (r, _) = reflex(chain, store, now = 2_000_000_100L)
        assertTrue(r.drain().isEmpty())
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun aSellFoundLateIsBankedAndLabelledLate() = runBlocking {
        val txs = vectors(trader)
        val (r, _) = reflex(FakeChain(txs), MemoryStore(), now = 1_900_000_000L) // far after the real sell
        val e = r.drain().single { it.status == BankEntry.Status.Confirmed }
        assertTrue(e.late)
    }

    @Test
    fun aShareUnderTheMinimumIsRecordedNotSent() = runBlocking {
        val txs = vectors(trader)
        val chain = FakeChain(txs)
        // the floor is set above this win's 50% share (about 2.7 SOL)
        val r = Reflex(chain, Keypair.generate(), program, MemoryStore(), { Rules(vaultOwner, 5000) },
            watched = trader, clock = { 1_900_000_000L }, minBank = 10_000_000_000L)
        val e = r.drain().single()
        assertEquals(BankEntry.Status.TooSmall, e.status)
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun aPreSubmissionFailureIsRetriedOnTheNextDrainWithoutLosingTheWin() = runBlocking {
        val txs = vectors(trader)
        val store = MemoryStore()
        val failing = FakeChain(txs, failBlockhash = true)
        val (r, signer) = reflex(failing, store)
        val e = r.drain().single()
        assertEquals(BankEntry.Status.Failed, e.status)
        assertEquals(null, e.bankSignature)
        assertNotNull(e.reason)
        assertEquals(1, store.state.log.size)

        val healthy = FakeChain(txs)
        val retry = Reflex(healthy, signer, program, store, { Rules(vaultOwner, 5000) }, watched = trader, clock = { 1_900_000_000L })
        val again = retry.drain().single()
        assertEquals(BankEntry.Status.Confirmed, again.status)
        assertEquals("the retry replaces the failed entry for the same sell", 1, store.state.log.size)
        assertEquals(1, healthy.sent.size)
    }

    @Test
    fun anAmbiguousSubmissionKeepsItsDerivedSignatureAndIsNeverAutoRetried() = runBlocking {
        val txs = vectors(trader)
        val store = MemoryStore()
        val failing = FakeChain(txs, failSend = true)
        val (r, signer) = reflex(failing, store)
        val failed = r.drain().single()

        assertEquals(BankEntry.Status.Failed, failed.status)
        assertNotNull(failed.bankSignature)
        assertTrue(failed.reason!!.contains("Submission uncertain"))

        val healthy = FakeChain(txs)
        val restarted = Reflex(healthy, signer, program, store, { Rules(vaultOwner, 5000) }, watched = trader)
        assertTrue(restarted.drain().isEmpty())
        assertTrue(healthy.sent.isEmpty())
    }

    @Test
    fun processDeathAfterSubmissionCannotBankTheSameSellAgainOnRestart() = runBlocking {
        val txs = vectors(trader)
        val store = MemoryStore()
        val chain = FakeChain(txs, crashAfterAccept = true)
        val (first, signer) = reflex(chain, store)

        try {
            first.drain()
            throw AssertionError("expected the simulated process death")
        } catch (_: SimulatedProcessDeath) {
            // A real process would be gone here, after sendTransaction but before its response.
        }
        assertEquals(1, chain.sent.size)
        assertEquals(BankEntry.Status.Pending, store.state.log.single().status)

        val restarted = Reflex(chain, signer, program, store, { Rules(vaultOwner, 5000) }, watched = trader)
        val recovered = restarted.drain().single()
        assertEquals(BankEntry.Status.Confirmed, recovered.status)
        assertEquals(store.state.log.single().bankSignature, recovered.bankSignature)
        assertEquals("restart must not submit the same bank transaction again", 1, chain.sent.size)
    }

    @Test
    fun noVaultMeansNothingIsSent() = runBlocking {
        val txs = vectors(trader)
        val chain = FakeChain(txs)
        val r = Reflex(chain, Keypair.generate(), program, MemoryStore(), { null }, watched = trader, clock = { 1_900_000_000L })
        val e = r.drain().single()
        assertEquals(BankEntry.Status.NoVault, e.status)
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun theStateFileFailsClosedInsteadOfResettingFinancialHistory() {
        val dir = java.nio.file.Files.createTempDirectory("reflex-corrupt-").toFile()
        val f = File(dir, "state.json").also { it.writeText("{not valid json") }

        try {
            JsonFileStore(f).load()
            throw AssertionError("corrupt state must not silently become an empty history")
        } catch (e: StateCorruptionException) {
            assertTrue(e.message!!.contains("refusing to reset financial history"))
        }
    }

    @Test
    fun theStateFileRecoversTheLastGoodGenerationWhenThePrimaryIsCorrupt() {
        val dir = java.nio.file.Files.createTempDirectory("reflex-store-").toFile()
        val f = File(dir, "state.json")
        val store = JsonFileStore(f)
        val first = ReflexState(lastSignature = "first", activatedAt = 5)
        val second = ReflexState(lastSignature = "second", activatedAt = 6)

        store.save(first)
        store.save(second)
        f.writeText("{not valid json")

        assertEquals(first, JsonFileStore(f).load())
    }

    @Test
    fun theStateFileRoundTrips() {
        val f = File.createTempFile("reflex", ".json").also { it.delete() }
        val s = JsonFileStore(f)
        val state = ReflexState(
            lastSignature = "abc",
            activatedAt = 5,
            positions = mapOf("M" to PositionDto("100", "3000")),
        ).withLog(BankEntry("s", "M", 10, 5, "b", BankEntry.Status.Confirmed, bankedAt = 9))
        s.save(state)
        assertEquals(state, JsonFileStore(f).load())
        assertEquals(java.math.BigInteger("100"), state.enginePositions()["M"]!!.raw)
    }

    private class FakeSkrBank(private val result: SkrBanked? = null, private val boom: Exception? = null) : SkrBank {
        var calls = 0
        var lastLamports = 0L
        override suspend fun bank(lamports: Long, vaultOwner: org.sol4k.PublicKey): SkrBanked {
            calls++; lastLamports = lamports
            boom?.let { throw it }
            return result!!
        }
    }

    private fun skrReflex(chain: Chain, bank: SkrBank, tier: Boolean = true, store: StateStore = MemoryStore()) = Reflex(
        chain, Keypair.generate(), program, store, { Rules(vaultOwner, 5000, BankAsset.StakedSkr) },
        watched = trader, clock = { 1_900_000_000L }, skrBank = bank, skrTier = { tier },
    )

    @Test
    fun skrModeBanksTheShareIntoStakedSkrAndSendsNoSolBank() = runBlocking {
        val txs = vectors(trader)
        val win = Engine.mirror(txs, trader).wins.single()
        val chain = FakeChain(txs)
        val bank = FakeSkrBank(SkrBanked("swapSig", "stakeSig", stakedRaw = 2_500_000, waitingRaw = 0))
        val e = skrReflex(chain, bank).drain().single()
        assertEquals(BankEntry.Status.Confirmed, e.status)
        assertEquals("SKR", e.asset)
        assertEquals("stakeSig", e.stakeSignature)
        assertEquals(2_500_000L, e.stakedSkrRaw)
        assertEquals(win.profit.toLong() / 2, bank.lastLamports)
        assertTrue("no SOL bank is sent in SKR mode", chain.sent.isEmpty())
    }

    @Test
    fun skrModeRecordsSkrBoughtButUnderTheMinimumStakeAsWaiting() = runBlocking {
        val bank = FakeSkrBank(SkrBanked("swapSig", null, stakedRaw = 0, waitingRaw = 400_000))
        val e = skrReflex(FakeChain(vectors(trader)), bank).drain().single()
        assertEquals(BankEntry.Status.Confirmed, e.status)
        assertEquals(0L, e.stakedSkrRaw)
        assertEquals(400_000L, e.waitingSkrRaw)
    }

    @Test
    fun ifTheOwnerNoLongerHoldsTheTierTheWinIsBankedAsSolInstead() = runBlocking {
        val chain = FakeChain(vectors(trader))
        val bank = FakeSkrBank(SkrBanked("s", "t", 1, 0))
        val e = skrReflex(chain, bank, tier = false).drain().single()
        assertEquals(0, bank.calls)
        assertEquals("SOL", e.asset)
        assertEquals(1, chain.sent.size)
    }

    @Test
    fun aFailedSkrBankIsRecordedAndNeverFallsBackToSol() = runBlocking {
        val chain = FakeChain(vectors(trader))
        val bank = FakeSkrBank(boom = IllegalStateException("No route for SKR"))
        val reflex = skrReflex(chain, bank)
        val e = reflex.drain().single()
        assertEquals(BankEntry.Status.Failed, e.status)
        assertEquals("SKR", e.asset)
        assertTrue(e.reason!!.contains("No route for SKR"))
        assertTrue("the win is not silently banked as SOL", chain.sent.isEmpty())
        assertTrue("multi-step SKR failures are never automatically retried", reflex.drain().isEmpty())
        assertEquals(1, bank.calls)
    }
}
