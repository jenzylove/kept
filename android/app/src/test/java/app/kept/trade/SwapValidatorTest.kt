package app.kept.trade

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.sol4k.Keypair
import org.sol4k.PublicKey

/** Hand assembled legacy and v0 transactions, good and hostile, run through [SwapValidator]. */
class SwapValidatorTest {
    private val system = "11111111111111111111111111111111"
    private val token = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private val token2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    private val ataProgram = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private val budget = "ComputeBudget111111111111111111111111111111"
    private val jupiter = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"
    private val wsol = SwapValidator.WSOL_MINT

    private val traderKp = Keypair.generate()
    private val trader = traderKp.publicKey
    private val me = trader.toBase58()
    private val wsolAta = SwapValidator.ata(trader, wsol)
    private val outMint = Keypair.generate().publicKey.toBase58()
    private val outAta = SwapValidator.ata(trader, outMint)
    private fun stranger() = Keypair.generate().publicKey.toBase58()

    private val spend = 1_000_000_000L

    // ---- assembly helpers ----------------------------------------------------------------

    /** An instruction by names. [programIndex] overrides the program's position, to point past the static keys. */
    private class Ix(val program: String, val accounts: List<String>, val data: ByteArray, val programIndex: Int? = null)

    private fun shortVec(n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = n
        while (true) {
            if (v < 0x80) { out.write(v); break }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        return out.toByteArray()
    }

    private fun u32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun u64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    private fun key(b58: String) = PublicKey(b58).bytes()

    /**
     * Serialize a transaction. Static keys are the fee payer, any extra signers, then every key the
     * instructions name. With [lookups] > 0 the message is v0 and carries one table that loads that many accounts.
     */
    private fun build(
        ixs: List<Ix>,
        payer: String = me,
        extraSigners: List<String> = emptyList(),
        lookups: Int = 0,
        sigSlots: Int? = null,
    ): ByteArray {
        val keys = ArrayList<String>()
        keys += payer
        keys += extraSigners
        for (ix in ixs) {
            if (ix.programIndex == null) keys += ix.program
            keys += ix.accounts.filter { !it.startsWith("lookup:") }
        }
        val unique = keys.distinct()
        val required = 1 + extraSigners.size
        val out = ByteArrayOutputStream()
        out.write(shortVec(sigSlots ?: required))
        out.write(ByteArray(64 * (sigSlots ?: required)))
        if (lookups > 0) out.write(0x80)
        out.write(required); out.write(0); out.write(0)
        out.write(shortVec(unique.size))
        unique.forEach { out.write(key(it)) }
        out.write(ByteArray(32) { 7 }) // blockhash
        out.write(shortVec(ixs.size))
        for (ix in ixs) {
            out.write(ix.programIndex ?: unique.indexOf(ix.program))
            out.write(shortVec(ix.accounts.size))
            ix.accounts.forEach { a ->
                // "lookup:N" names the Nth account loaded from the table, which sits after the static keys.
                out.write(if (a.startsWith("lookup:")) unique.size + a.removePrefix("lookup:").toInt() else unique.indexOf(a))
            }
            out.write(shortVec(ix.data.size))
            out.write(ix.data)
        }
        if (lookups > 0) {
            out.write(shortVec(1))
            out.write(key(stranger()))
            out.write(shortVec(lookups))
            repeat(lookups) { out.write(it) }
            out.write(shortVec(0))
        }
        return out.toByteArray()
    }

    private fun sysTransfer(from: String, to: String, lamports: Long) =
        Ix(system, listOf(from, to), u32(2) + u64(lamports))

    private fun sysCreate(from: String, to: String, lamports: Long, owner: String) =
        Ix(system, listOf(from, to), u32(0) + u64(lamports) + u64(165) + key(owner))

    private fun sysCreateWithSeed(from: String, to: String, lamports: Long, owner: String) =
        Ix(system, listOf(from, to, from), u32(3) + key(from) + u64(4) + "seed".toByteArray() + u64(lamports) + u64(165) + key(owner))

    private fun tokenIx(kind: Int, accounts: List<String>, extra: ByteArray = ByteArray(0), program: String = token) =
        Ix(program, accounts, byteArrayOf(kind.toByte()) + extra)

    private fun budgetLimit() = Ix(budget, emptyList(), byteArrayOf(2) + u32(400_000))
    private fun budgetPrice() = Ix(budget, emptyList(), byteArrayOf(3) + u64(1000))
    private fun ataCreate(funder: String = me, owner: String = me, ata: String = outAta, kind: Int = 1) =
        Ix(ataProgram, listOf(funder, ata, owner, outMint, system, token), byteArrayOf(kind.toByte()))
    private fun syncNative() = tokenIx(17, listOf(wsolAta))
    private fun closeWsol(dest: String = me) = tokenIx(9, listOf(wsolAta, dest, me))
    private fun jupiterIx(vararg accounts: String) =
        Ix(jupiter, listOf(me, wsolAta, outAta) + accounts, ByteArray(40) { it.toByte() })

    /** The shape Jupiter returns for a SOL to token swap. */
    private fun goodSwap(vararg extra: Ix): List<Ix> = listOf(
        budgetLimit(), budgetPrice(), ataCreate(),
        sysTransfer(me, wsolAta, spend), syncNative(),
        jupiterIx(),
        closeWsol(),
    ) + extra

    private fun validate(tx: ByteArray, inMint: String = wsol, inAmount: Long = spend) =
        SwapValidator.validate(tx, trader, inMint, inAmount)

    private fun assertRejected(contains: String, tx: ByteArray, inMint: String = wsol, inAmount: Long = spend) {
        try {
            validate(tx, inMint, inAmount)
            fail("expected the swap to be refused: $contains")
        } catch (e: SwapRejected) {
            assertTrue("message was: ${e.message}", e.message!!.contains(contains))
        }
    }

    // ---- the good swap -------------------------------------------------------------------

    @Test
    fun aSwapShapedLikeJupitersIsAcceptedAsLegacy() {
        validate(build(goodSwap()))
    }

    @Test
    fun theSameSwapIsAcceptedAsV0WithAccountsFromALookupTable() {
        // Jupiter routes through many pool accounts that it loads from tables. Data accounts are fine; programs are not.
        val ixs = listOf(budgetLimit(), budgetPrice(), ataCreate(), sysTransfer(me, wsolAta, spend), syncNative(),
            jupiterIx("lookup:0", "lookup:1", "lookup:2"), closeWsol())
        val tx = build(ixs, lookups = 3)
        assertTrue(SwapValidator.parse(tx).versioned)
        validate(tx)
    }

    @Test
    fun aSellSwapWithNoSolTransferIsAccepted() {
        val ixs = listOf(budgetLimit(), ataCreate(ata = wsolAta), jupiterIx(), closeWsol())
        validate(build(ixs), inMint = outMint, inAmount = 5_000)
    }

    @Test
    fun theSplTokenFormOfAWrappedSolAccountIsAccepted() {
        // Some routes open a temporary wrapped SOL account instead of using the associated one.
        val temp = stranger()
        val ixs = listOf(sysCreate(me, temp, spend + 2_039_280, token), tokenIx(18, listOf(temp), key(me)), jupiterIx(), tokenIx(9, listOf(temp, me, me)))
        validate(build(ixs))
        val withSeed = listOf(sysCreateWithSeed(me, temp, spend + 2_039_280, token), tokenIx(18, listOf(temp), key(me)), jupiterIx())
        validate(build(withSeed))
    }

    // ---- fee payer and signers -----------------------------------------------------------

    @Test
    fun refusesWhenTheFeePayerIsNotTheTradingWallet() {
        val other = stranger()
        assertRejected("fee payer", build(goodSwap(), payer = other))
    }

    @Test
    fun refusesWhenTheTransactionNeedsTwoSigners() {
        assertRejected("2 signatures", build(goodSwap(), extraSigners = listOf(stranger())))
    }

    @Test
    fun refusesWhenSignatureSlotsDoNotMatchTheHeader() {
        assertRejected("signature slots", build(goodSwap(), sigSlots = 2))
    }

    // ---- program allowlist ---------------------------------------------------------------

    @Test
    fun refusesAnUnknownProgram() {
        val evil = stranger()
        assertRejected("unknown program $evil", build(goodSwap(Ix(evil, listOf(me), byteArrayOf(1)))))
    }

    @Test
    fun refusesAProgramIdThatComesFromALookupTable() {
        // The program index points past the static keys, so it would be resolved through the table.
        val hidden = Ix(jupiter, listOf(me), byteArrayOf(1))
        assertRejected("lookup table", rebuildWithHiddenProgram(goodSwap(), hidden))
    }

    /** Build with the hidden program index computed after the static keys are known. */
    private fun rebuildWithHiddenProgram(base: List<Ix>, hidden: Ix): ByteArray {
        val probe = Ix(hidden.program, hidden.accounts, hidden.data, programIndex = 0)
        val n = SwapValidator.parse(build(base + probe, lookups = 1)).keys.size
        return build(base + Ix(hidden.program, hidden.accounts, hidden.data, programIndex = n), lookups = 1)
    }

    @Test
    fun theAllowlistIsExactlyTheSixProgramsInTheSpec() {
        assertEquals(setOf(system, token, token2022, ataProgram, budget, jupiter), SwapValidator.ALLOWED_PROGRAMS)
    }

    // ---- SOL leaving the wallet ----------------------------------------------------------

    @Test
    fun refusesASolTransferToAStranger() {
        val ixs = goodSwap(sysTransfer(me, stranger(), 1_000))
        assertRejected("not your own wrapped SOL", build(ixs))
    }

    @Test
    fun refusesASolTransferToAStrangersTokenAccountEvenWhenSmall() {
        val ixs = listOf(budgetLimit(), sysTransfer(me, stranger(), 1), jupiterIx())
        assertRejected("not your own wrapped SOL", build(ixs))
    }

    @Test
    fun refusesWhenTransfersToTheOwnWrappedAccountExceedTheQuote() {
        val ixs = listOf(budgetLimit(), sysTransfer(me, wsolAta, spend), sysTransfer(me, wsolAta, 1), jupiterIx())
        assertRejected("more SOL", build(ixs))
        assertRejected("more SOL", build(listOf(sysTransfer(me, wsolAta, spend + 1), jupiterIx())))
    }

    @Test
    fun acceptsTransfersThatSumToExactlyTheQuote() {
        validate(build(listOf(sysTransfer(me, wsolAta, spend - 5), sysTransfer(me, wsolAta, 5), jupiterIx())))
    }

    @Test
    fun refusesAnySolTransferWhenTheInputIsNotSol() {
        val ixs = listOf(sysTransfer(me, wsolAta, 1), jupiterIx())
        assertRejected("more SOL", build(ixs), inMint = outMint, inAmount = 1_000)
    }

    @Test
    fun refusesCreateAccountThatIsNotATokenAccount() {
        val ixs = listOf(sysCreate(me, stranger(), 1_000_000, system), jupiterIx())
        assertRejected("not a token account", build(ixs))
        val seeded = listOf(sysCreateWithSeed(me, stranger(), 1_000_000, stranger()), jupiterIx())
        assertRejected("not a token account", build(seeded))
    }

    @Test
    fun refusesCreateAccountThatFundsATokenAccountFarBeyondTheSpend() {
        val ixs = listOf(sysCreate(me, stranger(), spend * 5, token), jupiterIx())
        assertRejected("funds new accounts", build(ixs))
    }

    @Test
    fun refusesSystemCallsThatAreNotTransferOrCreate() {
        // Assign (1) would hand the wallet account itself to another program.
        val assign = Ix(system, listOf(me), u32(1) + key(stranger()))
        assertRejected("System Program call type 1", build(listOf(assign, jupiterIx())))
        // TransferWithSeed (11) and AdvanceNonce (4) are not part of a swap either.
        assertRejected("type 11", build(listOf(Ix(system, listOf(me, me, stranger()), u32(11) + u64(1)), jupiterIx())))
        assertRejected("type 4", build(listOf(Ix(system, listOf(wsolAta, me), u32(4)), jupiterIx())))
    }

    // ---- token program -------------------------------------------------------------------

    @Test
    fun refusesATopLevelTokenApprove() {
        val approve = tokenIx(4, listOf(wsolAta, stranger(), me), u64(1_000_000))
        assertRejected("approval from your wallet", build(goodSwap(approve)))
    }

    @Test
    fun refusesATopLevelTokenTransfer() {
        val transfer = tokenIx(3, listOf(outAta, stranger(), me), u64(1_000_000))
        assertRejected("transfer from your wallet", build(goodSwap(transfer)))
    }

    @Test
    fun refusesTransferCheckedAndApproveChecked() {
        val checked = tokenIx(12, listOf(outAta, outMint, stranger(), me), u64(5) + byteArrayOf(6))
        assertRejected("transfer from your wallet", build(goodSwap(checked)))
        val approveChecked = tokenIx(13, listOf(outAta, outMint, stranger(), me), u64(5) + byteArrayOf(6))
        assertRejected("approval from your wallet", build(goodSwap(approveChecked)))
    }

    @Test
    fun refusesSetAuthority() {
        // SetAuthority { type: AccountOwner, new owner Some(stranger) }
        val setAuth = tokenIx(6, listOf(outAta, me), byteArrayOf(2, 1) + key(stranger()))
        assertRejected("authority change from your wallet", build(goodSwap(setAuth)))
    }

    @Test
    fun refusesBurnAndTheSameCallsThroughToken2022() {
        assertRejected("burn", build(goodSwap(tokenIx(8, listOf(outAta, outMint, me), u64(1)))))
        assertRejected("approval", build(goodSwap(tokenIx(4, listOf(outAta, stranger(), me), u64(1), token2022))))
    }

    @Test
    fun refusesCloseAccountToAStranger() {
        val ixs = goodSwap().dropLast(1) + closeWsol(dest = stranger())
        assertRejected("not your wallet", build(ixs))
    }

    @Test
    fun refusesCloseAccountWhenTheDestinationIsInALookupTable() {
        val ixs = goodSwap().dropLast(1) + tokenIx(9, listOf(wsolAta, "lookup:0", me))
        assertRejected("lookup table", build(ixs, lookups = 1))
    }

    @Test
    fun refusesATokenAccountSetUpForSomeoneElse() {
        val temp = stranger()
        assertRejected("owned by someone else", build(listOf(sysCreate(me, temp, 2_039_280, token), tokenIx(18, listOf(temp), key(stranger())), jupiterIx())))
        assertRejected("owned by someone else", build(listOf(sysCreate(me, temp, 2_039_280, token), tokenIx(1, listOf(temp, wsol, stranger())), jupiterIx())))
    }

    @Test
    fun refusesTokenCallsAswapDoesNotNeed() {
        // InitializeMint2 (20) is one of many.
        assertRejected("token call type 20", build(goodSwap(tokenIx(20, listOf(outMint)))))
    }

    @Test
    fun refusesAnAssociatedAccountOpenedForOrFundedByAStranger() {
        assertRejected("for someone else", build(goodSwap(ataCreate(owner = stranger(), ata = stranger()))))
        assertRejected("funded by someone else", build(goodSwap(ataCreate(funder = stranger()))))
        assertRejected("type 2", build(goodSwap(Ix(ataProgram, listOf(me), byteArrayOf(2)))))
    }

    // ---- Jupiter instruction count -------------------------------------------------------

    @Test
    fun refusesZeroJupiterInstructions() {
        val ixs = goodSwap().filter { it.program != jupiter }
        assertRejected("found 0", build(ixs))
    }

    @Test
    fun refusesTwoJupiterInstructions() {
        assertRejected("found 2", build(goodSwap(jupiterIx())))
    }

    // ---- malformed bytes -----------------------------------------------------------------

    @Test
    fun refusesEveryTruncationOfAGoodTransaction() {
        val tx = build(goodSwap())
        for (n in 0 until tx.size) {
            try {
                validate(tx.copyOf(n))
                fail("a transaction cut to $n of ${tx.size} bytes was accepted")
            } catch (_: SwapRejected) {
            }
        }
        val v0 = build(goodSwap(), lookups = 2)
        for (n in 0 until v0.size) {
            try {
                validate(v0.copyOf(n))
                fail("a v0 transaction cut to $n of ${v0.size} bytes was accepted")
            } catch (_: SwapRejected) {
            }
        }
    }

    @Test
    fun refusesTrailingBytes() {
        assertRejected("unexpected bytes", build(goodSwap()) + byteArrayOf(0))
    }

    @Test
    fun refusesAnUnsupportedMessageVersion() {
        val tx = build(goodSwap(), lookups = 1)
        val sigEnd = 1 + 64
        assertEquals(0x80, tx[sigEnd].toInt() and 0xFF)
        tx[sigEnd] = 0x81.toByte() // version 1
        assertRejected("version 1", tx)
    }

    @Test
    fun refusesGarbageAndEmptyInput() {
        assertRejected("", ByteArray(0))
        assertRejected("", ByteArray(300) { 0xFF.toByte() })
    }

    // ---- signing path --------------------------------------------------------------------

    @Test
    fun signValidatedSignsAGoodSwapAndLeavesTheMessageUntouched() {
        val tx = build(goodSwap())
        val signed = Base64.getDecoder().decode(Jupiter.signValidated(Base64.getEncoder().encodeToString(tx), traderKp, wsol, spend))
        assertArrayEquals(tx.copyOfRange(65, tx.size), signed.copyOfRange(65, signed.size))
        assertTrue(trader.verify(signed.copyOfRange(1, 65), tx.copyOfRange(65, tx.size)))
    }

    @Test
    fun signValidatedThrowsBeforeSigningAHostileSwap() {
        val tx = build(goodSwap(sysTransfer(me, stranger(), 1)))
        try {
            Jupiter.signValidated(Base64.getEncoder().encodeToString(tx), traderKp, wsol, spend)
            fail("signed a hostile transaction")
        } catch (e: SwapRejected) {
            assertFalse(e.message.isNullOrBlank())
        }
    }

    @Test
    fun signValidatedRefusesBadBase64() {
        try {
            Jupiter.signValidated("not base64 !!", traderKp, wsol, spend)
            fail("accepted bad base64")
        } catch (_: SwapRejected) {
        }
    }

    // ---- through Jupiter's own HTTP path, with the network faked --------------------------

    private fun jupiterReturning(swapTx: ByteArray, quoteIn: Long = spend): Jupiter {
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val body = if (url.contains("/quote")) {
                """{"inputMint":"$wsol","outputMint":"$outMint","inAmount":"$quoteIn","outAmount":"500"}"""
            } else {
                """{"swapTransaction":"${Base64.getEncoder().encodeToString(swapTx)}"}"""
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Jupiter(client)
    }

    @Test
    fun signedSwapSignsAGoodTransactionFromTheApi() = runBlocking {
        val j = jupiterReturning(build(goodSwap()))
        val q = j.quote(wsol, outMint, spend)
        val signed = Base64.getDecoder().decode(j.signedSwap(q, traderKp))
        assertTrue(trader.verify(signed.copyOfRange(1, 65), signed.copyOfRange(65, signed.size)))
    }

    @Test
    fun signedSwapRefusesAHostileTransactionFromTheApi() = runBlocking {
        val j = jupiterReturning(build(goodSwap(sysTransfer(me, stranger(), 1))))
        val q = j.quote(wsol, outMint, spend)
        try {
            j.signedSwap(q, traderKp)
            fail("signed a hostile transaction")
        } catch (_: SwapRejected) {
        }
    }

    @Test
    fun aQuoteForADifferentAmountThanRequestedIsRefused() = runBlocking {
        val j = jupiterReturning(build(goodSwap()), quoteIn = spend * 10)
        try {
            j.quote(wsol, outMint, spend)
            fail("accepted a quote for another amount")
        } catch (_: IllegalStateException) {
        }
    }
}
