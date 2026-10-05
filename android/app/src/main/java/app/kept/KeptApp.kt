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

    /** The websocket twin of the RPC url (https to wss), unless one is configured. */
    val wsUrl: String get() = BuildConfig.RPC_URL.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

    companion object {
        const val CHANNEL_WATCH = "watch"
        const val CHANNEL_BANKED = "banked"
    }
}
