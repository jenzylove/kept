package app.kept

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kept.chain.SkrStaking
import app.kept.chain.VaultProgram
import app.kept.mirror.MirrorLoader
import app.kept.reflex.BankEntry
import app.kept.reflex.WatcherService
import app.kept.trade.Jupiter
import app.kept.wallet.MainWallet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.sol4k.PublicKey

/** The vault's SKR: staked shares, SKR already unstaking inside SKR's own period, and SKR waiting in the vault. */
data class SkrPosition(
    val shares: Long,
    val valueRaw: Long,
    val unstakingRaw: Long,
    val unstakeStartedAt: Long,
    val skrCooldownSecs: Long,
    val vaultHeldRaw: Long,
)

data class AppState(
    val mainWallet: String? = null,
    val tradingWallet: String? = null,
    val vaultAddress: String? = null,
    val vault: VaultProgram.VaultState? = null,
    val vaultLamports: Long = 0,
    val tradingLamports: Long = 0,
    val skrRaw: Long = 0,
    val skr: SkrPosition? = null,
    val bankAsset: String = "sol",
    val log: List<BankEntry> = emptyList(),
    val reflexOn: Boolean = false,
    /** False until the first read of the chain finishes, so we don't flash the wrong screen. */
    val loaded: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    val nowSec: Long = System.currentTimeMillis() / 1000,
)

