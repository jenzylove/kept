package app.kept.chain

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.sol4k.PublicKey

class SkrStakingTest {
    // The vault our devnet test created; used only as a stable input for address derivation.
    private val vault = PublicKey("5cMv9srSUHZ1cehPKZVtHUmb3h9k5jXtVFmR7UCom6tb")
    private val payer = PublicKey("9u4xoBGpzucCXdbA3YxMhNjCEy7oZzcNuy3QAaZkUhED")

    private val idl: JsonObject by lazy {
        val dir = File(System.getProperty("kept.vectors")!!).parentFile.parentFile
        Json.parseToJsonElement(File(dir, "programs/kept_vault/tests/fixtures/skr_idl.json").readText()).jsonObject
    }

    private fun idlIx(name: String) = idl["instructions"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    @Test
    fun addressesMatchWhatWeb3jsDerivesForTheSameSeeds() {
        // Reference values computed with @solana/web3.js findProgramAddressSync.
        assertEquals("CBdQJQAgCnrnNK27fsgYuKPSN8gyGG54WGsJdt3cxRBB", SkrStaking.userStake(vault).toBase58())
        assertEquals("8rUTGg1XoyuvK9G64S7d37m3HtLZH24oPeMmXkpJH8ir", SkrStaking.EVENT_AUTHORITY.toBase58())
        assertEquals("2yr5MHyXMcGtFXJNWp8UdZerRYKjnwUKGJV52KgEmCbd", SkrStaking.ata(vault).toBase58())
    }

    @Test
    fun stakeMatchesTheProgramsIdlAccountForAccount() {
        val ix = SkrStaking.stake(payer, vault, 2_000_000L)
        val spec = idlIx("stake")
        assertArrayEquals(spec["discriminator"]!!.jsonArray.map { it.jsonPrimitive.content.toInt().toByte() }.toByteArray(), ix.data.copyOf(8))
        val accounts = spec["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(accounts.size, ix.keys.size)
        accounts.forEachIndexed { i, a ->
            val name = a["name"]!!.jsonPrimitive.content
            assertEquals("writable $name", a["writable"]?.jsonPrimitive?.boolean ?: false, ix.keys[i].writable)
            assertEquals("signer $name", a["signer"]?.jsonPrimitive?.boolean ?: false, ix.keys[i].signer)
        }
        // account order the IDL declares
        val names = accounts.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(SkrStaking.userStake(vault), ix.keys[names.indexOf("user_stake")].publicKey)
        assertEquals(vault, ix.keys[names.indexOf("user")].publicKey)
        assertEquals(payer, ix.keys[names.indexOf("payer")].publicKey)
        assertEquals(SkrStaking.ata(payer), ix.keys[names.indexOf("user_token_account")].publicKey)
        assertEquals(2_000_000L, ByteBuffer.wrap(ix.data).order(ByteOrder.LITTLE_ENDIAN).getLong(8))
    }

    @Test
    fun withdrawMatchesTheProgramsIdlAccountForAccount() {
        val ix = SkrStaking.withdraw(vault)
        val spec = idlIx("withdraw")
        assertArrayEquals(spec["discriminator"]!!.jsonArray.map { it.jsonPrimitive.content.toInt().toByte() }.toByteArray(), ix.data)
        val accounts = spec["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(accounts.size, ix.keys.size)
        accounts.forEachIndexed { i, a ->
            val name = a["name"]!!.jsonPrimitive.content
            assertEquals("writable $name", a["writable"]?.jsonPrimitive?.boolean ?: false, ix.keys[i].writable)
            assertEquals("signer $name", a["signer"]?.jsonPrimitive?.boolean ?: false, ix.keys[i].signer)
        }
        val names = accounts.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(SkrStaking.ata(vault), ix.keys[names.indexOf("user_token_account")].publicKey)
    }

    @Test
    fun sharesAreReadFromTheRightOffset() {
        val data = ByteArray(8 + 1 + 32 * 3 + 16 + 8 + 8)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(8 + 1 + 32 * 3)
        buf.putLong(1_740_507L)
        buf.putLong(0)
        assertEquals(1_740_507L, SkrStaking.sharesOf(data))
    }

    @Test
    fun createAtaIsTheIdempotentVariant() {
        val ix = SkrStaking.createAtaIdempotent(payer, vault)
        assertArrayEquals(byteArrayOf(1), ix.data)
        assertEquals(6, ix.keys.size)
        assertEquals(SkrStaking.ata(vault), ix.keys[1].publicKey)
    }
}
