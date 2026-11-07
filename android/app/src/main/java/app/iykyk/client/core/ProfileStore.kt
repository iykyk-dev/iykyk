package app.iykyk.client.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.iykyk.core.iykyk.DeviceInfo
import app.iykyk.core.iykyk.Iykyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Servers come either from a subscription URL (the normal case) or from a single
 * pasted share link. Parsing happens in the Go core so iOS gets the same behavior.
 */
object ProfileStore {
    private const val PREFS = "profiles"
    private const val KEY_LINK = "link"
    private const val KEY_SUB_URL = "sub_url"
    private const val KEY_SUB_JSON = "sub_json"
    private const val KEY_SUB_UPDATED = "sub_updated"
    private const val KEY_SELECTED = "selected"
    private const val KEY_HWID = "hwid"

    private val _profiles = MutableStateFlow(Profiles())
    val profiles: StateFlow<Profiles> = _profiles.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val prefs = prefs(context)
        val selected = prefs.getInt(KEY_SELECTED, 0)
        val rawUrl = prefs.getString(KEY_SUB_URL, null)
        val rawJson = prefs.getString(KEY_SUB_JSON, null)
        val subUrl = SecretBox.decrypt(rawUrl)
        val subJson = SecretBox.decrypt(rawJson)
        // Saved by a version before encryption: encrypt it now.
        if (subUrl != null && subJson != null && !SecretBox.isEncrypted(rawJson)) {
            prefs.edit()
                .putString(KEY_SUB_URL, SecretBox.encrypt(subUrl))
                .putString(KEY_SUB_JSON, SecretBox.encrypt(subJson))
                .apply()
        }
        _profiles.value = when {
            subUrl != null && subJson != null -> runCatching {
                parse(subUrl, subJson, prefs.getLong(KEY_SUB_UPDATED, 0)).copy(selected = selected)
            }.getOrDefault(Profiles())
            else -> SecretBox.decrypt(prefs.getString(KEY_LINK, null))
                ?.let { link -> runCatching { Profiles(servers = listOf(describe(link))) }.getOrNull() }
                ?: Profiles()
        }
    }

    /** Current state for the VPN service (loads from storage if the app process is fresh). */
    fun snapshot(context: Context): Profiles {
        if (_profiles.value.servers.isEmpty()) load(context)
        return _profiles.value
    }

    /**
     * Imports whatever the user pasted: a subscription URL, a base64 subscription,
     * several links, or a single share link. Returns a short description for a toast.
     */
    suspend fun import(context: Context, text: String): String = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        val isUrl = trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
        // The official build works only with the provider's own subscriptions.
        if (locked && !isUrl) throw NotAllowedException()
        when {
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true) -> {
                val profiles = downloadSubscription(context, trimmed)
                profiles.subscription?.title?.takeIf { it.isNotBlank() }
                    ?: "${profiles.servers.size}"
            }
            // A WireGuard/AmneziaWG .conf file, or a single share link.
            trimmed.contains("[Interface]") ||
                trimmed.lines().count { it.contains("://") } == 1 -> {
                val server = describe(trimmed)
                prefs(context).edit()
                    .clear()
                    .putString(KEY_HWID, hwid(context))
                    .putString(KEY_LINK, SecretBox.encrypt(trimmed))
                    .apply()
                _profiles.value = Profiles(servers = listOf(server))
                server.name
            }
            else -> {
                // Several links or a base64 blob pasted as text.
                val json = Iykyk.parseSubscriptionText(trimmed)
                val profiles = parse("", json, System.currentTimeMillis())
                _profiles.value = profiles.copy(subscription = null)
                saveSubscription(context, "", json)
                "${profiles.servers.size}"
            }
        }
    }

    /** True in the official build: only the provider's subscription URLs are accepted. */
    val locked: Boolean by lazy { runCatching { Iykyk.isLocked() }.getOrDefault(false) }

    /** A link or subscription from someone else's service, refused by a locked build. */
    class NotAllowedException : Exception("not allowed")

    /** Re-downloads the current subscription, keeping the selected server if it still exists. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val url = _profiles.value.subscription?.url?.takeIf { it.isNotEmpty() }
            ?: error("no subscription to refresh")
        downloadSubscription(context, url)
    }

    /** Forgets the subscription and servers. The device ID stays, so adding it back does not take another device slot. */
    fun clear(context: Context) {
        val hwid = hwid(context)
        prefs(context).edit().clear().putString(KEY_HWID, hwid).apply()
        _profiles.value = Profiles()
    }

    fun select(context: Context, index: Int) {
        val current = _profiles.value
        if (index !in current.servers.indices) return
        prefs(context).edit().putInt(KEY_SELECTED, index).apply()
        _profiles.value = current.copy(selected = index)
    }

    private fun downloadSubscription(context: Context, url: String): Profiles {
        val device = DeviceInfo().apply {
            hwid = hwid(context)
            os = "Android"
            osVersion = Build.VERSION.RELEASE
            model = "${Build.MANUFACTURER} ${Build.MODEL}"
        }
        // Keep the fallbacks we already know: they matter exactly when the main URL fails.
        val fallbacks = org.json.JSONArray(_profiles.value.subscription?.fallbacks.orEmpty()).toString()
        val json = try {
            Iykyk.fetchSubscriptionWithFallbacks(url, fallbacks, userAgent(context), device)
        } catch (e: Exception) {
            if (e.message.orEmpty().startsWith("subscription not allowed")) throw NotAllowedException()
            throw e
        }
        val previous = _profiles.value
        val now = System.currentTimeMillis()
        var profiles = parse(url, json, now)
        val keep = profiles.servers.indexOfFirst { it.name == previous.current?.name }
        profiles = profiles.copy(selected = if (keep >= 0) keep else 0)
        saveSubscription(context, url, json, profiles.selected, now)
        _profiles.value = profiles
        return profiles
    }

    /**
     * Refreshes the subscription in the background when it is older than the panel's
     * update interval (Profile-Update-Interval), or 12 hours by default.
     */
    suspend fun refreshIfStale(context: Context): Boolean {
        val sub = _profiles.value.subscription ?: return false
        if (sub.url.isEmpty()) return false
        val hours = sub.updateHours.takeIf { it > 0 } ?: 12
        if (System.currentTimeMillis() - sub.updatedAt < hours * 3_600_000L) return false
        return runCatching { refresh(context) }.isSuccess
    }

    private fun saveSubscription(
        context: Context,
        url: String,
        json: String,
        selected: Int = 0,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit()
            .remove(KEY_LINK)
            .putString(KEY_SUB_URL, SecretBox.encrypt(url))
            .putString(KEY_SUB_JSON, SecretBox.encrypt(json))
            .putLong(KEY_SUB_UPDATED, updatedAt)
            .putInt(KEY_SELECTED, selected)
            .apply()
    }

    private fun parse(url: String, json: String, updatedAt: Long): Profiles {
        val root = JSONObject(json)
        val list = root.optJSONArray("servers")
        val servers = (0 until (list?.length() ?: 0)).map { i ->
            val item = list!!.getJSONObject(i)
            ServerInfo(
                name = item.optString("name"),
                protocol = item.optString("protocol"),
                address = item.optString("address"),
                link = item.optString("link"),
                details = item.optString("details"),
            )
        }
        val subscription = SubscriptionInfo(
            url = url,
            title = root.optString("title"),
            upload = root.optLong("upload"),
            download = root.optLong("download"),
            total = root.optLong("total"),
            expire = root.optLong("expire"),
            supportUrl = root.optString("supportUrl"),
            webPageUrl = root.optString("webPageUrl"),
            announce = root.optString("announce"),
            updatedAt = updatedAt,
            updateHours = root.optInt("updateHours"),
            fallbacks = root.optJSONArray("fallbacks")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }.orEmpty(),
            routing = root.optJSONObject("routing")?.toString().orEmpty(),
            hwidLimited = root.optBoolean("hwidLimited"),
            hwidLimitReached = root.optBoolean("hwidLimitReached"),
            devicesApi = root.optString("devicesApi"),
        )
        return Profiles(subscription = subscription.takeIf { url.isNotEmpty() }, servers = servers)
    }

    private fun describe(link: String): ServerInfo {
        val profile = Iykyk.describeLink(link)
        return ServerInfo(profile.name, profile.protocol, profile.address, link, profile.details)
    }

    /** This install's device ID as the panel sees it (x-hwid). */
    fun deviceId(context: Context): String = hwid(context)

    /** A random per-install device ID for panels with a device limit (Remnawave HWID). */
    private fun hwid(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_HWID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_HWID, id).apply()
        return id
    }

    private fun userAgent(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"
        return "IYKYK/$version (Android ${Build.VERSION.RELEASE})"
    }
}
