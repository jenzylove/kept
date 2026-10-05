package app.kept.wallet

import android.net.Uri
import app.kept.Prefs
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import org.sol4k.Base58
import org.sol4k.PublicKey

/**
 * The real main wallet, over Mobile Wallet Adapter. On a Seeker the wallet app is the Seed Vault
 * Wallet and every signature is a double tap; elsewhere it is whichever MWA wallet is installed.
 * The wallet sends the transaction itself, so Kept never touches the key or the signed bytes.
 */
class MwaWallet(
    private val sender: ActivityResultSender,
    private val prefs: Prefs,
    mainnet: Boolean,
) : MainWallet {
    private val adapter = MobileWalletAdapter(
        ConnectionIdentity(
            identityUri = Uri.parse("https://kept-seeker.vercel.app"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "Kept",
        ),
    ).apply {
        blockchain = if (mainnet) Solana.Mainnet else Solana.Devnet
        authToken = prefs.walletAuthToken
    }

    override suspend fun connect(): PublicKey =
        when (val r = adapter.connect(sender)) {
            is TransactionResult.Success -> {
                prefs.walletAuthToken = adapter.authToken ?: r.authResult.authToken
                val key = r.authResult.accounts.firstOrNull()?.publicKey ?: r.authResult.publicKey
                PublicKey(key)
            }
            is TransactionResult.NoWalletFound -> throw NoWalletApp()
            is TransactionResult.Failure -> { android.util.Log.w("KeptMwa", "connect failed: ${r.message}", r.e); throw WalletRejected(r.message) }
        }

    override suspend fun signAndSend(unsigned: ByteArray): String =
        when (val r = adapter.transact(sender) { signAndSendTransactions(arrayOf(unsigned)) }) {
            is TransactionResult.Success -> {
                prefs.walletAuthToken = adapter.authToken ?: prefs.walletAuthToken
                Base58.encode(r.payload.signatures.first())
            }
            is TransactionResult.NoWalletFound -> throw NoWalletApp()
            is TransactionResult.Failure -> { android.util.Log.w("KeptMwa", "signAndSend failed: ${r.message}", r.e); throw WalletRejected(r.message) }
        }
}
