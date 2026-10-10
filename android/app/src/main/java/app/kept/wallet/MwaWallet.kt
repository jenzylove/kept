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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.sol4k.Base58
import org.sol4k.PublicKey

internal const val MWA_IDENTITY_URI = "https://kept-seeker.vercel.app"
internal const val MWA_ICON_RELATIVE_URI = "favicon.ico"
internal const val MWA_OPERATION_TIMEOUT_MS = 70_000L

internal suspend fun <T> withWalletOperationTimeout(
    timeoutMs: Long = MWA_OPERATION_TIMEOUT_MS,
    operation: suspend () -> T,
): T = try {
    withTimeout(timeoutMs) { operation() }
} catch (_: TimeoutCancellationException) {
    throw WalletRejected(
        "Wallet approval timed out and Kept received no submitted signature. " +
            "Do not retry until you check the wallet's activity.",
    )
}

/** True when the wallet declined at the authorization step, before any request to sign reached the user. */
internal fun isIdentityRefusal(e: Throwable?): Boolean =
    generateSequence(e) { it.cause }.take(8).any { it.message?.contains("authorization request failed", ignoreCase = true) == true }

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
    /** The cluster's current slot, sent as minContextSlot. */
    private val currentSlot: suspend () -> Long,
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
        withWalletOperationTimeout {
            sessionLock.withLock {
            warmIdentity()
            var adapter = newAdapter()
            var result = operation(adapter)
            // A wallet app that has just started gives our site one second to prove this app is ours,
            // and on a phone network that often runs out. It refuses before showing anything, so
            // nothing was signed and asking once more is safe; by then the wallet is awake.
            var asks = 1
            while (result is TransactionResult.Failure && isIdentityRefusal(result.e) && asks < 3) {
                android.util.Log.w("KeptMwa", "wallet could not verify the app in time, asking again ($asks)")
                // Give a wallet that has just started a moment to finish its own loading.
                kotlinx.coroutines.delay(1_500)
                warmIdentity()
                adapter = newAdapter()
                result = operation(adapter)
                asks++
            }
            when (result) {
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
                    val message = if (isIdentityRefusal(result.e)) {
                        "Your wallet app could not confirm Kept in time, which happens on a slow connection. Nothing was sent. Try again in a moment."
                    } else if (result.message.contains("local association", ignoreCase = true)) {
                        "Couldn't reconnect to the wallet app. Close the wallet approval, return to Kept, and retry. Use Change wallet if it repeats."
                    } else {
                        result.message
                    }
                    throw WalletRejected(message)
                }
            }
        }
        }

    /** Fetch the file wallets verify us against, so the network path to it is warm. Never fails the operation. */
    private suspend fun warmIdentity() {
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(2_500) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val c = java.net.URL("$MWA_IDENTITY_URI/.well-known/assetlinks.json").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 2_000; c.readTimeout = 2_000
                    try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                }
            }
        }
    }

    override suspend fun connect(): PublicKey {
        val result = perform { it.connect(sender) }
        val key = result.authResult.accounts.firstOrNull()?.publicKey ?: result.authResult.publicKey
        return PublicKey(key)
    }

    override suspend fun signAndSend(unsigned: ByteArray): String {
        // Phantom rejects sign_and_send without a numeric minContextSlot (the MWA spec says optional).
        val minSlot = runCatching { currentSlot() }.getOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            ?: throw WalletRejected("Couldn't read the network's current slot. Check your connection and retry.")
        val params = TransactionParams(
            minContextSlot = minSlot,
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
