package app.kept.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.sol4k.Keypair

class WalletPolicyTest {
    @Test
    fun acceptsTheAuthorizedAccountThatOwnsTheVault() {
        val key = Keypair.generate().publicKey
        assertEquals(key.toBase58(), requireExpectedWallet(key.toBase58(), key.bytes()).toBase58())
    }

    @Test
    fun rejectsAChangedWalletBeforeSending() {
        val expected = Keypair.generate().publicKey
        val actual = Keypair.generate().publicKey
        assertThrows(WalletRejected::class.java) {
            requireExpectedWallet(expected.toBase58(), actual.bytes())
        }
    }
}
