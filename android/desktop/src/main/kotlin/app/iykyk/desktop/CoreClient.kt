package app.iykyk.desktop

import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The core service (iykyk-service.exe) said no, or is not running. */
class CoreException(message: String) : Exception(message)

/**
 * Talks to iykyk-service.exe on 127.0.0.1:47231. The token is in
 * %ProgramData%\IYKYK\api-token, written by the service when it starts.
 */
class CoreClient {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    val dataDir: File = File(System.getenv("ProgramData") ?: "C:\\ProgramData", "IYKYK")

    private fun token(): String =
        runCatching { File(dataDir, "api-token").readText().trim() }.getOrDefault("")

    fun get(path: String): JSONObject = JSONObject(call(path, null, 5))

    fun post(path: String, body: JSONObject = JSONObject(), timeoutSeconds: Long = 40): String =
        call(path, body, timeoutSeconds)

    private fun call(path: String, body: JSONObject?, timeoutSeconds: Long): String {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:47231$path"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer ${token()}")
        val request = if (body == null) {
            builder.GET().build()
        } else {
            builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        }
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: ConnectException) {
            throw CoreException(SERVICE_NOT_RUNNING)
        }
        if (response.statusCode() == 200) return response.body()
        val message = runCatching { JSONObject(response.body()).optString("error") }.getOrNull()
        throw CoreException(message?.ifBlank { null } ?: "HTTP ${response.statusCode()}")
    }

    companion object {
        const val SERVICE_NOT_RUNNING =
            "служба IYKYK не запущена. Перезагрузите компьютер или переустановите приложение"
    }
}
