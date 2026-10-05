package app.kept.trade

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Keypair
import app.kept.chain.VaultProgram
import org.sol4k.instruction.TransferInstruction

class JupiterSignTest {
    // The same builder the app uses to hand transactions to a wallet app.
    private fun unsignedTx(payer: Keypair): ByteArray =
        VaultProgram.unsignedBytes("11111111111111111111111111111111", payer.publicKey, listOf(TransferInstruction(payer.publicKey, Keypair.generate().publicKey, 1000)))

    @Test
    fun unsignedBytesHaveOneZeroedSlotPerRequiredSigner() {
        val payer = Keypair.generate()
        val tx = unsignedTx(payer)
        assertEquals(1, tx[0].toInt())
        assertArrayEquals(ByteArray(64), tx.copyOfRange(1, 65))
        assertEquals(1, tx[65].toInt()) // message header: one required signature
    }

    @Test
    fun signsTheMessageAsItIsInTheFirstSlotAndTouchesNothingElse() {
        val payer = Keypair.generate()
        val unsigned = unsignedTx(payer)
        val signedB64 = Jupiter.signVersioned(Base64.getEncoder().encodeToString(unsigned), payer)
        val signed = Base64.getDecoder().decode(signedB64)

        assertEquals(unsigned.size, signed.size)
        assertEquals(1, signed[0].toInt())
        val message = unsigned.copyOfRange(65, unsigned.size)
        assertArrayEquals("message bytes are untouched", message, signed.copyOfRange(65, signed.size))
        assertTrue("signature verifies over the message", payer.publicKey.verify(signed.copyOfRange(1, 65), message))
    }

    @Test
    fun handlesTwoSignatureSlotsByFillingOnlyTheFirst() {
        // 2 slots, 3 byte message: [count=2][slot0][slot1][message]
        val payer = Keypair.generate()
        val message = byteArrayOf(9, 8, 7)
        val raw = byteArrayOf(2) + ByteArray(128) + message
        val out = Base64.getDecoder().decode(Jupiter.signVersioned(Base64.getEncoder().encodeToString(raw), payer))
        assertTrue(payer.publicKey.verify(out.copyOfRange(1, 65), message))
        assertArrayEquals(ByteArray(64), out.copyOfRange(65, 129))
        assertArrayEquals(message, out.copyOfRange(129, 132))
    }
}
