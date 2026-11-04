package app.iykyk.client.core

import android.content.Context
import android.content.SharedPreferences
import app.iykyk.core.iykyk.Iykyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerInfo(
    val name: String,
    val protocol: String,
    val address: String,
)

/**
 * Stores the selected server as a share link.
 * Temporary until subscriptions (step 4) replace manual links.
 */
object ProfileStore {
    private const val PREFS = "profiles"
    private const val KEY_LINK = "link"

    private val _current = MutableStateFlow<ServerInfo?>(null)
    val current: StateFlow<ServerInfo?> = _current.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val link = link(context) ?: return
        _current.value = runCatching { describe(link) }.getOrNull()
    }

    fun link(context: Context): String? = prefs(context).getString(KEY_LINK, null)

    /** Validates and saves a link. Throws with a readable message if it is not supported. */
    fun save(context: Context, link: String): ServerInfo {
        val trimmed = link.trim()
        val info = describe(trimmed)
        prefs(context).edit().putString(KEY_LINK, trimmed).apply()
        _current.value = info
        return info
    }

    private fun describe(link: String): ServerInfo {
        val profile = Iykyk.describeLink(link)
        return ServerInfo(
            name = profile.name,
            protocol = profile.protocol,
            address = profile.address,
        )
    }
}
