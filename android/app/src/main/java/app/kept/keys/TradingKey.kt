package app.kept.keys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.sol4k.Base58
import org.sol4k.Keypair

/** Encrypts and decrypts the trading wallet secret before it touches disk. */
internal interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM backed by Android Keystore. The AES key is non-exportable and stays inside the device's
 * Keystore. The file contains only a version, the random IV, and authenticated ciphertext.
 */
internal class KeystoreBox(private val alias: String) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv
        require(iv.size in 12..16) { "Unexpected AES-GCM IV size" }
        return ByteBuffer.allocate(2 + iv.size + encrypted.size)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size >= 2 + 12 + 16) { "Trading key file is truncated" }
        val bytes = ByteBuffer.wrap(sealed)
        require(bytes.get() == FORMAT_VERSION) { "Unsupported trading key format" }
        val ivSize = bytes.get().toInt() and 0xff
        require(ivSize in 12..16 && bytes.remaining() > ivSize + 15) { "Trading key file is invalid" }
        val iv = ByteArray(ivSize).also(bytes::get)
        val encrypted = ByteArray(bytes.remaining()).also(bytes::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
    }
}

/** A Solana trading key whose encrypted secret is stored in Android app-private storage. */
class TradingKey internal constructor(
    private val file: File,
    private val box: SecretBox,
) {
    constructor(context: Context) : this(
        File(context.filesDir, FILE_NAME),
        KeystoreBox(KEYSTORE_ALIAS),
    )

    fun exists(): Boolean = file.isFile

    /** Creates the key once. Repeated calls return the existing key and never rotate funds away. */
    fun create(): Keypair {
        load()?.let { return it }
        val keypair = Keypair.generate()
        writeAtomically(box.seal(keypair.secret))
        return keypair
    }

    /** Returns null only when no wallet has been created. Corrupt or undecryptable storage fails closed. */
    fun load(): Keypair? {
        if (!file.isFile) return null
        val secret = box.open(file.readBytes())
        require(secret.size == SECRET_BYTES) { "Trading key has the wrong length" }
        return Keypair.fromSecretKey(secret)
    }

    fun exportBase58(): String? = load()?.let { Base58.encode(it.secret) }

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not store the trading key" }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    companion object {
        private const val FILE_NAME = "trading-wallet.key"
        private const val KEYSTORE_ALIAS = "kept_trading_wallet_v1"
        private const val SECRET_BYTES = 64
    }
}
