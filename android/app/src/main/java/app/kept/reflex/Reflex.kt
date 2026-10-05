package app.kept.reflex

import app.kept.chain.Rpc
import app.kept.chain.VaultProgram
import app.kept.engine.Engine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.sol4k.Keypair
import org.sol4k.PublicKey

/** The slice of the chain the reflex needs. [Rpc] implements it; tests use a fake. */
interface Chain {
    suspend fun signatures(address: String, limit: Int, before: String?, until: String?): List<Rpc.SigInfo>
    suspend fun transactionWhenReady(signature: String): JsonObject?
    suspend fun latestBlockhash(): String
    suspend fun send(base64Tx: String): String
    suspend fun confirm(signature: String): Boolean
}

class RpcChain(private val rpc: Rpc) : Chain {
    override suspend fun signatures(address: String, limit: Int, before: String?, until: String?) = rpc.signatures(address, limit, before, until)
    override suspend fun transactionWhenReady(signature: String) = rpc.transactionWhenReady(signature)
    override suspend fun latestBlockhash() = rpc.latestBlockhash()
    override suspend fun send(base64Tx: String) = rpc.send(base64Tx)
    override suspend fun confirm(signature: String) = rpc.confirm(signature)
}

enum class BankAsset { Sol, StakedSkr }

/** What the reflex needs to know about the vault it banks into. */
data class Rules(val vaultOwner: PublicKey, val bankBps: Int, val asset: BankAsset = BankAsset.Sol)

/**
 * The reflex: watches the trading wallet's history in order, keeps cost basis, and when a sell
 * lands above its cost, signs the bank transfer itself (no tap) and sends it to the vault.
 *
 * One code path does everything: [drain] reads every signature past the last one handled and
 * handles them oldest first. A websocket nudge, a timer and a restart all just call [drain], so a
 * missed notification can never skip a sell; it is picked up (and labelled late) on the next pass.
 */
