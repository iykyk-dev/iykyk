package app.iykyk.client.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import app.iykyk.core.libbox.Libbox
import app.iykyk.core.libbox.SetupOptions

/** One-time initialization of the Go core (sing-box libbox + Xray). */
object Core {
    private const val TAG = "IykykCore"

    @Volatile
    private var initialized = false

    fun setup(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val workingDir = app.getExternalFilesDir(null) ?: app.filesDir
            val options = SetupOptions().apply {
                basePath = app.filesDir.path
                workingPath = workingDir.path
                tempPath = app.cacheDir.path
                logMaxLines = 3000
                debug = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                crashReportSource = "Application"
            }
            try {
                Libbox.setup(options)
                initialized = true
            } catch (e: Exception) {
                Log.e(TAG, "core setup failed", e)
            }
        }
    }
}
