package app.iykyk.client.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.iykyk.client.BuildConfig
import app.iykyk.client.R
import app.iykyk.client.vpn.VpnState
import app.iykyk.core.iykyk.Iykyk
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Send a report to support": device and app info, settings, the current server
 * and the recent logs in one text file, shared to Telegram (or anywhere).
 * Secrets are removed first: the subscription URL, server links, UUIDs and passwords.
 */
object Report {
    fun share(activity: Activity) {
        val file = build(activity)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.report_message))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.action_send_report)))
    }

    fun build(context: Context): File {
        val profiles = ProfileStore.profiles.value
        val settings = SettingsStore.settings.value
        val sub = profiles.subscription
        val server = profiles.current
        val logs = AppLog.dir(context)
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        val text = buildString {
            appendLine("Отчёт IYKYK, ${date.format(Date())}")
            appendLine("Приложение: $version (${BuildConfig.DISTRIBUTION})")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Ядра: ${runCatching { Iykyk.versions() }.getOrDefault("?")}")
            appendLine()
            appendLine("VPN: ${VpnState.status.value}, проверка: ${VpnState.tunnel.value ?: "-"}")
            VpnState.error.value?.let { appendLine("Ошибка: $it") }
            appendLine("Подписка: ${sub?.title ?: "-"}, серверов ${profiles.servers.size}, " +
                "обновлена ${sub?.updatedAt?.takeIf { it > 0 }?.let { date.format(Date(it)) } ?: "-"}, " +
                "до ${sub?.expire?.takeIf { it > 0 }?.let { date.format(Date(it * 1000)) } ?: "-"}")
            appendLine("Сервер: ${server?.name ?: "-"} | ${server?.details ?: server?.protocol ?: "-"} | ${server?.address ?: "-"}")
            appendLine(
                "Настройки: обход РФ=${settings.bypassRussia}, фрагментация=${settings.fragment}, " +
                    "через Xray=${settings.preferXray}, DNS=${settings.remoteDns}/${settings.directDns}, " +
                    "приложения=${settings.appMode} (${settings.apps.size})",
            )
            appendLine()
            appendLine("--- Журнал приложения ---")
            appendLine(AppLog.tail(File(logs, "app.log"), 32 * 1024))
            appendLine("--- sing-box ---")
            appendLine(AppLog.tail(File(logs, "singbox.log")))
            appendLine("--- Xray ---")
            appendLine(AppLog.tail(File(logs, "xray.log")))
        }

        val dir = File(context.cacheDir, "reports").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return File(dir, "iykyk-report-$stamp.txt").apply { writeText(redact(text, profiles)) }
    }

    private val uuid = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val secretParam = Regex("""(?i)\b(password|passwd|pass|pbk|token|auth|psk|private[_-]?key)(["'=: ]+)[^\s"',&]+""")
    private val subPath = Regex("""/api/sub/[A-Za-z0-9_-]+""")

    internal fun redact(text: String, profiles: Profiles): String {
        var result = text
        val secrets = buildList {
            profiles.subscription?.url?.takeIf { it.isNotBlank() }?.let { add(it) }
            profiles.subscription?.fallbacks?.let { addAll(it) }
            profiles.servers.mapNotNull { it.link.takeIf { link -> link.length > 10 } }.let { addAll(it) }
        }
        for (secret in secrets.sortedByDescending { it.length }) result = result.replace(secret, "<скрыто>")
        result = subPath.replace(result, "/api/sub/<скрыто>")
        result = uuid.replace(result, "<uuid>")
        result = secretParam.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}<скрыто>" }
        return result
    }
}
