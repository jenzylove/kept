package app.kept.wallet

import android.content.Context
import app.kept.keys.TradingKey
import java.io.File
import org.sol4k.Keypair
import org.sol4k.PublicKey

/**
 * The user's main wallet: it owns the vault. Kept never holds this key. On a Seeker this is the
 * Seed Vault (a double tap signs); on any other phone it's whichever Mobile Wallet Adapter wallet
 * the user has (Phantom, Solflare, ...).
 */
interface MainWallet {
    /** Ask the wallet app to connect. Returns the wallet's address. */
    suspend fun connect(): PublicKey

    /**
     * Ask the wallet to sign and send a transaction. [unsigned] is a legacy transaction whose fee
     * payer is the wallet. Returns the transaction signature (base58).
     */
    suspend fun signAndSend(unsigned: ByteArray): String
}

class WalletRejected(message: String) : Exception(message)
class NoWalletApp : Exception("No wallet app found. Install Phantom or Solflare, or use a Seeker.")

/**
 * A software wallet for testing on an emulator that has no wallet app. Debug builds only; the
 * release build never constructs it. It is a real key on a real network, just not a hardware one.
 */
class DebugWallet(context: Context, private val send: suspend (ByteArray, Keypair) -> String) : MainWallet {
    private val keys = TradingKey(File(context.filesDir, "debug-main.key"), app.kept.keys.KeystoreBox("kept_debug_main"))

    override suspend fun connect(): PublicKey = (keys.load() ?: keys.create()).publicKey

    override suspend fun signAndSend(unsigned: ByteArray): String {
        val kp = keys.load() ?: error("connect first")
        return send(unsigned, kp)
    }
}
