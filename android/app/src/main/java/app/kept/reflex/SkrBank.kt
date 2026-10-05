package app.kept.reflex

import app.kept.chain.Rpc
import app.kept.chain.SkrStaking
import app.kept.chain.VaultProgram
import app.kept.trade.Jupiter
import org.sol4k.Keypair
import org.sol4k.PublicKey

/** What banking a win into staked SKR did. */
data class SkrBanked(
    val swapSignature: String?,
    val stakeSignature: String?,
    /** SKR (base units) staked in the vault's name by this bank. */
    val stakedRaw: Long,
    /** SKR bought but still under SKR's 1 SKR minimum stake: it waits in the trading wallet for the next win. */
    val waitingRaw: Long,
)

/** Banks a win into staked SKR instead of SOL. A seam so the reflex can be tested without a market. */
interface SkrBank {
    suspend fun bank(lamports: Long, vaultOwner: PublicKey): SkrBanked
}

/**
 * Swap the share of the win for SKR through Jupiter, then stake the trading wallet's SKR with Solana
 * Mobile's staking program in the *vault's* name. Both transactions are signed on this phone by the
 * trading key. Once staked, only the Kept program (through the vault's wait) can ever unstake it.
 */
class JupiterSkrBank(
    private val jupiter: Jupiter,
    private val rpc: Rpc,
    private val signer: Keypair,
    private val program: VaultProgram,
) : SkrBank {
    override suspend fun bank(lamports: Long, vaultOwner: PublicKey): SkrBanked {
        val quote = jupiter.quote(Jupiter.SOL, SkrStaking.MINT.toBase58(), lamports)
        val swapSig = rpc.send(jupiter.signedSwap(quote, signer))
        require(rpc.confirm(swapSig)) { "The swap to SKR did not confirm in time" }

        // Everything SKR the trading wallet holds, including leftovers from earlier small wins.
        val held = rpc.tokenBalance(signer.publicKey.toBase58(), SkrStaking.MINT.toBase58())
        if (held < SkrStaking.MIN_STAKE) return SkrBanked(swapSig, null, 0, held)

        val vault = program.vaultAddress(vaultOwner)
        val stakeTx = VaultProgram.signedBase64(
            rpc.latestBlockhash(), signer.publicKey,
            listOf(SkrStaking.stake(signer.publicKey, vault, held)),
            signer,
        )
        val stakeSig = rpc.send(stakeTx)
        require(rpc.confirm(stakeSig)) { "The SKR stake did not confirm in time" }
        return SkrBanked(swapSig, stakeSig, held, 0)
    }
}
