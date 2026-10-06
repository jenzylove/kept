package app.kept.wallet

import org.sol4k.PublicKey

/** Fail closed if the wallet app authorizes a different account than the vault owner. */
fun requireExpectedWallet(expectedAddress: String?, actualKey: ByteArray): PublicKey {
    val expected = expectedAddress ?: throw WalletRejected("Reconnect the wallet that owns this vault.")
    val actual = PublicKey(actualKey)
    if (actual.toBase58() != expected) {
        throw WalletRejected("The wallet app selected a different account. Reconnect the wallet ending ${expected.takeLast(4)} and try again.")
    }
    return actual
}
