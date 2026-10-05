package app.kept

import android.content.Context
import app.kept.reflex.Rules
import org.sol4k.PublicKey

/** Small settings that survive restarts. Nothing secret lives here: the trading key is sealed separately. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("kept", Context.MODE_PRIVATE)

    /** The main wallet that owns the vault (Seed Vault on Seeker, or any Mobile Wallet Adapter wallet). */
    var mainWallet: String?
        get() = p.getString("main_wallet", null)
        set(v) = p.edit().putString("main_wallet", v).apply()

    /** Reused so the wallet app doesn't ask the user to connect on every action. */
    var walletAuthToken: String?
        get() = p.getString("wallet_auth", null)
        set(v) = p.edit().putString("wallet_auth", v).apply()

    var bankBps: Int
        get() = p.getInt("bank_bps", 5000)
        set(v) = p.edit().putInt("bank_bps", v).apply()

    /** "sol" or "skr": what a win is banked as. SKR needs the holder tier. */
    var bankAsset: String
        get() = p.getString("bank_asset", "sol") ?: "sol"
        set(v) = p.edit().putString("bank_asset", v).apply()

    var cooldownSecs: Long
        get() = p.getLong("cooldown", 86_400L)
        set(v) = p.edit().putLong("cooldown", v).apply()

    /** True once the vault exists on chain and the reflex has been switched on. */
    var vaultReady: Boolean
        get() = p.getBoolean("vault_ready", false)
        set(v) = p.edit().putBoolean("vault_ready", v).apply()

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    fun rules(): Rules? {
        val main = mainWallet ?: return null
        if (!vaultReady) return null
        return Rules(PublicKey(main), bankBps, if (bankAsset == "skr") app.kept.reflex.BankAsset.StakedSkr else app.kept.reflex.BankAsset.Sol)
    }

    fun clear() = p.edit().clear().apply()
}
