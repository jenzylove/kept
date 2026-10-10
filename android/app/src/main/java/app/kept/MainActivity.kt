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
import androidx.compose.foundation.background
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
import app.kept.ui.Hairline
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
        enableEdgeToEdge()
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
                Box(Modifier.fillMaxSize().background(Kept.colors.ink)) { Root(vm, kept, ::exportKey) }
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
        s.vault != null -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Vault -> HomeScreen(vm, s)
                    Tab.Trade -> TradeScreen(vm, s)
                    Tab.Mirror -> MirrorScreen(vm, onStart = null, connectedWallet = s.tradingWallet)
                    Tab.Settings -> SettingsScreen(vm, s, onExport)
                }
            }
            // A message belongs to the screen it happened on; it should not follow the user around.
            TabBar(tab) { if (it != tab) vm.clearMessage(); tab = it }
        }
        !started -> MirrorScreen(vm, onStart = { started = true; kept.prefs.onboarded = true }, connectedWallet = null)
        else -> SetupScreen(vm, s)
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val c = Kept.colors
    Column(Modifier.fillMaxWidth().background(c.ink).navigationBarsPadding()) {
        Hairline()
        Row(Modifier.fillMaxWidth()) {
            Tab.entries.forEach { t ->
                val on = t == selected
                Column(Modifier.weight(1f).clickable { onSelect(t) }.padding(top = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.label, style = MaterialTheme.typography.labelLarge, color = if (on) c.bone else c.dim)
                    Box(Modifier.padding(top = 8.dp).height(2.dp).fillMaxWidth(0.3f).background(if (on) c.brass else c.ink))
                }
            }
        }
    }
}
