package app.iykyk.client.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Asks the provider's small devices service (server/devices in this repo,
 * next to the Remnawave panel) which devices use the subscription, and removes
 * one to free a slot. The admin token stays on the server; the app sends only
 * the subscription's short UUID, which already grants access to the subscription.
 * Throws when the provider does not run the service; callers show no row then. Shared by Android
 * and Windows; [hwid] is this install's device ID.
 */
object DevicesApi {
    suspend fun load(sub: SubscriptionInfo, hwid: String): DevicesInfo? =
        request(sub, hwid, "devices", null)

    suspend fun delete(sub: SubscriptionInfo, hwid: String, id: String): DevicesInfo? =
        request(sub, hwid, "devices/delete", JSONObject().put("id", id).toString())

    private suspend fun request(sub: SubscriptionInfo, hwid: String, path: String, body: String?): DevicesInfo? =
        withContext(Dispatchers.IO) {
            val base = apiBase(sub) ?: return@withContext null
            val shortUuid = runCatching { URI(sub.url).path.trimEnd('/').substringAfterLast('/') }
                .getOrNull()?.ifBlank { null } ?: return@withContext null
            val connection = URL("$base/$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("X-Subscription", shortUuid)
                connection.setRequestProperty("X-Hwid", hwid)
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                when (connection.responseCode) {
                    200 -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                    // The caller shows no row on failure; the message goes to the log.
                    404 -> error(if (body == null) "HTTP 404 (no service or subscription not found)" else "device not found")
                    else -> error("HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }

    /** The Iykyk-Devices-Api header, else /iykyk/api on the subscription's own host. */
    private fun apiBase(sub: SubscriptionInfo): String? {
        sub.devicesApi.takeIf { it.startsWith("https://") }?.let { return it.trimEnd('/') }
        val uri = runCatching { URI(sub.url) }.getOrNull() ?: return null
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
