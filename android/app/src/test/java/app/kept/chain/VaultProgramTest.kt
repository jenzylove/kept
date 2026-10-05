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
}
