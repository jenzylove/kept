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
import app.kept.KeptApp
import app.kept.MainActivity
import app.kept.R
import app.kept.chain.SkrStaking
import app.kept.trade.Jupiter
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTE_ID, watchingNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
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
        val reflex = Reflex(
            chain = RpcChain(app.rpc),
            signer = key,
            program = app.program,
            store = app.reflexStore,
            rules = { app.prefs.rules() },
            onBank = { notifyBank(it) },
            skrBank = JupiterSkrBank(Jupiter(), app.rpc, key, app.program),
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
        val client = OkHttpClient.Builder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
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

    private fun notifyBank(e: BankEntry) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val sol = String.format(Locale.US, "%.4f", e.amountLamports / 1e9)
        val (title, text) = when (e.status) {
            BankEntry.Status.Confirmed -> if (e.asset == "SKR") {
                "Staked SKR for $sol SOL" to (if (e.stakedSkrRaw > 0) "Your share of a win on ${short(e.mint)} is now staked SKR in your vault." else "Bought SKR with your share. It will be staked with your next win.")
            } else "Banked $sol SOL" to "Kept your share of a win on ${short(e.mint)}${if (e.late) " (found late)" else ""}."
            BankEntry.Status.Failed -> "Bank didn't go through" to (e.reason ?: "We'll try again.")
            BankEntry.Status.NoVault -> "Win not banked" to "Open Kept and create your vault."
            BankEntry.Status.TooSmall -> return
        }
        nm.notify(
            e.sellSignature.hashCode(),
            NotificationCompat.Builder(this, KeptApp.CHANNEL_BANKED)
                .setSmallIcon(R.drawable.ic_stat_kept)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open())
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val NOTE_ID = 1
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
