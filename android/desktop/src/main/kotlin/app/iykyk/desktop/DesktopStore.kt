package app.iykyk.desktop

import app.iykyk.client.core.AppSettings
import app.iykyk.client.core.Profiles
import app.iykyk.client.core.ServerInfo
import app.iykyk.client.core.SubscriptionInfo
import app.iykyk.client.core.ThemeMode
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.UUID

/** Settings and the subscription in %APPDATA%\IYKYK (per Windows user). */
class DesktopStore {
    private val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "IYKYK").apply { mkdirs() }
    private val stateFile = File(dir, "state.properties")
    private val subscriptionFile = File(dir, "subscription.json")
    private val props = Properties().apply {
        runCatching { stateFile.inputStream().use { load(it) } }
    }

    private fun save() = runCatching { stateFile.outputStream().use { props.store(it, "IYKYK") } }

    val hwid: String
        get() = props.getProperty("hwid") ?: UUID.randomUUID().toString().also {
            props.setProperty("hwid", it)
            save()
        }

    fun loadSettings(): AppSettings = AppSettings(
        bypassRussia = props.getProperty("bypassRussia", "true").toBoolean(),
        fragment = props.getProperty("fragment", "false").toBoolean(),
        preferXray = props.getProperty("preferXray", "false").toBoolean(),
        sortByPing = props.getProperty("sortByPing", "false").toBoolean(),
        autoConnectOnLaunch = props.getProperty("autoConnectOnLaunch", "false").toBoolean(),
        themeMode = runCatching { ThemeMode.valueOf(props.getProperty("themeMode")) }.getOrDefault(ThemeMode.SYSTEM),
    )

    fun saveSettings(settings: AppSettings) {
        props.setProperty("bypassRussia", settings.bypassRussia.toString())
        props.setProperty("fragment", settings.fragment.toString())
        props.setProperty("preferXray", settings.preferXray.toString())
        props.setProperty("sortByPing", settings.sortByPing.toString())
        props.setProperty("autoConnectOnLaunch", settings.autoConnectOnLaunch.toString())
        props.setProperty("themeMode", settings.themeMode.name)
        save()
    }

    fun loadProfiles(): Profiles {
        val url = props.getProperty("subscriptionUrl") ?: return Profiles()
        val json = runCatching { subscriptionFile.readText() }.getOrNull() ?: return Profiles()
        val updatedAt = props.getProperty("updatedAt")?.toLongOrNull() ?: 0
        val selected = props.getProperty("selected")?.toIntOrNull() ?: 0
        return runCatching { parse(url, json, updatedAt).copy(selected = selected) }.getOrDefault(Profiles())
    }

    fun saveSubscription(url: String, json: String, updatedAt: Long, selected: Int) {
        subscriptionFile.writeText(json)
        props.setProperty("subscriptionUrl", url)
        props.setProperty("updatedAt", updatedAt.toString())
        props.setProperty("selected", selected.toString())
        save()
    }

    fun saveSelected(index: Int) {
        props.setProperty("selected", index.toString())
        save()
    }

    fun clearSubscription() {
        subscriptionFile.delete()
        props.remove("subscriptionUrl")
        props.remove("updatedAt")
        props.remove("selected")
        save()
    }

    /** The core's subscription JSON; same format as on Android (ProfileStore.parse). */
    fun parse(url: String, json: String, updatedAt: Long): Profiles {
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
            fallbacks = root.optJSONArray("fallbacks")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty(),
            routing = root.optJSONObject("routing")?.toString().orEmpty(),
            hwidLimited = root.optBoolean("hwidLimited"),
            hwidLimitReached = root.optBoolean("hwidLimitReached"),
            devicesApi = root.optString("devicesApi"),
        )
        return Profiles(subscription = subscription, servers = servers)
    }
}
