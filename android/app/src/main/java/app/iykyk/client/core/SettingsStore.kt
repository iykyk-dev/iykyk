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
)

object SettingsStore {
    private const val PREFS = "settings"
    private const val KEY_BYPASS = "bypass_russia"
    private const val KEY_APP_MODE = "app_mode"
    private const val KEY_APPS = "apps"

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = AppSettings(
            bypassRussia = prefs.getBoolean(KEY_BYPASS, true),
            appMode = runCatching { AppMode.valueOf(prefs.getString(KEY_APP_MODE, null)!!) }
                .getOrDefault(AppMode.ALL),
            apps = prefs.getStringSet(KEY_APPS, emptySet()).orEmpty().toSet(),
        )
    }

    fun update(context: Context, transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BYPASS, updated.bypassRussia)
            .putString(KEY_APP_MODE, updated.appMode.name)
            .putStringSet(KEY_APPS, updated.apps)
            .apply()
    }

    /** Settings for the Go core, as JSON (see options in core/iykyk/auto.go). */
    fun coreOptions(context: Context): String = JSONObject()
        .put("bypassRussia", _settings.value.bypassRussia)
        .put("ruleSetDir", RuleSets.prepare(context))
        .toString()
}