data class MirrorUi(
    val running: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val result: MirrorLoader.Result? = null,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val kept = app as KeptApp
    private val rpc get() = kept.rpc
    private val program get() = kept.program

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _mirror = MutableStateFlow(MirrorUi())
    val mirror: StateFlow<MirrorUi> = _mirror.asStateFlow()

    /** The trading wallet's own history, for the staircase on the home screen. */
    private val _home = MutableStateFlow<MirrorLoader.Result?>(null)
    val homeMirror: StateFlow<MirrorLoader.Result?> = _home.asStateFlow()
    private var homeLogSize = -1

    /** Set by the activity once it can build a wallet (Mobile Wallet Adapter needs the activity). */
    var wallet: MainWallet? = null

    private val jupiter = Jupiter()

    init {
        _state.update { it.copy(mainWallet = kept.prefs.mainWallet, tradingWallet = kept.tradingKey.load()?.publicKey?.toBase58()) }
        refresh()
        viewModelScope.launch { while (true) { delay(4_000); tick() } }
    }

    private var ticks = 0

    private fun tick() {
        val log = kept.reflexStore.load().log
        _state.update { it.copy(nowSec = System.currentTimeMillis() / 1000, log = log) }
        ticks++
        if (log.size != homeLogSize) {
            // a bank just landed: refresh the vault total and the chart right away
            homeLogSize = log.size
            loadHomeMirror()
            refresh()
        } else if (ticks % 6 == 0) {
            refresh() // every ~24 s, so a withdrawal unlocking or a bank from another phone shows up
        }
    }

    private fun loadHomeMirror() {
        val trading = kept.tradingKey.load()?.publicKey?.toBase58() ?: return
        viewModelScope.launch {
            runCatching { MirrorLoader(rpc).load(trading, days = 365, maxTx = 300) }.onSuccess { _home.value = it }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    private inline fun work(label: String, crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = label, error = null, message = null) }
            try {
                block()
            } catch (e: app.kept.wallet.WalletRejected) {
                val m = e.message.orEmpty()
                val slow = m.contains("invalid for signing", true) || m.contains("payloads invalid", true) || m.contains("Blockhash", true)
                _state.update {
                    it.copy(
                        error = if (slow) "The network rejected that, most likely because approving took too long. Try again and approve within a minute."
                        else "The wallet did not complete that: ${m.take(160).ifBlank { "cancelled" }}",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = friendly(e)) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun friendly(e: Exception): String = when {
        e is app.kept.wallet.NoWalletApp -> e.message!!
        else -> e.message?.take(240) ?: e::class.simpleName.orEmpty()
    }

    fun refresh() {
        viewModelScope.launch {
            val s = _state.value
            val main = s.mainWallet
            runCatching {
                val trading = kept.tradingKey.load()?.publicKey
                val tradingLamports = trading?.let { rpc.balance(it.toBase58()) } ?: 0
                var vault: VaultProgram.VaultState? = null
                var vaultAddr: String? = null
                var vaultLamports = 0L
                var skr = 0L
                if (main != null) {
                    val v = program.vaultAddress(PublicKey(main))
                    vaultAddr = v.toBase58()
                    rpc.accountData(vaultAddr)?.let { vault = program.decodeVault(it) }
                    vaultLamports = if (vault != null) rpc.balance(vaultAddr) else 0
                    skr = runCatching { rpc.tokenBalance(main, SKR_MINT) }.getOrDefault(0)
                }
                var skrPos: SkrPosition? = null
                if (main != null && vault != null && BuildConfig.CLUSTER == "mainnet-beta") {
                    skrPos = runCatching {
                        val vaultPk = program.vaultAddress(PublicKey(main))
                        val us = rpc.accountData(SkrStaking.userStake(vaultPk).toBase58())
                        val cfg = rpc.accountData(SkrStaking.CONFIG.toBase58())
                        val held = runCatching { rpc.tokenBalance(vaultPk.toBase58(), SKR_MINT) }.getOrDefault(0)
                        if (us == null || cfg == null) null else {
                            val shares = SkrStaking.sharesOf(us)
                            val un = SkrStaking.unstakingOf(us)
                            SkrPosition(shares, SkrStaking.shareValue(shares, cfg), un.amountRaw, un.startedAt, SkrStaking.cooldownSecs(cfg), held)
                        }
                    }.getOrNull()
                }
                _state.update {
                    it.copy(
                        tradingWallet = trading?.toBase58(), vaultAddress = vaultAddr, vault = vault, vaultLamports = vaultLamports,
                        tradingLamports = tradingLamports, skrRaw = skr, log = kept.reflexStore.load().log,
                        skr = skrPos, bankAsset = kept.prefs.bankAsset,
                        reflexOn = vault != null && kept.prefs.vaultReady, loaded = true,
                    )
                }
            }.onFailure { e -> _state.update { it.copy(loaded = true, error = "Couldn't reach the network: ${e.message?.take(120)}") } }
        }
    }

    // ---- Mirror -------------------------------------------------------------------------------

    fun runMirror(address: String) {
        val a = address.trim()
        if (runCatching { PublicKey(a).bytes().size }.getOrDefault(0) != 32) {
            _mirror.value = MirrorUi(error = "That doesn't look like a Solana address.")
            return
        }
        viewModelScope.launch {
            _mirror.value = MirrorUi(running = true)
            try {
                val r = MirrorLoader(rpc).load(a, onProgress = { p -> _mirror.update { it.copy(progress = p.fetched to p.total) } })
                _mirror.value = MirrorUi(result = r)
            } catch (e: Exception) {
                _mirror.value = MirrorUi(error = "Couldn't read that wallet: ${e.message?.take(120)}")
            }
        }
    }

    // ---- Setup --------------------------------------------------------------------------------

    fun connectWallet() = work("Connecting to your wallet…") {
        val w = wallet ?: error("Wallet not ready")
        val pk = w.connect()
        kept.prefs.mainWallet = pk.toBase58()
        if (BuildConfig.DEBUG) android.util.Log.i("KeptDebug", "main=${pk.toBase58()}")
        _state.update { it.copy(mainWallet = pk.toBase58()) }
        refresh()
    }

    fun createTradingWallet() = work("Creating your trading wallet…") {
        if (!kept.tradingKey.exists()) kept.tradingKey.create()
        if (BuildConfig.DEBUG) android.util.Log.i("KeptDebug", "trading=${kept.tradingKey.load()?.publicKey?.toBase58()}")
        refresh()
    }

    /** Opens the vault on chain (the main wallet signs) and switches the reflex on. */
    fun createVault(bankBps: Int, cooldownSecs: Long) = work("Opening your vault…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val trading = kept.tradingKey.load()?.publicKey ?: error("Create the trading wallet first")
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, listOf(program.initVault(main, trading, cooldownSecs, bankBps)))
        val sig = w.signAndSend(tx)
        _state.update { it.copy(busy = "Waiting for the network…") }
        rpc.confirm(sig)
        kept.prefs.bankBps = bankBps
        kept.prefs.cooldownSecs = cooldownSecs
        kept.prefs.vaultReady = true
        WatcherService.start(getApplication())
        _state.update { it.copy(message = "Vault open. Kept is on.") }
        refresh()
    }

    fun setReflex(on: Boolean) {
        if (on) WatcherService.start(getApplication()) else WatcherService.stop(getApplication())
        _state.update { it.copy(reflexOn = on) }
    }

    fun fundTrading(lamports: Long) = work("Sending SOL to your trading wallet…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val trading = kept.tradingKey.load()?.publicKey ?: error("Create the trading wallet first")
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, listOf(VaultProgram.transfer(main, trading, lamports)))
        rpc.confirm(w.signAndSend(tx))
        _state.update { it.copy(message = "Sent.") }
        refresh()
    }

    // ---- Vault --------------------------------------------------------------------------------

    fun requestWithdraw(lamports: Long, toTrading: Boolean) = work("Asking to take money out…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val to = if (toTrading) kept.tradingKey.load()!!.publicKey else main
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, listOf(program.requestWithdraw(main, VaultProgram.Asset.Sol, lamports, to)))
        rpc.confirm(w.signAndSend(tx))
        _state.update { it.copy(message = "Requested. It unlocks after the cooldown.") }
        refresh()
    }

    fun cancelWithdraw() = work("Cancelling…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, listOf(program.cancelWithdraw(main)))
        rpc.confirm(w.signAndSend(tx))
        _state.update { it.copy(message = "Cancelled. Your money stays in the vault.") }
        refresh()
    }

    fun executeWithdraw() = work("Taking it out…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val v = _state.value.vault ?: error("No vault")
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, listOf(program.executeWithdrawSol(main, v.withdrawTo)))
        rpc.confirm(w.signAndSend(tx))
        _state.update { it.copy(message = "Done. The money is on its way to ${short(v.withdrawTo.toBase58())}.") }
        refresh()
    }

    fun setBankAsset(asset: String) {
        kept.prefs.bankAsset = asset
        _state.update { it.copy(bankAsset = asset) }
    }

    /** Step 1 of getting staked SKR out: ask, then wait the vault's cooldown like any withdrawal. */
    fun requestSkrUnstake() = work("Asking to unstake…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val pos = _state.value.skr ?: error("No staked SKR")
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, listOf(program.requestWithdraw(main, VaultProgram.Asset.StakedSkr, pos.shares, main)))
        rpc.confirm(w.signAndSend(tx))
        _state.update { it.copy(message = "Requested. It unlocks after your vault's wait.") }
        refresh()
    }

    /** Step 2, after the vault's wait: tell the SKR program to start unstaking. SKR then runs its own period. */
    fun executeSkrUnstake() = work("Unstaking…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, listOf(program.executeUnstakeSkr(main)))
        rpc.confirm(w.signAndSend(tx))
        _state.update { it.copy(message = "Unstaking started. SKR releases it after its own waiting period.") }
        refresh()
    }

    /** Step 3, after SKR's period: collect into the vault and send to the owner in one signature. */
    fun collectSkr() = work("Collecting your SKR…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val vault = program.vaultAddress(main)
        val pos = _state.value.skr ?: error("No SKR position")
        val amount = pos.unstakingRaw + pos.vaultHeldRaw
        require(amount > 0) { "Nothing to collect yet" }
        val ixs = buildList {
            add(SkrStaking.createAtaIdempotent(main, vault))
            if (pos.unstakingRaw > 0) add(SkrStaking.withdraw(vault))
            add(SkrStaking.createAtaIdempotent(main, main))
            add(program.releaseSkr(main, amount))
        }
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, ixs)
        rpc.confirm(w.signAndSend(tx))
        _state.update { it.copy(message = "Done. Your SKR is back in your wallet.") }
        refresh()
    }

    fun setRules(cooldownSecs: Long, bankBps: Int) = work("Saving your rules…") {
        val w = wallet ?: error("Wallet not ready")
        val main = PublicKey(kept.prefs.mainWallet ?: error("Connect your wallet first"))
        val trading = kept.tradingKey.load()!!.publicKey
        val tx = VaultProgram.unsignedBytes(rpc.latestBlockhash(), main, listOf(program.setRules(main, cooldownSecs, bankBps, trading)))
        rpc.confirm(w.signAndSend(tx))
        kept.prefs.bankBps = bankBps
        kept.prefs.cooldownSecs = cooldownSecs
        _state.update { it.copy(message = "Saved.") }
        refresh()
    }

    // ---- Trade (the trading wallet, signed on this phone) ------------------------------------

    suspend fun quote(inMint: String, outMint: String, amount: Long) = jupiter.quote(inMint, outMint, amount)

    fun swap(quote: Jupiter.Quote, label: String) = work(label) {
        val key = kept.tradingKey.load() ?: error("No trading wallet")
        val tx = jupiter.signedSwap(quote, key)
        val sig = rpc.send(tx)
        rpc.confirm(sig)
        _state.update { it.copy(message = "Done. If it's a sell above your cost, the reflex banks your share in a few seconds.") }
        refresh()
    }

    fun tradingTokenBalance(mint: String, onResult: (Long) -> Unit) {
        viewModelScope.launch {
            val owner = kept.tradingKey.load()?.publicKey?.toBase58() ?: return@launch
            onResult(runCatching { rpc.tokenBalance(owner, mint) }.getOrDefault(0))
        }
    }

    fun exportTradingKey(): String? = kept.tradingKey.exportBase58()

    companion object {
        const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        fun short(s: String) = if (s.length > 10) s.take(4) + "…" + s.takeLast(4) else s
    }
}
