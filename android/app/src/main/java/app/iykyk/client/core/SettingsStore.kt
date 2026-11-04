package app.iykyk.client.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Which apps go through the VPN. */
enum class AppMode { ALL, ONLY_SELECTED, EXCEPT_SELECTED }

data class AppSettings(
    /** Russian sites and IPs go directly, not through the VPN. On by default: many of them block foreign IPs. */
    val bypassRussia: Boolean = true,
    val appMode: AppMode = AppMode.ALL,
    val apps: Set<String> = emptySet(),
    /** Split TLS handshakes to get past DPI that blocks by server name. */
    val fragment: Boolean = false,
    /** Sort the server list by the last ping. */
    val sortByPing: Boolean = false,
    /** Connect when the app is opened. */
    val autoConnectOnLaunch: Boolean = false,
    /** Connect when the phone starts. */
    val autoConnectOnBoot: Boolean = false,
    /** Remind before the subscription expires. */
    val expiryReminders: Boolean = true,
)

object SettingsStore {
    private const val PREFS = "settings"
    private const val KEY_BYPASS = "bypass_russia"
    private const val KEY_APP_MODE = "app_mode"
    private const val KEY_APPS = "apps"
    private const val KEY_FRAGMENT = "fragment"
    private const val KEY_SORT_BY_PING = "sort_by_ping"
    private const val KEY_AUTO_LAUNCH = "auto_connect_launch"
    private const val KEY_AUTO_BOOT = "auto_connect_boot"
    private const val KEY_EXPIRY_REMINDERS = "expiry_reminders"

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = AppSettings(
            bypassRussia = prefs.getBoolean(KEY_BYPASS, true),
            appMode = runCatching { AppMode.valueOf(prefs.getString(KEY_APP_MODE, null)!!) }
                .getOrDefault(AppMode.ALL),
            apps = prefs.getStringSet(KEY_APPS, emptySet()).orEmpty().toSet(),
            fragment = prefs.getBoolean(KEY_FRAGMENT, false),
            sortByPing = prefs.getBoolean(KEY_SORT_BY_PING, false),
            autoConnectOnLaunch = prefs.getBoolean(KEY_AUTO_LAUNCH, false),
            autoConnectOnBoot = prefs.getBoolean(KEY_AUTO_BOOT, false),
            expiryReminders = prefs.getBoolean(KEY_EXPIRY_REMINDERS, true),
        )
    }

    fun update(context: Context, transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BYPASS, updated.bypassRussia)
            .putString(KEY_APP_MODE, updated.appMode.name)
            .putStringSet(KEY_APPS, updated.apps)
            .putBoolean(KEY_FRAGMENT, updated.fragment)
            .putBoolean(KEY_SORT_BY_PING, updated.sortByPing)
            .putBoolean(KEY_AUTO_LAUNCH, updated.autoConnectOnLaunch)
            .putBoolean(KEY_AUTO_BOOT, updated.autoConnectOnBoot)
            .putBoolean(KEY_EXPIRY_REMINDERS, updated.expiryReminders)
            .apply()
    }

    /** Settings for the Go core, as JSON (see options in core/iykyk/options.go). */
    fun coreOptions(context: Context): String = JSONObject()
        .put("bypassRussia", _settings.value.bypassRussia)
        .put("ruleSetDir", RuleSets.prepare(context))
        .put("fragment", _settings.value.fragment)
        .toString()
}
