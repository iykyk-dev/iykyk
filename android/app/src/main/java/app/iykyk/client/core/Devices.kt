package app.iykyk.client.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DeviceInfo(
    val id: String,
    val platform: String,
    val os: String,
    val model: String,
    val app: String,
    val added: String,
    val lastSeen: String,
    val current: Boolean,
)

/** Devices on the subscription: [used] of [limit] (0 = unlimited). */
data class DevicesInfo(val used: Int, val limit: Int, val devices: List<DeviceInfo>)

/**
 * Asks the provider's small devices service (server/devices in this repo,
 * next to the Remnawave panel) which devices use the subscription, and removes
 * one to free a slot. The admin token stays on the server; the app sends only
 * the subscription's short UUID, which already grants access to the subscription.
 * Returns null when the provider does not run the service.
 */
object Devices {
    suspend fun load(context: Context, sub: SubscriptionInfo): DevicesInfo? =
        request(context, sub, "devices", null)

    suspend fun delete(context: Context, sub: SubscriptionInfo, id: String): DevicesInfo? =
        request(context, sub, "devices/delete", JSONObject().put("id", id).toString())

    private suspend fun request(context: Context, sub: SubscriptionInfo, path: String, body: String?): DevicesInfo? =
        withContext(Dispatchers.IO) {
            val base = apiBase(sub) ?: return@withContext null
            val shortUuid = Uri.parse(sub.url).lastPathSegment ?: return@withContext null
            val connection = URL("$base/$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("X-Subscription", shortUuid)
                connection.setRequestProperty("X-Hwid", ProfileStore.deviceId(context))
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                when (connection.responseCode) {
                    200 -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                    404 -> if (body == null) null else error("device not found")
                    else -> error("HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }

    /** The Iykyk-Devices-Api header, else /iykyk/api on the subscription's own host. */
    private fun apiBase(sub: SubscriptionInfo): String? {
        sub.devicesApi.takeIf { it.startsWith("https://") }?.let { return it.trimEnd('/') }
        val uri = Uri.parse(sub.url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        return "https://${uri.host}/iykyk/api"
    }

    private fun parse(text: String): DevicesInfo {
        val root = JSONObject(text)
        val list = root.optJSONArray("devices")
        val devices = (0 until (list?.length() ?: 0)).map { i ->
            val d = list!!.getJSONObject(i)
            DeviceInfo(
                id = d.optString("id"),
                platform = d.optString("platform"),
                os = d.optString("os"),
                model = d.optString("model"),
                app = d.optString("app"),
                added = d.optString("added"),
                lastSeen = d.optString("lastSeen"),
                current = d.optBoolean("current"),
            )
        }
        return DevicesInfo(root.optInt("used", devices.size), root.optInt("limit"), devices)
    }
}
