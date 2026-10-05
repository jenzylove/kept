package app.kept.chain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.sol4k.AccountMeta
import org.sol4k.PublicKey
import org.sol4k.instruction.BaseInstruction
import org.sol4k.instruction.Instruction

/**
 * Solana Mobile's SKR staking program (mainnet only). Kept stakes banked SKR in the *vault's* name:
 * the stake's owner is the vault PDA, so only the Kept program (through its cooldown) can ever
 * unstake it. Layouts come from the program's on chain Anchor IDL.
 */
object SkrStaking {
    val PROGRAM = PublicKey("SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ")
    val MINT = PublicKey("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3")
    val CONFIG = PublicKey("4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw")
    val GUARDIAN_POOL = PublicKey("DPJ58trLsF9yPrBa2pk6UaRkvqW8hWUYjawe788WBuqr")
    val STAKE_VAULT = PublicKey("8isViKbwhuhFhsv2t8vaFL74pKCqaFPQXo1KkeQwZbB8")
    val TOKEN_PROGRAM = PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val ASSOCIATED_TOKEN_PROGRAM = PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val SYSTEM = PublicKey("11111111111111111111111111111111")

    /** SKR has 6 decimals. */
    const val ONE_SKR = 1_000_000L

    /** The program refuses stakes under this many base units (1 SKR at the time of writing). */
    const val MIN_STAKE = ONE_SKR

    private val STAKE_DISC = byteArrayOf(206.toByte(), 176.toByte(), 202.toByte(), 18, 200.toByte(), 209.toByte(), 179.toByte(), 108)
    private val WITHDRAW_DISC = byteArrayOf(183.toByte(), 18, 70, 156.toByte(), 148.toByte(), 109, 161.toByte(), 34)

    private fun seed(s: String) = PublicKey(s.toByteArray())
    private fun pda(seeds: List<PublicKey>, program: PublicKey) = PublicKey.findProgramAddress(seeds, program).publicKey

    val EVENT_AUTHORITY: PublicKey by lazy { pda(listOf(seed("__event_authority")), PROGRAM) }

    /** The stake record for `user` (here: the vault PDA) in the single guardian pool. */
    fun userStake(user: PublicKey): PublicKey = pda(listOf(seed("user_stake"), CONFIG, user, GUARDIAN_POOL), PROGRAM)

    /** The associated token account for SKR. */
    fun ata(owner: PublicKey): PublicKey = pda(listOf(owner, TOKEN_PROGRAM, MINT), ASSOCIATED_TOKEN_PROGRAM)

    /** Create `owner`'s SKR token account if it doesn't exist yet. Safe to include every time. */
    fun createAtaIdempotent(payer: PublicKey, owner: PublicKey): Instruction =
        BaseInstruction(
            byteArrayOf(1), // CreateIdempotent
            listOf(
                AccountMeta.signerAndWritable(payer),
                AccountMeta.writable(ata(owner)),
                AccountMeta(owner),
                AccountMeta(MINT),
                AccountMeta(SYSTEM),
                AccountMeta(TOKEN_PROGRAM),
            ),
            ASSOCIATED_TOKEN_PROGRAM,
        )

    /**
     * Stake `amount` SKR from the payer's token account into a stake owned by `vault`.
     * Only the payer signs: the stake's owner doesn't need to, which is what lets a program own it.
     */
    fun stake(payer: PublicKey, vault: PublicKey, amount: Long): Instruction {
        val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(STAKE_DISC).putLong(amount).array()
        return BaseInstruction(
            data,
            listOf(
                AccountMeta.writable(userStake(vault)),
                AccountMeta.writable(CONFIG),
                AccountMeta.writable(GUARDIAN_POOL),
                AccountMeta.signerAndWritable(payer),
                AccountMeta(vault),
                AccountMeta.writable(ata(payer)),
                AccountMeta.writable(STAKE_VAULT),
                AccountMeta(MINT),
                AccountMeta(TOKEN_PROGRAM),
                AccountMeta(SYSTEM),
                AccountMeta(EVENT_AUTHORITY),
                AccountMeta(PROGRAM),
            ),
            PROGRAM,
        )
    }

    /** After SKR's own unstake period, anyone can complete the withdrawal into the vault's token account. */
    fun withdraw(vault: PublicKey): Instruction =
        BaseInstruction(
            WITHDRAW_DISC,
            listOf(
                AccountMeta.writable(userStake(vault)),
                AccountMeta.writable(CONFIG),
                AccountMeta.writable(vault),
                AccountMeta.writable(STAKE_VAULT),
                AccountMeta.writable(ata(vault)),
                AccountMeta(TOKEN_PROGRAM),
                AccountMeta(EVENT_AUTHORITY),
                AccountMeta(PROGRAM),
            ),
            PROGRAM,
        )

    /** Shares the vault owns, read from its UserStake account. Layout: disc(8) bump(1) config(32) user(32) pool(32) shares(u128). */
    fun sharesOf(userStakeAccount: ByteArray): Long {
        val b = ByteBuffer.wrap(userStakeAccount).order(ByteOrder.LITTLE_ENDIAN)
        b.position(8 + 1 + 32 + 32 + 32)
        val lo = b.long
        val hi = b.long
        require(hi == 0L && lo >= 0) { "share count too large" }
        return lo
    }

    /** SKR's global share price, scaled by 1e9, from the StakeConfig account (u128 at offset 137). */
    fun sharePrice(stakeConfig: ByteArray): java.math.BigInteger {
        val b = ByteBuffer.wrap(stakeConfig).order(ByteOrder.LITTLE_ENDIAN)
        b.position(8 + 1 + 32 * 3 + 8 + 8 + 16) // disc, bump, authority/mint/vault, min stake, cooldown, total shares
        val lo = java.math.BigInteger(java.lang.Long.toUnsignedString(b.long))
        val hi = java.math.BigInteger(java.lang.Long.toUnsignedString(b.long))
        return lo.add(hi.shiftLeft(64))
    }

    /** What `shares` are worth in SKR base units at the current share price. */
    fun shareValue(shares: Long, stakeConfig: ByteArray): Long =
        java.math.BigInteger.valueOf(shares).multiply(sharePrice(stakeConfig)).divide(java.math.BigInteger.valueOf(1_000_000_000L)).toLong()

    /** SKR that is in SKR's own unstake period for this stake, and when it started (unix seconds). */
    data class Unstaking(val amountRaw: Long, val startedAt: Long)

    fun unstakingOf(userStakeAccount: ByteArray): Unstaking {
        val b = ByteBuffer.wrap(userStakeAccount).order(ByteOrder.LITTLE_ENDIAN)
        b.position(userStakeAccount.size - 16)
        return Unstaking(b.long, b.long)
    }

    /** SKR's unstake period in seconds, from the StakeConfig account (u64 at offset 8 + 1 + 96 + 8). */
    fun cooldownSecs(stakeConfig: ByteArray): Long =
        ByteBuffer.wrap(stakeConfig).order(ByteOrder.LITTLE_ENDIAN).getLong(8 + 1 + 32 * 3 + 8)
}
