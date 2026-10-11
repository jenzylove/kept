package app.kept.reflex

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.kept.BuildConfig
import app.kept.KeptApp
import app.kept.chain.Net
import app.kept.MainActivity
import app.kept.R
import app.kept.chain.SkrStaking
import app.kept.trade.Jupiter
import org.sol4k.PublicKey
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Keeps the reflex alive. A websocket tells us the moment the trading wallet has activity; a timer
 * covers anything the websocket misses; both just ask the [Reflex] to drain, so nothing is skipped.
 */
class WatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android kills the app if a foreground service is slow to show its notice, so show it at the first chance. */
    private fun goForeground() {
        ServiceCompat.startForeground(
            this, NOTE_ID, watchingNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { goForeground() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!started) {
            started = true
            scope.launch { run() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        val app = application as KeptApp
        val key = app.tradingKey.load()
        if (key == null) { stopSelf(); return }
        val owner = runCatching { PublicKey(app.prefs.mainWallet ?: "") }.getOrNull()
        val onChainVault = owner?.let { ownerKey ->
            runCatching {
                app.rpc.accountData(app.program.vaultAddress(ownerKey).toBase58())?.let(app.program::decodeVault)
            }.getOrNull()
        }
        if (owner == null || onChainVault == null || onChainVault.owner != owner || onChainVault.banker != key.publicKey) {
            app.prefs.reflexEnabled = false
            setStatus("Paused: vault ownership does not match this device")
            stopSelf()
            return
        }
        val reflex = Reflex(
            chain = RpcChain(app.rpc),
            signer = key,
            program = app.program,
            store = app.reflexStore,
            // Staked SKR banking is mainnet only; anywhere else every win banks as SOL.
            rules = { app.prefs.rules()?.let { if (BuildConfig.CLUSTER == "mainnet-beta") it else it.copy(asset = BankAsset.Sol) } },
            onBank = { notifyBank(it) },
            skrBank = if (BuildConfig.CLUSTER == "mainnet-beta") JupiterSkrBank(Jupiter(), RpcSkrChain(app.rpc), key, app.program) else null,
            skrTier = {
                val main = app.prefs.mainWallet
                main != null && app.rpc.tokenBalance(main, SkrStaking.MINT.toBase58()) >= app.skrTierRaw
            },
        )
        reflex.activate()
        val nudge = Channel<Unit>(Channel.CONFLATED)

        scope.launch {
            for (n in nudge) {
                runCatching { reflex.drain() }.onFailure { setStatus("Waiting for the network…") }
                    .onSuccess { setStatus("Watching ${short(key.publicKey.toBase58())}") }
            }
        }
        scope.launch { while (isActive) { delay(TIMER_MS); nudge.trySend(Unit) } }
        nudge.trySend(Unit) // catch up on anything that happened while we were not running

        var backoff = 1_000L
        while (scope.isActive) {
            val opened = socket(app, key.publicKey.toBase58(), nudge)
            if (opened) backoff = 1_000L
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
            nudge.trySend(Unit)
        }
    }

    /** Hold one websocket open until it drops. Returns true if it connected. */
    private suspend fun socket(app: KeptApp, address: String, nudge: Channel<Unit>): Boolean {
        var connected = false
        val client = Net.client(pingSec = 20)
        suspendCancellableCoroutine { cont ->
            val ws = client.newWebSocket(
                Request.Builder().url(app.wsUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected = true
                        webSocket.send(
                            """{"jsonrpc":"2.0","id":1,"method":"logsSubscribe","params":[{"mentions":["$address"]},{"commitment":"confirmed"}]}""",
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("logsNotification")) nudge.trySend(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                },
            )
            cont.invokeOnCancellation { ws.cancel() }
        }
        return connected
    }

    private fun setStatus(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTE_ID, watchingNotification(text))
    }

    private fun open(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun watchingNotification(text: String): Notification =
        NotificationCompat.Builder(this, KeptApp.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_kept)
            .setContentTitle("Kept is on")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open())
            .build()

    private val tokens by lazy { app.kept.trade.Tokens() }

    /** "Kept 0.0100 SOL from SKR", then the win and what the vault holds now. Names and totals are looked up, and left out if that fails. */
    private fun notifyBank(e: BankEntry) {
        if (e.status == BankEntry.Status.Pending || e.status == BankEntry.Status.TooSmall) return
        scope.launch {
            val app = application as KeptApp
            val sol = { l: Long -> String.format(Locale.US, "%.4f", l / 1e9) }
            val name = runCatching { tokens.byMint(listOf(e.mint))[e.mint]?.symbol }.getOrNull() ?: short(e.mint)
            val vaultNow = runCatching {
                val owner = PublicKey(app.prefs.mainWallet!!)
                val address = app.program.vaultAddress(owner).toBase58()
                (app.rpc.balance(address) - app.rpc.minimumBalanceForRentExemption(VAULT_SIZE)).coerceAtLeast(0)
            }.getOrNull()
            val (title, text) = when (e.status) {
                BankEntry.Status.Confirmed -> if (e.asset == "SKR") {
                    "Kept ${sol(e.amountLamports)} SOL from $name, as staked SKR" to
                        (if (e.stakedSkrRaw > 0) "Win of ${sol(e.profitLamports)} SOL. Your share is now staked SKR in your vault." else "Win of ${sol(e.profitLamports)} SOL. The SKR is staked with your next win.")
                } else {
                    "Kept ${sol(e.amountLamports)} SOL from $name" to
                        ("Win of ${sol(e.profitLamports)} SOL." + (vaultNow?.let { " Vault: ${sol(it)} SOL." } ?: "") + (if (e.late) " Found late." else ""))
                }
                BankEntry.Status.Failed -> "A win on $name was not kept" to (e.reason ?: "Kept will try again.")
                BankEntry.Status.NoVault -> "A win on $name was not kept" to "Open Kept and open your vault."
                else -> return@launch
            }
            getSystemService(android.app.NotificationManager::class.java).notify(
                e.sellSignature.hashCode(),
                NotificationCompat.Builder(this@WatcherService, KeptApp.CHANNEL_BANKED)
                    .setSmallIcon(R.drawable.ic_stat_kept)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open())
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    companion object {
        private const val NOTE_ID = 1
        private val VAULT_SIZE = app.kept.chain.VaultProgram.VAULT_ACCOUNT_SIZE
        private const val TIMER_MS = 15_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatcherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }

        fun short(s: String) = if (s.length > 10) s.take(4) + "…" + s.takeLast(4) else s
    }
}
