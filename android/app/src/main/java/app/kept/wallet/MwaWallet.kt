package app.kept.wallet

import android.net.Uri
import app.kept.Prefs
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionParams
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sol4k.Base58
import org.sol4k.PublicKey

internal const val MWA_IDENTITY_URI = "https://kept-seeker.vercel.app"
internal const val MWA_ICON_RELATIVE_URI = "favicon.ico"

internal fun mwaConnectionIdentity() = ConnectionIdentity(
    identityUri = Uri.parse(MWA_IDENTITY_URI),
    iconUri = Uri.parse(MWA_ICON_RELATIVE_URI),
    identityName = "Kept",
)

/**
 * The real main wallet, over Mobile Wallet Adapter. On a Seeker the wallet app is the Seed Vault
 * Wallet and every signature is a double tap; elsewhere it is whichever MWA wallet is installed.
 * The wallet sends the transaction itself, so Kept never touches the key or the signed bytes.
 */
class MwaWallet(
    private val sender: ActivityResultSender,
    private val prefs: Prefs,
    private val mainnet: Boolean,
) : MainWallet {
    private val sessionLock = Mutex()

    /**
     * Use a fresh adapter for every operation. A reused adapter retains the previous wallet's
     * walletUriBase, which can leave later local associations targeting a stale wallet endpoint
     * after the user cancels or Android restores the activity. The auth token remains reusable.
     */
    private fun newAdapter() = MobileWalletAdapter(mwaConnectionIdentity()).apply {
        blockchain = if (mainnet) Solana.Mainnet else Solana.Devnet
        authToken = prefs.walletAuthToken
    }

    private suspend fun <T> perform(operation: suspend (MobileWalletAdapter) -> TransactionResult<T>): TransactionResult.Success<T> =
        sessionLock.withLock {
            val adapter = newAdapter()
            when (val result = operation(adapter)) {
                is TransactionResult.Success -> {
                    prefs.walletAuthToken = adapter.authToken ?: result.authResult.authToken
                    result
                }
                is TransactionResult.NoWalletFound -> throw NoWalletApp()
                is TransactionResult.Failure -> {
                    android.util.Log.w("KeptMwa", "wallet operation failed: ${result.message}", result.e)
                    if (result.message.contains("Auth token invalid", ignoreCase = true)) {
                        prefs.walletAuthToken = null
                    }
                    val message = if (result.message.contains("local association", ignoreCase = true)) {
                        "Couldn't reconnect to the wallet app. Close the wallet approval, return to Kept, and retry. Use Change wallet if it repeats."
                    } else {
                        result.message
                    }
                    throw WalletRejected(message)
                }
            }
        }

    override suspend fun connect(): PublicKey {
        val result = perform { it.connect(sender) }
        val key = result.authResult.accounts.firstOrNull()?.publicKey ?: result.authResult.publicKey
        return PublicKey(key)
    }

    override suspend fun signAndSend(unsigned: ByteArray): String {
        val params = TransactionParams(
            minContextSlot = null,
            commitment = "confirmed",
            skipPreflight = false,
            maxRetries = 3,
            waitForCommitmentToSendNextTransaction = false,
        )
        val result = perform { adapter ->
            adapter.transact(sender) { auth ->
                val key = auth.accounts.firstOrNull()?.publicKey ?: auth.publicKey
                requireExpectedWallet(prefs.mainWallet, key)
                signAndSendTransactions(arrayOf(unsigned), params)
            }
        }
        return Base58.encode(result.payload.signatures.first())
    }
}
