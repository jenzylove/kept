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
    suspend fun latestBlockhash(): Rpc.BlockhashInfo
    suspend fun blockHeight(): Long
    suspend fun signatureStatus(signature: String): Rpc.SignatureStatus
    suspend fun send(base64Tx: String): String
    suspend fun confirm(signature: String): Boolean
}

class RpcChain(private val rpc: Rpc) : Chain {
    override suspend fun signatures(address: String, limit: Int, before: String?, until: String?) = rpc.signatures(address, limit, before, until)
    override suspend fun transactionWhenReady(signature: String) = rpc.transactionWhenReady(signature)
    override suspend fun latestBlockhash() = rpc.latestBlockhashInfo()
    override suspend fun blockHeight() = rpc.blockHeight()
    override suspend fun signatureStatus(signature: String) = rpc.signatureStatus(signature)
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
        made += reconcilePendingLocked()
        made += retrySafeFailedLocked()
        val pending = newSignatures()
        for (s in pending) {
            // A bank intent is persisted before submission. If the process dies after the RPC
            // accepts it but before the response is saved, the durable log is the idempotency key:
            // advance the cursor without applying the sell or submitting its bank again.
            if (state.log.any { it.sellSignature == s.signature }) {
                state = state.copy(lastSignature = s.signature)
                store.save(state)
                continue
            }
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

    private suspend fun bank(
        sellSignature: String,
        mint: String,
        profit: Long,
        sellTime: Long?,
        priorBankSignatures: List<String> = emptyList(),
    ): BankEntry {
        val now = clock()
        val late = sellTime != null && now - sellTime > LATE_AFTER_SECS
        val amount = Engine.bankAmount(java.math.BigInteger.valueOf(profit), rules()?.bankBps ?: 0).toLong()
        fun record(
            status: BankEntry.Status, sig: String? = null, reason: String? = null,
            extra: (BankEntry) -> BankEntry = { it },
        ) = extra(
            BankEntry(
                sellSignature, mint, profit, amount, sig, status, reason, sellTime, now, late,
                priorBankSignatures = priorBankSignatures,
            ),
        ).also {
            state = state.withLog(it)
            store.save(state)
            onBank(it)
        }

        val r = rules() ?: return record(BankEntry.Status.NoVault, reason = "No vault yet")
        if (amount < minBank) return record(BankEntry.Status.TooSmall, reason = "Share is under $minBank lamports, not worth the fee")
        if (r.asset == BankAsset.StakedSkr && skrBank != null && runCatching { skrTier() }.getOrDefault(false)) {
            return try {
                val out = skrBank.bank(amount, r.vaultOwner, state.waitingSkrRaw) { waiting ->
                    // Saved the moment it changes, before any later step can fail or the process can die.
                    state = state.copy(waitingSkrRaw = waiting)
                    store.save(state)
                }
                state = state.copy(waitingSkrRaw = out.waitingRaw)
                store.save(state)
                record(BankEntry.Status.Confirmed, out.swapSignature) {
                    it.copy(asset = "SKR", stakeSignature = out.stakeSignature, stakedSkrRaw = out.stakedRaw, waitingSkrRaw = out.waitingRaw)
                }
            } catch (e: Exception) {
                // The win is still in the trading wallet; it can be retried. Never silently fall back to SOL.
                record(BankEntry.Status.Failed, reason = "SKR bank: ${e.message ?: e::class.simpleName}") { it.copy(asset = "SKR") }
            }
        }
        val built = try {
            val recent = chain.latestBlockhash()
            recent to VaultProgram.signedBase64(
                recent.blockhash, signer.publicKey,
                listOf(program.bank(signer.publicKey, r.vaultOwner, amount, profit, sellSignature)),
                signer,
            )
        } catch (e: Exception) {
            return record(BankEntry.Status.Failed, reason = "Not submitted: ${e.message ?: e::class.simpleName}")
        }
        val (recent, tx) = built
        val expectedSig = VaultProgram.firstSignature(tx)
        // Persist the signed transaction identity before the first network side effect. A crash
        // after this save may require reconciliation, but it can never duplicate the bank.
        state = state.withLog(
            BankEntry(
                sellSignature, mint, profit, amount, expectedSig, BankEntry.Status.Pending,
                "Submission may be in flight; verify this signature before retrying", sellTime, now, late,
                lastValidBlockHeight = recent.lastValidBlockHeight,
                priorBankSignatures = priorBankSignatures,
            ),
        )
        store.save(state)
        return try {
            val sig = chain.send(tx)
            if (chain.confirm(sig)) {
                record(BankEntry.Status.Confirmed, sig)
            } else {
                val unresolved = state.log.first { it.sellSignature == sellSignature }.copy(
                    bankSignature = expectedSig,
                    reason = "Not confirmed yet; Kept will reconcile this signature before any retry",
                )
                state = state.withLog(unresolved)
                store.save(state)
                unresolved
            }
        } catch (e: Exception) {
            // sendTransaction can fail after the node accepted the transaction. Keep this as a
            // pending intent until the chain proves it failed or its blockhash expires.
            val unresolved = state.log.first { it.sellSignature == sellSignature }.copy(
                bankSignature = expectedSig,
                reason = "Submission uncertain; reconciling signature: ${e.message ?: e::class.simpleName}",
            )
            state = state.withLog(unresolved)
            store.save(state)
            unresolved
        }
    }

    /** Reconcile signed intents after restart without ever resubmitting a transaction that can land. */
    private suspend fun reconcilePendingLocked(): List<BankEntry> {
        val resolved = ArrayList<BankEntry>()
        for (pending in state.log.filter { it.status == BankEntry.Status.Pending }) {
            val signature = pending.bankSignature ?: continue
            val status = runCatching { chain.signatureStatus(signature) }.getOrNull() ?: continue
            if (status == Rpc.SignatureStatus.Confirmed) {
                val confirmed = pending.copy(status = BankEntry.Status.Confirmed, reason = null)
                state = state.withLog(confirmed)
                store.save(state)
                onBank(confirmed)
                resolved += confirmed
                continue
            }
            val expired = pending.lastValidBlockHeight?.let { last ->
                status == Rpc.SignatureStatus.Missing &&
                    runCatching { chain.blockHeight() > last }.getOrDefault(false)
            } == true
            if (status != Rpc.SignatureStatus.Failed && !expired) continue

            // This signature cannot land later. Preserve it in the audit trail and make exactly
            // one retry eligible. If the process dies now, retrySafeFailedLocked resumes safely.
            val retryable = pending.copy(
                bankSignature = null,
                status = BankEntry.Status.Failed,
                reason = if (expired) {
                    "Previous signed attempt expired before landing; retrying safely"
                } else {
                    "Previous signed attempt failed on chain; retrying safely"
                },
                priorBankSignatures = (pending.priorBankSignatures + signature).distinct(),
            )
            state = state.withLog(retryable)
            store.save(state)
        }
        return resolved
    }

    /** Retry only SOL banks that failed before a signed transaction could have been submitted. */
    suspend fun retryFailed(): List<BankEntry> = lock.withLock {
        retrySafeFailedLocked()
    }

    private suspend fun retrySafeFailedLocked(): List<BankEntry> {
        val out = ArrayList<BankEntry>()
        for (f in state.log.filter {
            it.status == BankEntry.Status.Failed && it.bankSignature == null && it.asset == "SOL"
        }) {
            out += bank(f.sellSignature, f.mint, f.profitLamports, f.sellTime, f.priorBankSignatures)
        }
        return out
    }

    companion object {
        const val PAGE = 100
        const val MAX_PAGES = 50
        const val MIN_BANK_LAMPORTS = 100_000L // 0.0001 SOL; below this the fee eats the point
        const val LATE_AFTER_SECS = 120L
    }
}
