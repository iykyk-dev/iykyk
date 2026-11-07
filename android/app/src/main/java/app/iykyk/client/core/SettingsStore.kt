package app.iykyk.client.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

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
    private const val KEY_REMOTE_DNS = "remote_dns"
    private const val KEY_DIRECT_DNS = "direct_dns"
    private const val KEY_THEME = "theme"
    private const val KEY_PREFER_XRAY = "prefer_xray"

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
            remoteDns = prefs.getString(KEY_REMOTE_DNS, null) ?: "cloudflare",
            directDns = prefs.getString(KEY_DIRECT_DNS, null) ?: "yandex",
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }
                .getOrDefault(ThemeMode.SYSTEM),
            preferXray = prefs.getBoolean(KEY_PREFER_XRAY, false),
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
            .putString(KEY_REMOTE_DNS, updated.remoteDns)
            .putString(KEY_DIRECT_DNS, updated.directDns)
            .putString(KEY_THEME, updated.themeMode.name)
            .putBoolean(KEY_PREFER_XRAY, updated.preferXray)
            .apply()
    }

    /** Settings for the Go core, as JSON (see options in core/iykyk/options.go). */
    /** [fragment] and [preferXray] override the settings (the "Не работает?" checks try both). */
    fun coreOptions(
        context: Context,
        fragment: Boolean = _settings.value.fragment,
        preferXray: Boolean = _settings.value.preferXray,
    ): String = JSONObject()
        .put("bypassRussia", _settings.value.bypassRussia)
        .put("ruleSetDir", RuleSets.prepare(context))
        .put("fragment", fragment)
        // DNS is not a user setting any more: the defaults suit everyone.
        .put("remoteDNS", "cloudflare")
        .put("directDNS", "yandex")
        .put("preferXray", preferXray)
        .put("logDir", AppLog.dir(context).absolutePath)
        .apply {
            // The provider's routing from the subscription (Iykyk-Routing header).
            ProfileStore.profiles.value.subscription?.routing
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { put("routing", JSONObject(it)) } }
        }
        .toString()
}
