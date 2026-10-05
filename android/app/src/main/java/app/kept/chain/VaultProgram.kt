package app.kept.chain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.sol4k.AccountMeta
import org.sol4k.Base58
import org.sol4k.Keypair
import org.sol4k.PublicKey
import org.sol4k.Transaction
import org.sol4k.instruction.BaseInstruction
import org.sol4k.instruction.Instruction
import org.sol4k.instruction.TransferInstruction

/** The Kept vault program: instruction builders and account decoding. Mirrors programs/kept_vault. */
class VaultProgram(programId: String) {
    val programId = PublicKey(programId)

    fun vaultAddress(owner: PublicKey): PublicKey =
        PublicKey.findProgramAddress(listOf(PublicKey(VAULT_SEED), owner), programId).publicKey

    private fun disc(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("global:$name".toByteArray()).copyOf(8)

    private fun data(name: String, size: Int, fill: ByteBuffer.() -> Unit): ByteArray {
        val b = ByteBuffer.allocate(8 + size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(disc(name))
        b.fill()
        return b.array()
    }

    private fun ix(data: ByteArray, vararg keys: AccountMeta): Instruction = BaseInstruction(data, keys.toList(), programId)

    /** Owner opens a vault. `banker` is the trading wallet, the only key that can put money in. */
    fun initVault(owner: PublicKey, banker: PublicKey, cooldownSecs: Long, bankBps: Int): Instruction =
        ix(
            data("init_vault", 32 + 8 + 2) { put(banker.bytes()); putLong(cooldownSecs); putShort(bankBps.toShort()) },
            AccountMeta.signerAndWritable(owner),
            AccountMeta.writable(vaultAddress(owner)),
            AccountMeta(SYSTEM_PROGRAM),
        )

    /** The trading wallet banks `amount` lamports from a sell. `sellSignature` ties the bank to the sell. */
    fun bank(banker: PublicKey, owner: PublicKey, amount: Long, profit: Long, sellSignature: String): Instruction {
        val sig = Base58.decode(sellSignature)
        require(sig.size == 64) { "sell signature must be 64 bytes" }
        return ix(
            data("bank", 8 + 8 + 64) { putLong(amount); putLong(profit); put(sig) },
            AccountMeta.signerAndWritable(banker),
            AccountMeta.writable(vaultAddress(owner)),
            AccountMeta(SYSTEM_PROGRAM),
        )
    }

    fun requestWithdraw(owner: PublicKey, asset: Asset, amount: Long, to: PublicKey): Instruction =
        ix(
            data("request_withdraw", 1 + 8 + 32) { put(asset.ordinal.toByte()); putLong(amount); put(to.bytes()) },
            AccountMeta.signer(owner),
            AccountMeta.writable(vaultAddress(owner)),
        )

    fun cancelWithdraw(owner: PublicKey): Instruction =
        ix(data("cancel_withdraw", 0) {}, AccountMeta.signer(owner), AccountMeta.writable(vaultAddress(owner)))

    fun executeWithdrawSol(owner: PublicKey, to: PublicKey): Instruction =
        ix(data("execute_withdraw_sol", 0) {}, AccountMeta.writable(vaultAddress(owner)), AccountMeta.writable(to))

    fun setRules(owner: PublicKey, cooldownSecs: Long, bankBps: Int, banker: PublicKey): Instruction =
        ix(
            data("set_rules", 8 + 2 + 32) { putLong(cooldownSecs); putShort(bankBps.toShort()); put(banker.bytes()) },
            AccountMeta.signer(owner),
            AccountMeta.writable(vaultAddress(owner)),
        )

    /**
     * After the vault's cooldown, ask the SKR program to start unstaking the vault's shares. Anyone
     * can send this; the program checks the cooldown and signs for the vault itself.
     */
    fun executeUnstakeSkr(owner: PublicKey): Instruction {
        val vault = vaultAddress(owner)
        return ix(
            data("execute_unstake_skr", 0) {},
            AccountMeta.writable(vault),
            AccountMeta.writable(SkrStaking.userStake(vault)),
            AccountMeta.writable(SkrStaking.CONFIG),
            AccountMeta.writable(SkrStaking.GUARDIAN_POOL),
            AccountMeta(SkrStaking.STAKE_VAULT),
            AccountMeta(SkrStaking.MINT),
            AccountMeta(SkrStaking.EVENT_AUTHORITY),
            AccountMeta(SkrStaking.PROGRAM),
        )
    }

    /** Move SKR sitting in the vault's token account to the owner's. Only the owner can do this. */
    fun releaseSkr(owner: PublicKey, amount: Long): Instruction {
        val vault = vaultAddress(owner)
        return ix(
            data("release_skr", 8) { putLong(amount) },
            AccountMeta.signer(owner),
            AccountMeta.writable(vault),
            AccountMeta.writable(SkrStaking.ata(vault)),
            AccountMeta.writable(SkrStaking.ata(owner)),
            AccountMeta(SkrStaking.TOKEN_PROGRAM),
        )
    }

    enum class Asset { Sol, Skr, StakedSkr }

    /** Decoded `Vault` account. Layout: 8 byte discriminator, then the fields in declaration order. */
    data class VaultState(
        val owner: PublicKey,
        val banker: PublicKey,
        val cooldownSecs: Long,
        val pendingCooldownSecs: Long,
        val pendingCooldownAt: Long,
        val bankBps: Int,
        val totalBanked: Long,
        val totalWithdrawn: Long,
        val bankCount: Long,
        val withdrawAsset: Asset,
        val withdrawAmount: Long,
        val withdrawTo: PublicKey,
        val withdrawUnlockAt: Long,
        val skrUnstakeApproved: Boolean,
    ) {
        val hasPendingWithdraw get() = withdrawAmount > 0
    }

    fun decodeVault(account: ByteArray): VaultState {
        val b = ByteBuffer.wrap(account).order(ByteOrder.LITTLE_ENDIAN)
        require(account.size >= 8 && account.copyOfRange(0, 8).contentEquals(accountDisc("Vault"))) { "not a Kept vault" }
        b.position(8)
        fun key(): PublicKey = PublicKey(ByteArray(32).also { b.get(it) })
        val owner = key()
        val banker = key()
        b.get() // bump
        val cooldown = b.long
        val pendingCooldown = b.long
        val pendingAt = b.long
        val bps = b.short.toInt() and 0xFFFF
        val banked = b.long
        val withdrawn = b.long
        val count = b.long
        val asset = Asset.entries[b.get().toInt()]
        val wAmount = b.long
        val wTo = key()
        val unlock = b.long
        val approved = b.get().toInt() != 0
        return VaultState(owner, banker, cooldown, pendingCooldown, pendingAt, bps, banked, withdrawn, count, asset, wAmount, wTo, unlock, approved)
    }

    private fun accountDisc(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("account:$name".toByteArray()).copyOf(8)

    companion object {
        val SYSTEM_PROGRAM = PublicKey("11111111111111111111111111111111")
        private val VAULT_SEED = "vault".toByteArray()

        fun transfer(from: PublicKey, to: PublicKey, lamports: Long): Instruction = TransferInstruction(from, to, lamports)

        /** Build a legacy transaction, sign with each given key, and return its base64 wire form. */
        fun signedBase64(blockhash: String, payer: PublicKey, instructions: List<Instruction>, vararg signers: Keypair): String {
            val tx = Transaction(blockhash, instructions, payer)
            signers.forEach { tx.sign(it) }
            return java.util.Base64.getEncoder().encodeToString(tx.serialize())
        }

        /**
         * Unsigned legacy transaction bytes, for a wallet app to sign over Mobile Wallet Adapter.
         * A wallet needs one zeroed 64 byte slot per required signer to sign into; sol4k writes none
         * for an unsigned transaction, so we add them (the message itself is untouched).
         */
        fun unsignedBytes(blockhash: String, payer: PublicKey, instructions: List<Instruction>): ByteArray {
            val raw = Transaction(blockhash, instructions, payer).serialize()
            val message = if (raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw.copyOfRange(1 + 64 * (raw[0].toInt() and 0xFF), raw.size)
            val signers = message[0].toInt() and 0xFF // message header: numRequiredSignatures
            require(signers in 1..127) { "unexpected signer count $signers" }
            return byteArrayOf(signers.toByte()) + ByteArray(64 * signers) + message
        }
    }
}
