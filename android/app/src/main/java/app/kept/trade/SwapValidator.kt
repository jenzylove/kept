package app.kept.trade

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.sol4k.PublicKey

/** Thrown when a transaction Jupiter returned does not look like a plain swap. The message is shown to the user. */
class SwapRejected(message: String) : Exception(message)

/**
 * Checks a transaction returned by Jupiter's API before the trading key signs it. Jupiter is a
 * remote service, so its answer is treated as untrusted input: the transaction is parsed byte by
 * byte here and refused unless it can only do what a swap should do. Pure and offline, so it is
 * unit tested against hand built adversarial transactions.
 *
 * What it does not do: it cannot see inside the Jupiter program, which moves tokens by CPI. The
 * simulation before sending and the balance checks after sending cover that part.
 */
object SwapValidator {
    private const val SYSTEM = "11111111111111111111111111111111"
    private const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    private const val ATA = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
    const val JUPITER_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"

    /** The wrapped SOL mint, which is also the SOL input mint. */
    const val WSOL_MINT = "So11111111111111111111111111111111111111112"

    val ALLOWED_PROGRAMS: Set<String> = setOf(SYSTEM, TOKEN, TOKEN_2022, ATA, COMPUTE_BUDGET, JUPITER_V6)

    /**
     * Lamports a swap may move beyond the input amount: rent for the temporary token accounts a
     * swap opens (a token account holds 0.00204 SOL). Anything above this is not a swap.
     */
    const val RENT_ALLOWANCE_LAMPORTS = 10_000_000L

    private const val MAX_ACCOUNTS = 256
    private const val MAX_INSTRUCTIONS = 64

    class Instruction(val programIndex: Int, val accounts: IntArray, val data: ByteArray)

    class Parsed(
        val signatureCount: Int,
        val versioned: Boolean,
        val requiredSignatures: Int,
        /** Static account keys, base58. A v0 instruction index at or past this size points into a lookup table. */
        val keys: List<String>,
        val instructions: List<Instruction>,
        val lookupTables: Int,
    )

    /** Parse a serialized legacy or v0 transaction. Throws [SwapRejected] if it is malformed or has trailing bytes. */
    fun parse(tx: ByteArray): Parsed {
        try {
            return parseInner(tx)
        } catch (e: SwapRejected) {
            throw e
        } catch (e: RuntimeException) {
            // Out of range reads and bad lengths all mean the same thing: not a transaction we understand.
            throw SwapRejected("Swap refused: the transaction from Jupiter is malformed")
        }
    }

