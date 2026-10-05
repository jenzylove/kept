package app.kept

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.kept.chain.Rpc
import app.kept.chain.VaultProgram
import app.kept.keys.TradingKey
import app.kept.reflex.JsonFileStore
import java.io.File

class KeptApp : Application() {
    val rpc by lazy { Rpc(BuildConfig.RPC_URL) }
    val program by lazy { VaultProgram(BuildConfig.VAULT_PROGRAM) }
    val tradingKey by lazy { TradingKey(this) }
    val prefs by lazy { Prefs(this) }
    val reflexStore by lazy { JsonFileStore(File(filesDir, "reflex.json")) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, "Reflex running", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows that Kept is watching your trading wallet" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BANKED, "Banked wins", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A receipt each time Kept banks part of a win" },
        )
    }

    /** SKR (base units) the owner must hold to unlock the custom share and SKR banking. One constant, shown in the app. */
    val skrTierRaw: Long = 100_000_000L

    /** Websocket used only as a nudge that something happened; it carries no key. */
    val wsUrl: String get() = BuildConfig.WS_URL

    companion object {
        const val CHANNEL_WATCH = "watch"
        const val CHANNEL_BANKED = "banked"
    }
}
