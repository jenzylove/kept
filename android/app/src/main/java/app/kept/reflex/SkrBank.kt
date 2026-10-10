package app.kept.reflex

import app.kept.chain.Rpc
import app.kept.chain.RpcException
import app.kept.chain.SkrStaking
import app.kept.chain.VaultProgram
import app.kept.trade.Jupiter
import app.kept.trade.SwapSource
import org.sol4k.Keypair
import org.sol4k.PublicKey

/** What banking a win into staked SKR did. */
data class SkrBanked(
    val swapSignature: String?,
    val stakeSignature: String?,
    /** SKR (base units) staked in the vault's name by this bank. */
    val stakedRaw: Long,
    /** SKR bought by Kept but still under SKR's 1 SKR minimum stake: it waits in the trading wallet for the next win. */
    val waitingRaw: Long,
)

/** Banks a win into staked SKR instead of SOL. A seam so the reflex can be tested without a market. */
interface SkrBank {
    /**
     * [waitingRaw] is the SKR earlier banks bought and left waiting under the minimum stake. The bank
     * stakes at most that plus what this swap just bought. [saveWaiting] is called with the new
     * waiting amount as soon as it changes, so it is on disk before any later step can fail.
     */
    suspend fun bank(lamports: Long, vaultOwner: PublicKey, waitingRaw: Long, saveWaiting: suspend (Long) -> Unit): SkrBanked
}

/** The slice of the chain SKR banking needs. [RpcSkrChain] implements it; tests use a fake. */
interface SkrChain {
    suspend fun tokenBalance(owner: String, mint: String): Long
    suspend fun latestBlockhash(): String
    suspend fun simulateSigned(base64Tx: String)
    suspend fun send(base64Tx: String): String
    suspend fun confirm(signature: String): Boolean
}

class RpcSkrChain(private val rpc: Rpc) : SkrChain {
    override suspend fun tokenBalance(owner: String, mint: String) = rpc.tokenBalance(owner, mint)
    override suspend fun latestBlockhash() = rpc.latestBlockhash()
    override suspend fun simulateSigned(base64Tx: String) = rpc.simulateSigned(base64Tx)
    override suspend fun send(base64Tx: String) = rpc.send(base64Tx)
    override suspend fun confirm(signature: String) = rpc.confirm(signature)
}

/**
 * Swap the share of the win for SKR through Jupiter, then stake the SKR *this bank just bought* with
 * Solana Mobile's staking program in the *vault's* name. Both transactions are signed on this phone
 * by the trading key. Once staked, only the Kept program (through the vault's wait) can ever unstake it.
 *
 * The amount staked is the verified balance change across the swap, plus SKR an earlier bank bought
 * and recorded as waiting. SKR the user put in the trading wallet themselves is never staked.
 */
class JupiterSkrBank(
    private val swap: SwapSource,
    private val chain: SkrChain,
    private val signer: Keypair,
    private val program: VaultProgram,
) : SkrBank {
    override suspend fun bank(lamports: Long, vaultOwner: PublicKey, waitingRaw: Long, saveWaiting: suspend (Long) -> Unit): SkrBanked {
        val me = signer.publicKey.toBase58()
        val mint = SkrStaking.MINT.toBase58()
        require(waitingRaw >= 0) { "Recorded waiting SKR is negative" }

        val before = chain.tokenBalance(me, mint)
        val quote = swap.quote(Jupiter.SOL, mint, lamports)
        require(quote.inMint == Jupiter.SOL && quote.outMint == mint && quote.inAmount == lamports) {
            "The SKR quote does not match the share being banked"
        }
        // signedSwap refuses to sign anything SwapValidator does not accept as a plain swap.
        val swapTx = swap.signedSwap(quote, signer)
        chain.simulateSigned(swapTx)
        val swapSig = chain.send(swapTx)
        require(chain.confirm(swapSig)) { "The swap to SKR did not confirm in time" }

        val after = chain.tokenBalance(me, mint)
        val bought = after - before
        require(bought > 0) { "The swap confirmed but the SKR balance did not rise, so nothing was staked" }
        // A balance that rose by far more than the swap could buy means something else deposited SKR meanwhile.
        require(bought <= quote.outAmount + quote.outAmount / 10 + 1) {
            "The SKR balance rose by more than the swap could buy, so nothing was staked"
        }

        // Never more than the wallet holds, in case the user moved some of it out meanwhile.
        val stakeable = minOf(Math.addExact(waitingRaw, bought), after)
        saveWaiting(stakeable)
        if (stakeable < SkrStaking.MIN_STAKE) return SkrBanked(swapSig, null, 0, stakeable)

        val vault = program.vaultAddress(vaultOwner)
        val stakeTx = VaultProgram.signedBase64(
            chain.latestBlockhash(), signer.publicKey,
            listOf(SkrStaking.stake(signer.publicKey, vault, stakeable)),
            signer,
        )
        chain.simulateSigned(stakeTx)
        // Cleared before the stake can reach the network. If the process dies mid flight the SKR sits
        // unrecorded in the wallet and is never counted again; staking too little is the safe direction.
        saveWaiting(0)
        try {
            val stakeSig = chain.send(stakeTx)
            require(chain.confirm(stakeSig)) { "The SKR stake did not confirm in time. Check the wallet before banking again" }
            return SkrBanked(swapSig, stakeSig, stakeable, 0)
        } catch (e: RpcException) {
            // A negative code is the node refusing it or the chain failing it: the SKR is still waiting.
            if (e.code < 0) saveWaiting(stakeable)
            throw e
        }
    }
}
