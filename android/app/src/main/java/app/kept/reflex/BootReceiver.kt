package app.kept.reflex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kept.KeptApp

/** Brings the reflex back after a reboot or an app update, but only if the user had set Kept up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as KeptApp
        if (app.prefs.vaultReady && app.prefs.reflexEnabled && app.tradingKey.exists()) WatcherService.start(context)
    }
}
