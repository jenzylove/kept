package app.kept

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.kept.ui.HomeScreen
import app.kept.ui.Kept
import app.kept.ui.KeptTheme
import app.kept.ui.MirrorScreen
import app.kept.ui.SettingsScreen
import app.kept.ui.SetupScreen
import app.kept.ui.TradeScreen
import app.kept.wallet.DebugWallet
import app.kept.wallet.MwaWallet
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A light page: the clock and battery icons must be dark.
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(clear, clear),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(clear, clear),
        )
        val kept = application as KeptApp

        // The wallet adapter must be created before the activity starts.
        val sender = ActivityResultSender(this)
        vm.wallet = if (BuildConfig.DEBUG && BuildConfig.DEBUG_WALLET) {
            DebugWallet(this) { tx, key ->
                val signed = app.kept.trade.Jupiter.signVersioned(java.util.Base64.getEncoder().encodeToString(tx), key)
                kept.rpc.send(signed)
            }
        } else {
            MwaWallet(sender, kept.prefs, mainnet = BuildConfig.CLUSTER == "mainnet-beta", currentSlot = { kept.rpc.slot() })
        }

        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            KeptTheme {
                Box(Modifier.fillMaxSize().background(Kept.colors.page).background(Kept.sky)) { Root(vm, kept, ::exportKey) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    /** Reveal the trading key only after the user proves it's them (fingerprint, face or PIN). */
    private fun exportKey(onKey: (String) -> Unit) {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = BiometricManager.from(this).canAuthenticate(allowed)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            if (BuildConfig.DEBUG) { vm.exportTradingKey()?.let(onKey); return } // emulators have no screen lock
            android.widget.Toast.makeText(this, "Set a screen lock on this phone to reveal the key.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Reveal your trading key")
            .setSubtitle("Anyone with this key can move the money in the trading wallet.")
            .setAllowedAuthenticators(allowed)
            .build()
        BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vm.exportTradingKey()?.let(onKey)
                }
            },
        ).authenticate(info)
    }
}

private enum class Tab(val label: String) { Vault("Vault"), Trade("Trade"), Mirror("Mirror"), Settings("Settings") }

@Composable
private fun Root(vm: AppViewModel, kept: KeptApp, onExport: (onKey: (String) -> Unit) -> Unit) {
    val s by vm.state.collectAsState()
    var started by rememberSaveable { mutableStateOf(kept.prefs.onboarded) }
    var tab by rememberSaveable { mutableStateOf(Tab.Vault) }

    when {
        !s.loaded -> Unit
        s.vault != null -> Box(Modifier.fillMaxSize()) {
            when (tab) {
                Tab.Vault -> HomeScreen(vm, s)
                Tab.Trade -> TradeScreen(vm, s)
                Tab.Mirror -> MirrorScreen(vm, onStart = null, connectedWallet = s.tradingWallet)
                Tab.Settings -> SettingsScreen(vm, s, onExport)
            }
            // A message belongs to the screen it happened on; it should not follow the user around.
            TabBar(tab, Modifier.align(Alignment.BottomCenter)) { if (it != tab) vm.clearMessage(); tab = it }
        }
        !started -> MirrorScreen(vm, onStart = { started = true; kept.prefs.onboarded = true }, connectedWallet = null)
        else -> SetupScreen(vm, s)
    }
}

/** The tab bar floats over the page as one frosted pill; the chosen tab is a solid pill inside it. */
@Composable
private fun TabBar(selected: Tab, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val c = Kept.colors
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 22.dp).padding(bottom = 14.dp).fillMaxWidth()
            .shadow(24.dp, CircleShape, ambientColor = c.accent.copy(alpha = 0.18f), spotColor = c.accent.copy(alpha = 0.28f))
            .background(Color.White, CircleShape).border(1.dp, Color.White, CircleShape).padding(6.dp),
    ) {
        Tab.entries.forEach { t ->
            val on = t == selected
            val fill by animateColorAsState(if (on) c.text else Color.Transparent, tween(220), label = "tab")
            Box(
                Modifier.weight(1f).height(46.dp).background(fill, CircleShape).clip(CircleShape).clickable { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(t.label, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else c.dim)
            }
        }
    }
}
