package app.iykyk.client.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-disk journal of what the app did (connect, checks, reconnects,
 * errors) for "Send a report to support". The cores write their own warnings
 * to singbox.log and xray.log in the same folder.
 */
object AppLog {
    private const val MAX_BYTES = 256 * 1024L
    private const val CORE_LOG_MAX_BYTES = 1024 * 1024L
    private var file: File? = null
    private val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }

    fun init(context: Context) {
        val log = File(dir(context), "app.log")
        if (log.length() > MAX_BYTES) {
            runCatching { log.writeText(tail(log, MAX_BYTES / 2)) }
        }
        file = log
    }

    @Synchronized
    fun i(message: String) {
        Log.i("IYKYK", message)
        val log = file ?: return
        runCatching { log.appendText("${time.format(Date())} $message\n") }
    }

    /** Starts the cores' logs afresh for a new connection. */
    fun resetCoreLogs(context: Context) {
        for (name in listOf("singbox.log", "xray.log")) File(dir(context), name).delete()
    }

    /** Keeps the cores' logs from growing without bound in long sessions (they append). */
    fun trimCoreLogs(context: Context) {
        for (name in listOf("singbox.log", "xray.log")) {
            val log = File(dir(context), name)
            if (log.length() > CORE_LOG_MAX_BYTES) runCatching { RandomAccessFile(log, "rw").use { it.setLength(0) } }
        }
    }

    /** The last [maxBytes] of a file, starting at a line boundary. */
    fun tail(file: File, maxBytes: Long = 64 * 1024L): String {
        if (!file.exists()) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - maxBytes).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                if (start > 0) text.substringAfter('\n') else text
            }
        }.getOrDefault("")
    }
}
