package app.kept.chain

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Keypair
import org.sol4k.PublicKey

class VaultProgramTest {
    private val program = VaultProgram("EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK")

    // The owner and vault our devnet smoke test created (see docs/spikes/devnet-smoke-output.json).
    private val devnetOwner = PublicKey("BVJUKxqZqW4LUWASMP87RZLPxkJPcFMCJkPjzNMR8Qed")
    private val devnetVault = "5cMv9srSUHZ1cehPKZVtHUmb3h9k5jXtVFmR7UCom6tb"

    @Test
    fun vaultAddressMatchesTheOneTheRealProgramCreatedOnDevnet() {
        assertEquals(devnetVault, program.vaultAddress(devnetOwner).toBase58())
    }

    @Test
    fun instructionDiscriminatorsMatchTheProgramsIdl() {
        val idl = Json.parseToJsonElement(File(System.getProperty("kept.idl")!!).readText()).jsonObject
        val discs = idl["instructions"]!!.jsonArray.associate { i ->
            val o = i.jsonObject
            o["name"]!!.jsonPrimitive.content to o["discriminator"]!!.jsonArray.map { it.jsonPrimitive.content.toInt().toByte() }.toByteArray()
        }
        val k = Keypair.generate().publicKey
        val sig = org.sol4k.Base58.encode(ByteArray(64) { 7 })
        val built = mapOf(
            "init_vault" to program.initVault(k, k, 3600, 5000),
            "bank" to program.bank(k, k, 1, 2, sig),
            "request_withdraw" to program.requestWithdraw(k, VaultProgram.Asset.Sol, 1, k),
            "cancel_withdraw" to program.cancelWithdraw(k),
            "execute_withdraw_sol" to program.executeWithdrawSol(k, k),
            "set_rules" to program.setRules(k, 3600, 5000, k),
        )
        for ((name, ix) in built) {
            assertArrayEquals("discriminator for $name", discs[name], ix.data.copyOf(8))
        }
    }

    @Test
    fun bankInstructionLayoutIsAmountProfitThenTheSellSignature() {
        val banker = Keypair.generate().publicKey
        val sigBytes = ByteArray(64) { (it + 1).toByte() }
        val ix = program.bank(banker, devnetOwner, amount = 50_000_000L, profit = 123L, sellSignature = org.sol4k.Base58.encode(sigBytes))
        val b = ByteBuffer.wrap(ix.data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8 + 8 + 8 + 64, ix.data.size)
        b.position(8)
        assertEquals(50_000_000L, b.long)
        assertEquals(123L, b.long)
        assertArrayEquals(sigBytes, ByteArray(64).also { b.get(it) })
        // accounts: banker (signer, writable), vault (writable), system program
        assertTrue(ix.keys[0].signer && ix.keys[0].writable)
        assertEquals(devnetVault, ix.keys[1].publicKey.toBase58())
        assertTrue(ix.keys[1].writable && !ix.keys[1].signer)
    }

    @Test
    fun vaultAccountSizeAndFundingGuardMatchTheRealDevnetAccount() {
        val raw = Base64.getDecoder().decode(javaClass.getResource("/vault-devnet.b64")!!.readText().trim())
        assertEquals(raw.size, VaultProgram.VAULT_ACCOUNT_SIZE)
        assertEquals(1_534_080L, VaultProgram.vaultOpenShortfall(balance = 0, rent = 1_529_080L))
        assertEquals(34_080L, VaultProgram.vaultOpenShortfall(balance = 1_500_000L, rent = 1_529_080L))
        assertEquals(0L, VaultProgram.vaultOpenShortfall(balance = 1_534_080L, rent = 1_529_080L))
    }

    @Test
    fun decodesTheRealDevnetVaultAccount() {
        val raw = Base64.getDecoder().decode(javaClass.getResource("/vault-devnet.b64")!!.readText().trim())
        val v = program.decodeVault(raw)
        assertEquals(devnetOwner.toBase58(), v.owner.toBase58())
        assertEquals("9u4xoBGpzucCXdbA3YxMhNjCEy7oZzcNuy3QAaZkUhED", v.banker.toBase58())
        assertEquals(60L, v.cooldownSecs)
        assertEquals(5000, v.bankBps)
        assertEquals(50_000_000L, v.totalBanked)
        assertEquals(1L, v.bankCount)
        assertEquals(0L, v.totalWithdrawn)
        // the smoke test left one pending withdrawal of 0.01 SOL back to the owner
        assertTrue(v.hasPendingWithdraw)
        assertEquals(VaultProgram.Asset.Sol, v.withdrawAsset)
        assertEquals(10_000_000L, v.withdrawAmount)
        assertEquals(devnetOwner.toBase58(), v.withdrawTo.toBase58())
        assertTrue(v.withdrawUnlockAt > 1_700_000_000L)
        assertFalse(v.skrUnstakeApproved)
    }

    @Test
    fun aSignedBankTransactionVerifies() {
        val trader = Keypair.generate()
        val tx = VaultProgram.signedBase64(
            blockhash = "11111111111111111111111111111111",
            payer = trader.publicKey,
            instructions = listOf(program.bank(trader.publicKey, devnetOwner, 1000, 2000, org.sol4k.Base58.encode(ByteArray(64) { 3 }))),
            trader,
        )
        val bytes = Base64.getDecoder().decode(tx)
        assertEquals(1, bytes[0].toInt()) // one signature
        val sig = bytes.copyOfRange(1, 65)
        val message = bytes.copyOfRange(65, bytes.size)
        assertTrue(trader.publicKey.verify(sig, message))
    }

    private fun idlInstruction(name: String) = Json.parseToJsonElement(File(System.getProperty("kept.idl")!!).readText())
        .jsonObject["instructions"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    private fun assertMatchesIdl(name: String, ix: org.sol4k.instruction.Instruction) {
        val spec = idlInstruction(name)
        assertArrayEquals("discriminator for $name", spec["discriminator"]!!.jsonArray.map { it.jsonPrimitive.content.toInt().toByte() }.toByteArray(), ix.data.copyOf(8))
        val accounts = spec["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("account count for $name", accounts.size, ix.keys.size)
        accounts.forEachIndexed { i, a ->
            val n = a["name"]!!.jsonPrimitive.content
            assertEquals("$name.$n writable", a["writable"]?.jsonPrimitive?.content == "true", ix.keys[i].writable)
            assertEquals("$name.$n signer", a["signer"]?.jsonPrimitive?.content == "true", ix.keys[i].signer)
        }
    }

    @Test
    fun skrExitInstructionsMatchTheProgramsIdl() {
        assertMatchesIdl("execute_unstake_skr", program.executeUnstakeSkr(devnetOwner))
        assertMatchesIdl("release_skr", program.releaseSkr(devnetOwner, 1_000_000L))
        // release_skr's amount is the 8 bytes after the discriminator
        val d = program.releaseSkr(devnetOwner, 1_000_000L).data
        assertEquals(1_000_000L, java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(8))
        // the vault is the SKR stake's owner and holds its own token account
        val ix = program.executeUnstakeSkr(devnetOwner)
        assertEquals(SkrStaking.userStake(program.vaultAddress(devnetOwner)), ix.keys[1].publicKey)
    }
}
