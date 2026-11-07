package app.iykyk.desktop

import app.iykyk.client.core.AppSettings
import app.iykyk.client.core.DevicesApi
import app.iykyk.client.core.DevicesInfo
import app.iykyk.client.core.EXPIRY_REMIND_DAYS
import app.iykyk.client.core.daysLeft
import app.iykyk.client.core.Profiles
import app.iykyk.client.vpn.TunnelState
import app.iykyk.client.core.AppMode
import app.iykyk.client.vpn.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop counterpart of MainActivity + IykykVpnService: app state for the
 * shared screens, kept in sync with the core service.
 */
class DesktopController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val core = CoreClient()
    private val store = DesktopStore()

    val profiles = MutableStateFlow(store.loadProfiles())
    val settings = MutableStateFlow(store.loadSettings())
    val status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val error = MutableStateFlow<String?>(null)
    val tunnel = MutableStateFlow<TunnelState?>(null)
    val connectedAt = MutableStateFlow(0L)
    val refreshing = MutableStateFlow(false)
    val pinging = MutableStateFlow(false)
    val pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val message = MutableStateFlow<String?>(null)
    val versions = MutableStateFlow("")
    val devices = MutableStateFlow<DevicesInfo?>(null)
    /** A Windows notification to show from the tray: title to text. */
    val notification = MutableStateFlow<Pair<String, String>?>(null)
    val locked = MutableStateFlow(true)
    val updater = DesktopUpdater()
    /** Version + notes to show once after an update. */
    val whatsNew = MutableStateFlow(updater.pendingNotes())

    private var busy = false
    private var checkJob: Job? = null
    private var hadWorkingTunnel = false
    private var autoReconnects = 0

    init {
        scope.launch {
            runCatching {
                val info = JSONObject(io { core.post("/v1/versions") })
                versions.value = info.optString("versions")
                locked.value = info.optBoolean("locked", true)
            }.onFailure { error.value = it.message }
            pollStatus()
        }
        scope.launch {
            refreshIfStale()
            remindIfExpiring()
            pingServers()
            // Right after an update the service was reinstalled: reconnect if the VPN was on.
            val afterUpdate = updater.takeReconnect()
            if ((settings.value.autoConnectOnLaunch || afterUpdate) && profiles.value.hasServers) connect()
        }
        scope.launch {
            // Check for a new version a little after start, then twice a day.
            delay(15_000)
            while (true) {
                if (updater.check(manual = false)) {
                    val version = (updater.state.value as? app.iykyk.client.core.UpdateState.Available)?.version
                    if (version != null && store.notifiedUpdate != version) {
                        store.notifiedUpdate = version
                        notification.value = "Вышла новая версия IYKYK $version" to "Откройте IYKYK и нажмите «Обновить»"
                    }
                }
                delay(12 * 3_600_000L)
            }
        }
        // "System proxy" mode: Windows' proxy points to IYKYK while connected, and
        // is given back on disconnect (also after a crash, at the next start).
        scope.launch {
            status.collect { state ->
                val wanted = state == VpnStatus.CONNECTED && settings.value.systemProxy
                io { if (wanted) WindowsProxy.enable() else if (state == VpnStatus.DISCONNECTED) WindowsProxy.disableIfOurs() }
            }
        }
        scope.launch {
            updater.state.collect { state ->
                if (state is app.iykyk.client.core.UpdateState.Failed) {
                    message.value = "Обновление не установлено: ${state.message}"
                    updater.acknowledgeFailure()
                }
            }
        }
    }

    // --- Status ---------------------------------------------------------------------

    /** The service owns the tunnel; mirror its state every second. */
    private suspend fun pollStatus() {
        while (scope.isActive) {
            runCatching { io { core.get("/v1/status") } }.onSuccess { applyStatus(it) }
            delay(1_000)
        }
    }

    private fun applyStatus(json: JSONObject) {
        if (busy) return
        val state = when (json.optString("state")) {
            "connected" -> VpnStatus.CONNECTED
            "connecting" -> VpnStatus.CONNECTING
            else -> VpnStatus.DISCONNECTED
        }
        if (state != status.value) {
            status.value = state
            if (state == VpnStatus.CONNECTED) startChecks() else stopChecks()
        }
        val since = json.optLong("connectedAt")
        connectedAt.value = if (state == VpnStatus.CONNECTED && since > 0) {
            // SessionTimer counts in System.nanoTime() milliseconds.
            System.nanoTime() / 1_000_000 - (System.currentTimeMillis() - since)
        } else {
            0L
        }
        json.optString("error").takeIf { it.isNotBlank() && state == VpnStatus.DISCONNECTED }?.let { error.value = it }
    }

    // --- Connect --------------------------------------------------------------------

    fun toggle() {
        if (status.value == VpnStatus.CONNECTED || status.value == VpnStatus.CONNECTING) {
            disconnect()
        } else {
            hadWorkingTunnel = false
            autoReconnects = 0
            connect()
        }
    }

    fun connect() {
        val link = profiles.value.current?.link ?: return
        busy = true
        error.value = null
        status.value = VpnStatus.CONNECTING
        scope.launch {
            try {
                val result = JSONObject(io {
                    core.post("/v1/connect", JSONObject().put("link", link).put("options", coreOptions()), 60)
                })
                busy = false
                applyStatus(result)
            } catch (e: Exception) {
                busy = false
                status.value = VpnStatus.DISCONNECTED
                error.value = e.message
            }
        }
    }

    /** Quitting: give the system proxy back right away, the coroutine may not get to run. */
    fun shutdown() {
        runCatching { WindowsProxy.disableIfOurs() }
    }

    fun disconnect() {
        busy = true
        status.value = VpnStatus.DISCONNECTING
        stopChecks()
        scope.launch {
            runCatching { io { core.post("/v1/disconnect") } }
            busy = false
            status.value = VpnStatus.DISCONNECTED
            connectedAt.value = 0
        }
    }

    private fun reconnectIfConnected() {
        if (status.value == VpnStatus.CONNECTED) connect()
    }

    private fun coreOptions(): String = JSONObject()
        .put("bypassRussia", settings.value.bypassRussia)
        .put("fragment", settings.value.fragment)
        .put("remoteDNS", "cloudflare")
        .put("directDNS", "yandex")
        .put("preferXray", settings.value.preferXray)
        .put(
            "appMode",
            when (settings.value.appMode) {
                AppMode.ALL -> "all"
                AppMode.ONLY_SELECTED -> "only"
                AppMode.EXCEPT_SELECTED -> "except"
            },
        )
        .put("apps", org.json.JSONArray(settings.value.apps.toList()))
        .put("systemProxyPort", if (settings.value.systemProxy) WindowsProxy.PORT else 0)
        .apply {
            profiles.value.subscription?.routing?.takeIf { it.isNotBlank() }
                ?.let { runCatching { put("routing", JSONObject(it)) } }
        }
        .toString()

    // --- Tunnel checks --------------------------------------------------------------

    private fun startChecks() {
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(4_000)
            while (isActive) {
                check()
                delay(3 * 60_000L)
            }
        }
    }

    private fun stopChecks() {
        checkJob?.cancel()
        checkJob = null
        tunnel.value = null
    }

    /** "Your subscription ends in N days", once a day, from 3 days before. */
    private fun remindIfExpiring() {
        if (!settings.value.expiryReminders) return
        val sub = profiles.value.subscription ?: return
        val days = sub.daysLeft() ?: return
        if (days > EXPIRY_REMIND_DAYS) return
        val today = java.time.LocalDate.now().toString()
        if (store.lastReminderDay == today) return
        store.lastReminderDay = today
        val text = when {
            days < 0 -> "Подписка закончилась. Продлите её, чтобы VPN продолжил работать."
            days == 0 -> "Подписка заканчивается сегодня"
            days == 1 -> "Подписка заканчивается через 1 день"
            days in 2..4 -> "Подписка заканчивается через $days дня"
            else -> "Подписка заканчивается через $days дней"
        }
        notification.value = sub.title.ifBlank { "IYKYK" } to text
    }

    // --- Devices --------------------------------------------------------------------

    fun loadDevices() {
        val sub = profiles.value.subscription ?: return
        scope.launch { devices.value = runCatching { DevicesApi.load(sub, store.hwid) }.getOrNull() }
    }

    fun renameDevice(id: String, name: String) {
        val sub = profiles.value.subscription ?: return
        scope.launch {
            try {
                devices.value = DevicesApi.rename(sub, store.hwid, id, name)
            } catch (e: Exception) {
                message.value = "Не удалось сохранить название: ${e.message}"
            }
        }
    }

    fun deleteDevice(id: String) {
        val sub = profiles.value.subscription ?: return
        scope.launch {
            try {
                devices.value = DevicesApi.delete(sub, store.hwid, id)
                message.value = "Устройство удалено"
            } catch (e: Exception) {
                message.value = "Не удалось удалить: ${e.message}"
            }
        }
    }

    fun check() {
        if (status.value != VpnStatus.CONNECTED) return
        scope.launch {
            if (tunnel.value !is TunnelState.Ok) tunnel.value = TunnelState.Checking
            repeat(3) { attempt ->
                val result = runCatching { JSONObject(io { core.post("/v1/check") }) }.getOrNull()
                if (result != null) {
                    tunnel.value = TunnelState.Ok(result.optString("ip"), result.optString("country"), result.optInt("latency"))
                    hadWorkingTunnel = true
                    autoReconnects = 0
                    return@launch
                }
                if (attempt < 2) delay(3_000)
            }
            // As on Android: restart only a tunnel that worked and then died
            // (sleep, network switch), at most twice in a row.
            if (hadWorkingTunnel && autoReconnects < 2) {
                autoReconnects++
                tunnel.value = TunnelState.Reconnecting
                connect()
            } else {
                tunnel.value = TunnelState.Failed
            }
        }
    }

    // --- Subscription ---------------------------------------------------------------

    fun importText(text: String) {
        val trimmed = text.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            if (locked.value) message.value = "Эта ссылка не от «Для своих». IYKYK работает только с подписками нашего сервиса."
            return
        }
        message.value = "Загружаю подписку…"
        scope.launch {
            try {
                download(trimmed)
                message.value = "Добавлено: " + (profiles.value.subscription?.title?.ifBlank { null } ?: "${profiles.value.servers.size}")
                pings.value = emptyMap()
                reconnectIfConnected()
                pingServers()
            } catch (e: Exception) {
                message.value = friendly(e) ?: "Не удалось добавить: ${e.message}"
            }
        }
    }

    fun refresh() {
        val url = profiles.value.subscription?.url ?: return
        if (refreshing.value) return
        refreshing.value = true
        scope.launch {
            try {
                download(url)
                message.value = "Подписка обновлена"
                remindIfExpiring()
            } catch (e: Exception) {
                message.value = friendly(e) ?: "Не удалось обновить: ${e.message}"
            } finally {
                refreshing.value = false
            }
        }
    }

    private suspend fun refreshIfStale() {
        val sub = profiles.value.subscription ?: return
        val hours = sub.updateHours.takeIf { it > 0 } ?: 12
        if (System.currentTimeMillis() - sub.updatedAt < hours * 3_600_000L) return
        runCatching { download(sub.url) }
    }

    private suspend fun download(url: String) {
        val device = JSONObject()
            .put("hwid", store.hwid)
            .put("os", "Windows")
            .put("osVersion", System.getProperty("os.version") ?: "")
            .put("model", System.getenv("COMPUTERNAME") ?: "PC")
        val fallbacks = JSONArray(profiles.value.subscription?.fallbacks.orEmpty()).toString()
        val json = io {
            core.post(
                "/v1/fetch",
                JSONObject().put("url", url).put("fallbacks", fallbacks)
                    .put("userAgent", "IYKYK/${System.getProperty("iykyk.version") ?: "dev"} (Windows ${System.getProperty("os.version")})")
                    .put("device", device),
            )
        }
        val now = System.currentTimeMillis()
        val previous = profiles.value
        var parsed = store.parse(url, json, now)
        val keep = parsed.servers.indexOfFirst { it.name == previous.current?.name }
        parsed = parsed.copy(selected = if (keep >= 0) keep else 0)
        store.saveSubscription(url, json, now, parsed.selected)
        profiles.value = parsed
    }

    fun select(index: Int) {
        val current = profiles.value
        if (index !in current.servers.indices) return
        store.saveSelected(index)
        profiles.value = current.copy(selected = index)
        if (index != current.selected) reconnectIfConnected()
    }

    fun deleteSubscription() {
        if (status.value != VpnStatus.DISCONNECTED) disconnect()
        store.clearSubscription()
        profiles.value = Profiles()
        devices.value = null
        pings.value = emptyMap()
    }

    fun pingServers() {
        val servers = profiles.value.servers
        if (servers.isEmpty() || pinging.value) return
        pinging.value = true
        scope.launch {
            try {
                val links = servers.map { it.link }
                val text = io {
                    core.post("/v1/real-ping", JSONObject().put("links", JSONArray(links).toString()).put("options", coreOptions()), 90)
                }
                val results = JSONArray(text)
                pings.value = links.indices.associate { links[it] to results.optInt(it, -1) }
            } catch (_: Exception) {
            } finally {
                pinging.value = false
            }
        }
    }

    // --- Settings -------------------------------------------------------------------

    fun updateSettings(reconnect: Boolean = false, transform: (AppSettings) -> AppSettings) {
        val before = settings.value
        settings.value = transform(settings.value)
        store.saveSettings(settings.value)
        if (before.autoConnectOnBoot != settings.value.autoConnectOnBoot) {
            WindowsIntegration.setAutostart(settings.value.autoConnectOnBoot)
        }
        if (reconnect) reconnectIfConnected()
    }

    // --- Desktop actions ------------------------------------------------------------

    fun pasteFromClipboard() {
        val text = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        }.getOrNull()?.trim()
        if (text.isNullOrEmpty()) {
            message.value = "В буфере обмена нет текста"
            return
        }
        importText(text)
    }

    fun copyLink() {
        val url = profiles.value.subscription?.url ?: return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
        message.value = "Ссылка скопирована"
    }

    fun checkUpdates() {
        scope.launch { updater.check(manual = true) }
    }

    /** Downloads the installer and starts it; the app quits so its files can be replaced. */
    fun installUpdate(onQuit: () -> Unit) {
        scope.launch {
            if (updater.install(vpnWasOn = status.value != VpnStatus.DISCONNECTED)) onQuit()
        }
    }

    fun whatsNewSeen() {
        whatsNew.value = null
        updater.notesSeen()
    }

    fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    /** A text report with the logs, saved to the desktop and shown in Explorer. */
    fun saveReport() {
        scope.launch {
            val file = io {
                val logs = File(core.dataDir, "logs")
                val text = buildString {
                    appendLine("Отчёт IYKYK для Windows, ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(Date())}")
                    appendLine("Windows ${System.getProperty("os.version")}, ${System.getProperty("os.arch")}")
                    appendLine(versions.value)
                    appendLine("VPN: ${status.value}, проверка: ${tunnel.value ?: "-"}, ошибка: ${error.value ?: "-"}")
                    val server = profiles.value.current
                    appendLine("Сервер: ${server?.name} | ${server?.details} | ${server?.address}")
                    for (name in listOf("singbox.log", "xray.log")) {
                        appendLine("--- $name ---")
                        appendLine(runCatching { File(logs, name).readText().takeLast(48_000) }.getOrDefault(""))
                    }
                }.let { redact(it) }
                val desktop = File(System.getProperty("user.home"), "Desktop").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
                File(desktop, "IYKYK-report.txt").apply { writeText(text) }
            }
            message.value = "Отчёт сохранён на рабочий стол: ${file.name}. Отправьте его в поддержку"
            runCatching { Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", file.absolutePath)) }
        }
    }

    private fun redact(text: String): String {
        var result = text
        profiles.value.subscription?.url?.let { result = result.replace(it, "<скрыто>") }
        profiles.value.servers.forEach { if (it.link.length > 10) result = result.replace(it.link, "<скрыто>") }
        result = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""").replace(result, "<uuid>")
        return result
    }

    private fun friendly(e: Exception): String? = when {
        e.message.orEmpty().startsWith("subscription not allowed") ->
            "Эта ссылка не от «Для своих». IYKYK работает только с подписками нашего сервиса."
        e.message.orEmpty().startsWith("hwid:") ->
            "Достигнут лимит устройств подписки. Удалите старое устройство или напишите в поддержку."
        else -> null
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
