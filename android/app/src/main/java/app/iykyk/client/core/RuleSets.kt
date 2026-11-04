package app.iykyk.client.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Russian domain/IP lists for the bypass option ship inside the APK (assets/rulesets)
 * and are copied to app storage, where sing-box can read them.
 */
object RuleSets {
    private const val TAG = "IykykRuleSets"
    private const val ASSET_DIR = "rulesets"
    private val FILES = listOf("geosite-category-ru.srs", "geoip-ru.srs")

    /** Returns the directory with the rule sets, or "" if the APK has none (local builds). */
    @Synchronized
    fun prepare(context: Context): String {
        val app = context.applicationContext
        val dir = File(app.filesDir, ASSET_DIR)
        val stamp = File(dir, ".version")
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")
        val ready = FILES.all { File(dir, it).isFile }
        if (ready && stamp.isFile && stamp.readText() == version) return dir.path

        return try {
            dir.mkdirs()
            for (name in FILES) {
                app.assets.open("$ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }
            stamp.writeText(version)
            dir.path
        } catch (e: Exception) {
            Log.w(TAG, "rule sets not available, bypassing only .ru/.su/.рф", e)
            ""
        }
    }
}