    private class Reader(val b: ByteArray, var pos: Int = 0) {
        fun u8(): Int {
            if (pos >= b.size) throw SwapRejected("Swap refused: the transaction from Jupiter is truncated")
            return b[pos++].toInt() and 0xFF
        }
        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > b.size - pos) throw SwapRejected("Swap refused: the transaction from Jupiter is truncated")
            val out = b.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        fun shortVec(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val byte = u8()
                value = value or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) {
                    if (shift > 0 && byte == 0) throw SwapRejected("Swap refused: the transaction from Jupiter is malformed")
                    return value
                }
                shift += 7
                if (shift > 14) throw SwapRejected("Swap refused: the transaction from Jupiter is malformed")
            }
        }
        val remaining get() = b.size - pos
    }

    private fun parseInner(tx: ByteArray): Parsed {
        val r = Reader(tx)
        val sigCount = r.shortVec()
        if (sigCount < 1 || sigCount > 127) throw SwapRejected("Swap refused: bad signature count")
        r.bytes(sigCount * 64)

        val first = r.u8()
        val versioned = first and 0x80 != 0
        val required: Int
        if (versioned) {
            if (first and 0x7F != 0) throw SwapRejected("Swap refused: unsupported transaction version ${first and 0x7F}")
            required = r.u8()
        } else {
            required = first
        }
        val readonlySigned = r.u8()
        val readonlyUnsigned = r.u8()
        val keyCount = r.shortVec()
        if (keyCount > MAX_ACCOUNTS) throw SwapRejected("Swap refused: too many accounts")
        if (required < 1 || required > keyCount || readonlySigned > required) {
            throw SwapRejected("Swap refused: bad message header")
        }
        if (readonlyUnsigned > keyCount - required) throw SwapRejected("Swap refused: bad message header")
        val keys = List(keyCount) { PublicKey(r.bytes(32)).toBase58() }
        r.bytes(32) // recent blockhash

        val ixCount = r.shortVec()
        if (ixCount > MAX_INSTRUCTIONS) throw SwapRejected("Swap refused: too many instructions")
        val instructions = List(ixCount) {
            val program = r.u8()
            val acctCount = r.shortVec()
            val accounts = IntArray(acctCount) { r.u8() }
            val dataLen = r.shortVec()
            Instruction(program, accounts, r.bytes(dataLen))
        }

        var tables = 0
        var loaded = 0
        if (versioned) {
            tables = r.shortVec()
            repeat(tables) {
                r.bytes(32)
                val writable = r.shortVec()
                r.bytes(writable)
                val readonly = r.shortVec()
                r.bytes(readonly)
                loaded += writable + readonly
            }
        }
        if (r.remaining != 0) throw SwapRejected("Swap refused: unexpected bytes after the transaction message")
        if (keyCount + loaded > 256) throw SwapRejected("Swap refused: too many accounts")
        if (sigCount != required) throw SwapRejected("Swap refused: signature slots do not match the message header")
        return Parsed(sigCount, versioned, required, keys, instructions, tables)
    }

    /**
     * Throws [SwapRejected] unless [tx] is a plain swap for [trader]. [inMint] and [inAmount] are the
     * values the app asked Jupiter to quote, not values Jupiter reported.
     */
    fun validate(tx: ByteArray, trader: PublicKey, inMint: String, inAmount: Long) {
        val p = parse(tx)
        val me = trader.toBase58()

        if (p.requiredSignatures != 1) throw SwapRejected("Swap refused: it asks for ${p.requiredSignatures} signatures, expected one")
        if (p.keys[0] != me) throw SwapRejected("Swap refused: the fee payer is not your trading wallet")

        val wsolAta = ata(trader, WSOL_MINT)
        var jupiterCount = 0
        val out = Outflow()

        for ((n, ix) in p.instructions.withIndex()) {
            // A program id past the static keys comes from a lookup table, which cannot be checked offline.
            if (ix.programIndex >= p.keys.size) throw SwapRejected("Swap refused: instruction ${n + 1} runs a program loaded from a lookup table")
            val program = p.keys[ix.programIndex]
            if (program !in ALLOWED_PROGRAMS) throw SwapRejected("Swap refused: instruction ${n + 1} runs an unknown program $program")

            // Static key for an account slot, or null when it points into a lookup table.
            fun key(slot: Int): String? = ix.accounts.getOrNull(slot)?.let { p.keys.getOrNull(it) }

            when (program) {
                JUPITER_V6 -> jupiterCount++
                COMPUTE_BUDGET -> Unit
                SYSTEM -> checkSystem(n + 1, ix, me, wsolAta, out, ::key)
                TOKEN, TOKEN_2022 -> checkToken(n + 1, ix, me, ::key)
                ATA -> checkAta(n + 1, ix, me, ::key)
            }
        }
        if (jupiterCount != 1) throw SwapRejected("Swap refused: expected one Jupiter instruction, found $jupiterCount")

        // Only SOL leaves the wallet as lamports, and only as much as the quote spends.
        val spend = if (inMint == WSOL_MINT) inAmount else 0L
        if (out.transfers > spend) throw SwapRejected("Swap refused: it moves more SOL into wrapped SOL than the swap spends")
        // A temporary token account holds the spend plus its own rent.
        if (out.created > spend + RENT_ALLOWANCE_LAMPORTS) throw SwapRejected("Swap refused: it funds new accounts with more SOL than the swap spends")
    }

    /** Lamports the trader funds, split so each kind is capped on its own. */
    private class Outflow { var transfers = 0L; var created = 0L }

    /**
     * A plain transfer from the trader may only go to its own wrapped SOL account. Account creation
     * funded by the trader must create a token account. Both are added to [out] for the caps in [validate].
     */
    private fun checkSystem(n: Int, ix: Instruction, me: String, wsolAta: String, out: Outflow, key: (Int) -> String?) {
        if (ix.data.size < 4) throw SwapRejected("Swap refused: instruction $n is a malformed System Program call")
        val buf = ByteBuffer.wrap(ix.data).order(ByteOrder.LITTLE_ENDIAN)
        when (val kind = buf.int) {
            2 -> { // Transfer { lamports }
                if (ix.data.size != 12) throw SwapRejected("Swap refused: instruction $n is a malformed SOL transfer")
                val lamports = buf.long
                if (key(0) != me) return // not the trader's money; the trader signs nothing else
                val to = key(1) ?: throw SwapRejected("Swap refused: instruction $n sends SOL to an account from a lookup table")
                if (to != wsolAta) throw SwapRejected("Swap refused: instruction $n sends SOL to $to, not your own wrapped SOL account")
                if (lamports < 0) throw SwapRejected("Swap refused: instruction $n has a bad SOL amount")
                out.transfers += lamports
            }
            0 -> { // CreateAccount { lamports, space, owner }
                if (ix.data.size != 52) throw SwapRejected("Swap refused: instruction $n is a malformed account creation")
                val lamports = buf.long
                buf.long
                val owner = PublicKey(ByteArray(32).also { buf.get(it) }).toBase58()
                out.created += createdAccount(n, key(0) == me, lamports, owner)
            }
            3 -> { // CreateAccountWithSeed { base, seed, lamports, space, owner }
                val base = ByteArray(32)
                if (buf.remaining() < 32 + 8) throw SwapRejected("Swap refused: instruction $n is a malformed account creation")
                buf.get(base)
                val seedLen = buf.long
                if (seedLen < 0 || seedLen > 32 || buf.remaining().toLong() != seedLen + 8 + 8 + 32) {
                    throw SwapRejected("Swap refused: instruction $n is a malformed account creation")
                }
                buf.position(buf.position() + seedLen.toInt())
                val lamports = buf.long
                buf.long
                val owner = PublicKey(ByteArray(32).also { buf.get(it) }).toBase58()
                out.created += createdAccount(n, key(0) == me, lamports, owner)
            }
            else -> throw SwapRejected("Swap refused: instruction $n is System Program call type $kind, which a swap never needs")
        }
    }

    private fun createdAccount(n: Int, fundedByMe: Boolean, lamports: Long, owner: String): Long {
        if (!fundedByMe) return 0
        if (owner != TOKEN) throw SwapRejected("Swap refused: instruction $n creates an account that is not a token account")
        if (lamports < 0) throw SwapRejected("Swap refused: instruction $n has a bad SOL amount")
        return lamports
    }

    private fun checkToken(n: Int, ix: Instruction, me: String, key: (Int) -> String?) {
        if (ix.data.isEmpty()) throw SwapRejected("Swap refused: instruction $n is a malformed token call")
        val kind = ix.data[0].toInt() and 0xFF
        val touchesMe = ix.accounts.indices.any { key(it) == me }
        when (kind) {
            // Transfer, Approve, SetAuthority, TransferChecked, ApproveChecked, plus Burn and BurnChecked.
            3, 4, 6, 8, 12, 13, 15 -> {
                val name = mapOf(3 to "transfer", 4 to "approval", 6 to "authority change", 8 to "burn", 12 to "transfer", 13 to "approval", 15 to "burn")[kind]
                if (touchesMe) throw SwapRejected("Swap refused: instruction $n is a top level token $name from your wallet")
                throw SwapRejected("Swap refused: instruction $n is a top level token $name, which a swap does not need")
            }
            9 -> { // CloseAccount: only back to the trader
                val dest = key(1) ?: throw SwapRejected("Swap refused: instruction $n closes a token account into a lookup table address")
                if (dest != me) throw SwapRejected("Swap refused: instruction $n closes a token account into $dest, not your wallet")
            }
            1 -> { // InitializeAccount: owner is account 2
                if (key(2) != me) throw SwapRejected("Swap refused: instruction $n sets up a token account owned by someone else")
            }
            16, 18 -> { // InitializeAccount2 and 3: owner is in the data
                if (ix.data.size != 33) throw SwapRejected("Swap refused: instruction $n is a malformed token call")
                val owner = PublicKey(ix.data.copyOfRange(1, 33)).toBase58()
                if (owner != me) throw SwapRejected("Swap refused: instruction $n sets up a token account owned by someone else")
            }
            17, 21, 22 -> Unit // SyncNative, GetAccountDataSize, InitializeImmutableOwner
            else -> throw SwapRejected("Swap refused: instruction $n is token call type $kind, which a swap does not need")
        }
    }

    private fun checkAta(n: Int, ix: Instruction, me: String, key: (Int) -> String?) {
        val kind = if (ix.data.isEmpty()) 0 else ix.data[0].toInt() and 0xFF
        if (kind != 0 && kind != 1) throw SwapRejected("Swap refused: instruction $n is an associated token call type $kind, which a swap does not need")
        // Create and CreateIdempotent: [funder, account, owner, mint, system, token].
        if (key(0) != me) throw SwapRejected("Swap refused: instruction $n opens a token account funded by someone else")
        if (key(2) != me) throw SwapRejected("Swap refused: instruction $n opens a token account for someone else")
    }

    /** The associated token account of [owner] for [mint] under the classic token program. */
    fun ata(owner: PublicKey, mint: String): String =
        PublicKey.findProgramAddress(listOf(owner, PublicKey(TOKEN), PublicKey(mint)), PublicKey(ATA)).publicKey.toBase58()
}
