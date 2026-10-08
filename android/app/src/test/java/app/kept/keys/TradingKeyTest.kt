package app.kept.keys

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sol4k.Base58

class TradingKeyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private class TestBox : SecretBox {
        override fun seal(plain: ByteArray): ByteArray = byteArrayOf(1) + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            require(sealed.firstOrNull() == 1.toByte()) { "bad test ciphertext" }
            return sealed.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    @Test
    fun reportsMissingKeyWithoutCreatingOne() {
        val file = File(temporary.root, "wallet.key")
        val key = TradingKey(file, TestBox())

        assertFalse(key.exists())
        assertNull(key.load())
    }

    @Test
    fun missingKeyCannotBeExported() {
        val file = File(temporary.root, "wallet.key")
        val key = TradingKey(file, TestBox())

        assertNull(key.exportBase58())
        assertFalse(file.exists())
    }

    @Test
    fun createStoresEncryptedSecretAndLoadsTheSameKey() {
        val file = File(temporary.root, "wallet.key")
        val key = TradingKey(file, TestBox())

        val created = key.create()
        val loaded = key.load()!!

        assertTrue(key.exists())
        assertArrayEquals(created.secret, loaded.secret)
        assertEquals(created.publicKey, loaded.publicKey)
        assertEquals(Base58.encode(created.secret), key.exportBase58())
        assertNotEquals(created.secret.toList(), file.readBytes().takeLast(64))
    }

    @Test
    fun createNeverRotatesAnExistingWallet() {
        val key = TradingKey(File(temporary.root, "wallet.key"), TestBox())
        val first = key.create()
        val second = key.create()
        assertArrayEquals(first.secret, second.secret)
        assertEquals(first.publicKey, second.publicKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptStorageFailsClosedInsteadOfCreatingAnotherWallet() {
        val file = File(temporary.root, "wallet.key").apply { writeBytes(byteArrayOf(9, 1, 2, 3)) }
        TradingKey(file, TestBox()).load()
    }
}
