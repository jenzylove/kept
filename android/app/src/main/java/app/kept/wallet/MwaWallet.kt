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
// Time for the person to read and approve in their wallet app. Slow phones take ten seconds just to
// show the wallet, so this is generous; it exists only so a lost session cannot hang forever.
internal const val MWA_OPERATION_TIMEOUT_MS = 240_000L

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

/**
 * A wallet app checks a file on our site before it will talk to this app, and gives that check about
 * one second. On some phone networks the first lookup of any site's name takes around six seconds
 * (measured on a real phone), which fails the check before it starts. The phone remembers a name
 * once any app has looked it up, so Kept looks ours up first, and waits for that to finish, before
 * it opens the wallet. Never fails the operation it serves.
 */
internal object IdentityWarmth {
    @Volatile private var warmAt = 0L
    private const val FRESH_MS = 20_000L

    suspend fun prime(maxWaitMs: Long) {
        if (System.currentTimeMillis() - warmAt < FRESH_MS) return
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(maxWaitMs) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL("$MWA_IDENTITY_URI/.well-known/assetlinks.json")
                    java.net.InetAddress.getAllByName(url.host)
                    val c = url.openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 4_000; c.readTimeout = 4_000
                    try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                    warmAt = System.currentTimeMillis()
                }
            }
        }
    }
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
    /**
     * Every request starts with the wallet's own connect screen, not a saved login. Measured on a real
     * phone: a wallet app that has just started turns down a saved login in a hundredth of a second,
     * every time, and each refusal costs the user a wasted trip to the wallet. A fresh connect is one
     * extra tap and it is always accepted. The wallet check inside each request makes sure it is the
     * same wallet as before.
     */
    private fun newAdapter() = MobileWalletAdapter(mwaConnectionIdentity()).apply {
        blockchain = if (mainnet) Solana.Mainnet else Solana.Devnet
        authToken = null
    }

    private suspend fun <T> perform(operation: suspend (MobileWalletAdapter) -> TransactionResult<T>): TransactionResult.Success<T> =
        withWalletOperationTimeout {
            sessionLock.withLock {
            var adapter = newAdapter()
            var result = operation(adapter)
            // A refusal at the connect step happens before anything is signed, so asking once more is safe.
            if (result is TransactionResult.Failure && isIdentityRefusal(result.e)) {
                android.util.Log.w("KeptMwa", "wallet refused at the connect step, asking once more")
                kotlinx.coroutines.delay(800)
                adapter = newAdapter()
                result = operation(adapter)
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
                        "Your wallet app would not confirm Kept just now. Nothing was sent. Open your wallet app once, come back, and try again."
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
