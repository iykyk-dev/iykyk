package app.iykyk.desktop

import app.iykyk.client.core.UPDATE_MANIFEST_MIRROR
import app.iykyk.client.core.UPDATE_MANIFEST_URL
import app.iykyk.client.core.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties

/**
 * Updates on Windows: reads the same version.json as Android, downloads IYKYK-Setup.exe,
 * checks its SHA-256 and runs it silently. Windows asks once for administrator rights
 * (the installer replaces the VPN service); the new version starts by itself and
 * reconnects if the VPN was on.
 */
class DesktopUpdater {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** "1.0.0" in an installed copy, null when run from the IDE. */
    val currentVersion: String? = System.getProperty("iykyk.version")?.takeIf { it != "dev" && it.isNotBlank() }
    val enabled: Boolean get() = currentVersion != null

    private data class Release(val version: String, val notes: String, val url: String, val sha256: String)

    @Volatile private var available: Release? = null

    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val stateFile = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "IYKYK/update.properties")
    private val props = Properties().apply { runCatching { stateFile.inputStream().use { load(it) } } }
    private fun save() = runCatching {
        stateFile.parentFile.mkdirs()
        stateFile.outputStream().use { props.store(it, "IYKYK updates") }
    }

    /** True when a newer version was found (for a tray notification). */
    suspend fun check(manual: Boolean): Boolean = withContext(Dispatchers.IO) {
        val current = currentVersion ?: return@withContext false
        if (manual) state.value = UpdateState.Checking
        try {
            val text = runCatching { get(UPDATE_MANIFEST_URL) }.getOrElse { get(UPDATE_MANIFEST_MIRROR) }
            val root = JSONObject(text)
            val windows = root.optJSONObject("windows")
            val version = root.getString("version")
            if (windows != null && versionCode(version) > versionCode(current)) {
                val release = Release(version, root.optString("notes"), windows.getString("url"), windows.getString("sha256"))
                available = release
                state.value = UpdateState.Available(release.version, release.notes)
                true
            } else {
                available = null
                state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                false
            }
        } catch (e: Exception) {
            state.value = if (manual) UpdateState.Failed(e.message ?: e.javaClass.simpleName) else UpdateState.Idle
            false
        }
    }

    /**
     * Downloads and starts the installer. Returns true when the installer is running
     * and the app should quit so its files can be replaced.
     */
    suspend fun install(vpnWasOn: Boolean): Boolean = withContext(Dispatchers.IO) {
        val release = available ?: return@withContext false
        try {
            state.value = UpdateState.Downloading(release.version, 0f)
            val file = File(System.getProperty("java.io.tmpdir"), "IYKYK-Setup-${release.version}.exe")
            download(release, file)
            props.setProperty("reconnect", vpnWasOn.toString())
            props.setProperty("notes_version", release.version)
            props.setProperty("notes", release.notes)
            save()
            state.value = UpdateState.Downloading(release.version, null)
            // Start-Process -Verb RunAs shows the UAC prompt; it fails if the user says no.
            val command = "Start-Process -FilePath '${file.absolutePath.replace("'", "''")}' " +
                "-ArgumentList '/SILENT','/SUPPRESSMSGBOXES','/NORESTART','/CLOSEAPPLICATIONS' -Verb RunAs"
            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                props.remove("reconnect")
                save()
                error(if (output.contains("canceled", ignoreCase = true) || output.contains("отмен", ignoreCase = true)) "установка отменена" else output.trim().take(200))
            }
            true
        } catch (e: Exception) {
            state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** After a failure was shown: back to "available" so the user can retry. */
    fun acknowledgeFailure() {
        val release = available
        state.value = if (release != null) UpdateState.Available(release.version, release.notes) else UpdateState.Idle
    }

    /** Right after an update: whether to reconnect, and the notes to show once. */
    fun takeReconnect(): Boolean {
        val reconnect = props.getProperty("reconnect") == "true"
        if (props.remove("reconnect") != null) save()
        return reconnect
    }

    fun pendingNotes(): Pair<String, String>? {
        val version = props.getProperty("notes_version") ?: return null
        if (version != currentVersion) return null
        val notes = props.getProperty("notes").orEmpty()
        return if (notes.isBlank()) null else version to notes
    }

    fun notesSeen() {
        props.remove("notes_version")
        props.remove("notes")
        save()
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "IYKYK/${currentVersion ?: "dev"} (Windows)")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun download(release: Release, file: File) {
        val request = HttpRequest.newBuilder(URI(release.url)).timeout(Duration.ofMinutes(10)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
        val total = response.headers().firstValueAsLong("content-length").orElse(-1)
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var shown = 0f
        response.body().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    done += read
                    if (total > 0) {
                        val progress = done.toFloat() / total
                        if (progress - shown >= 0.02f) {
                            shown = progress
                            state.value = UpdateState.Downloading(release.version, progress)
                        }
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            error("файл повредился при скачивании, попробуйте ещё раз")
        }
    }

    companion object {
        /** Same formula as Android (android/app/build.gradle.kts). */
        fun versionCode(version: String): Long {
            val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-beta(\d+))?""").matchEntire(version) ?: return 0
            val (major, minor, patch, beta) = match.destructured
            val build = if (beta.isEmpty()) 99 else beta.toInt().coerceIn(1, 98)
            return major.toLong() * 1_000_000 + minor.toLong() * 10_000 + patch.toLong() * 100 + build
        }
    }
}