class Reflex(
    private val chain: Chain,
    private val signer: Keypair,
    private val program: VaultProgram,
    private val store: StateStore,
    private val rules: () -> Rules?,
    private val watched: String = signer.publicKey.toBase58(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val onBank: (BankEntry) -> Unit = {},
    private val minBank: Long = MIN_BANK_LAMPORTS,
    /** Banks into staked SKR when the rules ask for it. Null means SOL only. */
    private val skrBank: SkrBank? = null,
    /** True while the owner still holds enough SKR to use SKR banking; checked at bank time. */
    private val skrTier: suspend () -> Boolean = { true },
) {
    private val lock = Mutex()
    private var state = store.load()

    val snapshot: ReflexState get() = state

    /** Start counting wins from now. Sells before this still update cost basis but are never banked. */
    suspend fun activate(at: Long = clock()) = lock.withLock {
        if (state.activatedAt == 0L) {
            state = state.copy(activatedAt = at)
            store.save(state)
        }
    }

    /** Handle everything new on the trading wallet. Returns the bank attempts made in this pass. */
    suspend fun drain(): List<BankEntry> = lock.withLock {
        val made = ArrayList<BankEntry>()
        val pending = newSignatures()
        for (s in pending) {
            val tx = chain.transactionWhenReady(s.signature) ?: break // not indexed yet: retry next pass, keep order
            val entry = handle(s.signature, tx)
            if (entry != null) made += entry
            state = state.copy(lastSignature = s.signature)
            store.save(state)
        }
        made
    }

    /** Signatures newer than the last one handled, oldest first. */
    private suspend fun newSignatures(): List<Rpc.SigInfo> {
        val all = ArrayList<Rpc.SigInfo>()
        var before: String? = null
        for (pageNo in 0 until MAX_PAGES) {
            val page = chain.signatures(watched, PAGE, before, state.lastSignature)
            all += page
            if (page.size < PAGE) break
            before = page.last().signature
        }
        // Newest first from the node; handle oldest first. Paging stops by itself at lastSignature
        // (the `until` bound), so the cap is only a safety net for a wallet with thousands of new
        // transactions between passes.
        return all.asReversed()
    }

    private suspend fun handle(signature: String, tx: JsonObject): BankEntry? {
        if (Engine.failed(tx)) return null
        val applied = Engine.apply(state.enginePositions(), Engine.classify(Engine.deltas(tx, watched)))
        state = state.withPositions(applied.positions)
        val sold = applied.event as? Engine.Event.Sold ?: return null
        val profit = sold.profit ?: return null // no known cost: never banked
        if (profit.signum() <= 0) return null
        val sellTime = Engine.blockTime(tx)
        if (sellTime != null && sellTime < state.activatedAt) return null // before Kept was on
        return bank(signature, sold.mint, profit.toLong(), sellTime)
    }

    private suspend fun bank(sellSignature: String, mint: String, profit: Long, sellTime: Long?): BankEntry {
        val now = clock()
        val late = sellTime != null && now - sellTime > LATE_AFTER_SECS
        val amount = Engine.bankAmount(java.math.BigInteger.valueOf(profit), rules()?.bankBps ?: 0).toLong()
        fun record(
            status: BankEntry.Status, sig: String? = null, reason: String? = null,
            extra: (BankEntry) -> BankEntry = { it },
        ) = extra(BankEntry(sellSignature, mint, profit, amount, sig, status, reason, sellTime, now, late)).also {
            state = state.withLog(it)
            store.save(state)
            onBank(it)
        }

        val r = rules() ?: return record(BankEntry.Status.NoVault, reason = "No vault yet")
        if (amount < minBank) return record(BankEntry.Status.TooSmall, reason = "Share is under $minBank lamports, not worth the fee")
        if (r.asset == BankAsset.StakedSkr && skrBank != null && runCatching { skrTier() }.getOrDefault(false)) {
            return try {
                val out = skrBank.bank(amount, r.vaultOwner)
                record(BankEntry.Status.Confirmed, out.swapSignature) {
                    it.copy(asset = "SKR", stakeSignature = out.stakeSignature, stakedSkrRaw = out.stakedRaw, waitingSkrRaw = out.waitingRaw)
                }
            } catch (e: Exception) {
                // The win is still in the trading wallet; it can be retried. Never silently fall back to SOL.
                record(BankEntry.Status.Failed, reason = "SKR bank: ${e.message ?: e::class.simpleName}") { it.copy(asset = "SKR") }
            }
        }
        return try {
            val hash = chain.latestBlockhash()
            val tx = VaultProgram.signedBase64(
                hash, signer.publicKey,
                listOf(program.bank(signer.publicKey, r.vaultOwner, amount, profit, sellSignature)),
                signer,
            )
            val sig = chain.send(tx)
            if (chain.confirm(sig)) record(BankEntry.Status.Confirmed, sig) else record(BankEntry.Status.Failed, sig, "Not confirmed in time; it may still land")
        } catch (e: Exception) {
            record(BankEntry.Status.Failed, reason = e.message ?: e::class.simpleName)
        }
    }

    /** Try again for banks that failed, using the profit we recorded. */
    suspend fun retryFailed(): List<BankEntry> = lock.withLock {
        val out = ArrayList<BankEntry>()
        for (f in state.log.filter { it.status == BankEntry.Status.Failed && it.bankSignature == null }) {
            // A failed attempt may have landed anyway; the program ties each bank to a sell, but the chain is the judge.
            out += bank(f.sellSignature, f.mint, f.profitLamports, f.sellTime)
        }
        out
    }

    companion object {
        const val PAGE = 100
        const val MAX_PAGES = 50
        const val MIN_BANK_LAMPORTS = 100_000L // 0.0001 SOL; below this the fee eats the point
        const val LATE_AFTER_SECS = 120L
    }
}
