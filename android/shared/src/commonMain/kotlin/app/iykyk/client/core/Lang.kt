package app.iykyk.client.core

import java.util.Locale

/**
 * The app speaks Russian, and English when the system (or, on Android 13+, the
 * per-app setting) asks for English. Any other language gets Russian, like the
 * string resources.
 */
object Lang {
    val english: Boolean get() = Locale.getDefault().language == "en"

    /** Russian or English text for the few places outside string resources. */
    fun t(ru: String, en: String): String = if (english) en else ru

    fun bytes(bytes: Long): String {
        val units = if (english) listOf("B", "KB", "MB", "GB", "TB") else listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val locale = if (english) Locale.US else Locale.forLanguageTag("ru")
        return if (unit == 0) "$bytes ${units[0]}" else String.format(locale, "%.1f %s", value, units[unit])
    }

    fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond.coerceAtLeast(0)) + t("/с", "/s")
}
