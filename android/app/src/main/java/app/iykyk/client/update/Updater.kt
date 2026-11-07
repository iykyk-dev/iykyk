package app.iykyk.client.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import app.iykyk.client.BuildConfig
import app.iykyk.client.MainActivity
import app.iykyk.client.R
import app.iykyk.client.core.AppLog
import app.iykyk.client.core.UPDATE_MANIFEST_URL
import app.iykyk.client.core.UpdateState
import app.iykyk.client.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates for copies installed from the site or the bot (store copies update through
 * the store). The app reads version.json from the latest GitHub release, downloads the
 * APK for this phone's processor, checks its SHA-256 and that it is signed with the
 * same key as the installed app, then hands it to the system installer.
 *
 * Android itself also refuses an update signed with another key, so a tampered
 * mirror can at worst make the update fail, never replace the app.
 */
object Updater {
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED_CODE = "notified_code"
    private const val KEY_RECONNECT = "reconnect"
    private const val KEY_NOTES_VERSION = "notes_version"
    private const val KEY_NOTES = "notes"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 3

    private data class Release(
        val version: String,
        val code: Long,
        val notes: String,
        val url: String,
        val sha256: String,
    )

    /** Updates itself only in a release build distributed outside the stores. */
    val enabled: Boolean get() = BuildConfig.UPDATES_ENABLED

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile private var available: Release? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When the app opens: at most every 6 hours. */
    suspend fun checkIfDue(context: Context) {
        if (!enabled || _state.value is UpdateState.Downloading) return
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last < 6 * 3_600_000L && available == null) return
        check(context, manual = false)
    }

    /** "Check for updates" in Settings. */
    suspend fun checkNow(context: Context) {
        if (!enabled || _state.value is UpdateState.Downloading) return
        check(context, manual = true)
    }

    /** From the periodic worker: once a day, a notification per new version. */
    suspend fun checkInBackground(context: Context) {
        if (!enabled) return
        val prefs = prefs(context)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < 20 * 3_600_000L) return
        val release = check(context, manual = false) ?: return
        if (prefs.getLong(KEY_NOTIFIED_CODE, 0) == release.code) return
        prefs.edit().putLong(KEY_NOTIFIED_CODE, release.code).apply()
        notify(context, release)
    }

    private suspend fun check(context: Context, manual: Boolean): Release? = withContext(Dispatchers.IO) {
        if (manual) _state.value = UpdateState.Checking
        try {
            val release = parse(get(manifestUrl()))
            prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (release != null && release.code > installedCode(context)) {
                AppLog.i("update: ${release.version} is available")
                available = release
                _state.value = UpdateState.Available(release.version, release.notes)
                release
            } else {
                available = null
                _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                null
            }
        } catch (e: Exception) {
            AppLog.i("update: check failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = if (manual) UpdateState.Failed(e.message ?: e.javaClass.simpleName) else UpdateState.Idle
            null
        }
    }

    /**
     * "Update": downloads, verifies, opens the system installer. Returns false when the
     * user first has to allow IYKYK to install apps (the settings page is opened).
     */
    suspend fun install(context: Context): Boolean {
        val release = available ?: return true
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        _state.value = UpdateState.Downloading(release.version, 0f)
        try {
            val apk = withContext(Dispatchers.IO) { download(context, release) }
            withContext(Dispatchers.IO) { verifySignature(context, apk) }
            _state.value = UpdateState.Downloading(release.version, null)
            prefs(context).edit()
                .putBoolean(KEY_RECONNECT, VpnState.status.value.isActive)
                .putString(KEY_NOTES_VERSION, release.version)
                .putString(KEY_NOTES, release.notes)
                .apply()
            withContext(Dispatchers.IO) { commit(context, apk) }
            AppLog.i("update: installing ${release.version}")
        } catch (e: Exception) {
            AppLog.i("update: failed: ${e.javaClass.simpleName}: ${e.message}")
            failed(e.message ?: e.javaClass.simpleName)
        }
        return true
    }

    /** The system installer reported an error (or the user cancelled). */
    fun failed(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    /** After the failure was shown: back to the banner so the user can try again. */
    fun acknowledgeFailure() {
        val release = available
        _state.value = if (release != null) UpdateState.Available(release.version, release.notes) else UpdateState.Idle
    }

    /** After the app was replaced: reconnect if the VPN was on before the update. */
    fun consumeReconnect(context: Context): Boolean {
        val prefs = prefs(context)
        val reconnect = prefs.getBoolean(KEY_RECONNECT, false)
        if (reconnect) prefs.edit().remove(KEY_RECONNECT).apply()
        return reconnect
    }

    /** "What's new" once, right after updating to the version the notes belong to. */
    fun pendingNotes(context: Context): Pair<String, String>? {
        val prefs = prefs(context)
        val version = prefs.getString(KEY_NOTES_VERSION, null) ?: return null
        val notes = prefs.getString(KEY_NOTES, null).orEmpty()
        val installed = runCatching { packageInfo(context, 0).versionName }.getOrNull()
        if (installed != version || notes.isBlank()) return null
        return version to notes
    }

    fun notesSeen(context: Context) {
        prefs(context).edit().remove(KEY_NOTES_VERSION).remove(KEY_NOTES).apply()
    }

    /** SHA-256 of the signing certificate, "AB:CD:…", shown in Settings to compare with the site. */
    fun signatureFingerprint(context: Context): String = runCatching {
        certificates(packageInfo(context, signatureFlags())).firstOrNull()
            ?.chunked(2)?.joinToString(":")?.uppercase()
    }.getOrNull().orEmpty()

    // --- network ---

    /**
     * Normal copies read the latest release. A beta copy (1.1.0-beta2) also gets newer
     * betas: the newest release of any kind that carries a version.json.
     */
    private fun manifestUrl(): String {
        if (!BuildConfig.VERSION_NAME.contains("-beta")) return UPDATE_MANIFEST_URL
        val releases = org.json.JSONArray(get("https://api.github.com/repos/iykyk-dev/iykyk/releases?per_page=15"))
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || !release.optString("tag_name").startsWith("v")) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.optString("name") == "version.json") return asset.getString("browser_download_url")
            }
        }
        return UPDATE_MANIFEST_URL
    }

    private fun get(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "IYKYK/${BuildConfig.VERSION_NAME}")
        }

    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "IYKYK-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(release.url)
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var done = 0L
            var lastShown = 0f
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total != null) {
                            val progress = done.toFloat() / total
                            if (progress - lastShown >= 0.02f) {
                                lastShown = progress
                                _state.value = UpdateState.Downloading(release.version, progress)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            error(context.getString(R.string.update_bad_checksum))
        }
        return file
    }

    // --- verification and installation ---

    private fun verifySignature(context: Context, apk: File) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, signatureFlags())
            ?: error(context.getString(R.string.update_bad_file))
        if (archive.packageName != context.packageName) error(context.getString(R.string.update_bad_file))
        val theirs = certificates(archive)
        val mine = certificates(packageInfo(context, signatureFlags()))
        // Some Android versions do not report an archive's signers; the installer still checks.
        if (theirs.isNotEmpty() && mine.isNotEmpty() && theirs.toSet() != mine.toSet()) {
            apk.delete()
            error(context.getString(R.string.update_bad_signature))
        }
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // No confirmation when IYKYK installed itself before (Android 12+).
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java), flags,
            )
            session.commit(intent.intentSender)
        }
    }

    private fun installedCode(context: Context): Long = PackageInfoCompat.getLongVersionCode(packageInfo(context, 0))

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun packageInfo(context: Context, flags: Int): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, flags)

    /** SHA-256 (hex) of each signing certificate. */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        } ?: return emptyList()
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig -> sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
    }

    /**
     * version.json, written by CI:
     * {"version":"1.0.1","versionCode":1000199,"notes":"…",
     *  "android":{"arm64-v8a":{"url":"…","sha256":"…"},…,"universal":{…}}}
     */
    private fun parse(text: String): Release? {
        val root = JSONObject(text)
        val apks = root.optJSONObject("android") ?: return null
        val abi = Build.SUPPORTED_ABIS.firstOrNull { apks.has(it) } ?: "universal"
        val apk = apks.optJSONObject(abi) ?: return null
        return Release(
            version = root.getString("version"),
            code = root.getLong("versionCode"),
            notes = root.optString("notes"),
            url = apk.getString("url"),
            sha256 = apk.getString("sha256"),
        )
    }

    private fun notify(context: Context, release: Release) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.update_notification_title, release.version))
            .setContentText(context.getString(R.string.update_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
