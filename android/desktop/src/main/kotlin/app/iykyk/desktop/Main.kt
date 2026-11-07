package app.iykyk.desktop

import app.iykyk.client.core.AboutInfo
import app.iykyk.client.core.AppMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.iykyk.client.core.ThemeMode
import app.iykyk.client.ui.IykykTheme
import app.iykyk.client.ui.MainScreen
import app.iykyk.client.ui.SettingsScreen
import app.iykyk.client.vpn.VpnStatus
import kotlinx.coroutines.delay
import javax.imageio.ImageIO

/** Shown or hidden; another copy of the app started asks to show it. */
private val windowVisible = kotlinx.coroutines.flow.MutableStateFlow(true)

fun main(args: Array<String>) {
    if (!WindowsIntegration.acquireSingleInstance { windowVisible.value = true }) return
    // Started with Windows: stay in the tray.
    if ("--minimized" in args) windowVisible.value = false
    runApp()
}

private fun runApp() = application {
    val app = remember { DesktopController() }
    var visible by remember { mutableStateOf(windowVisible.value) }
    LaunchedEffect(Unit) { windowVisible.collect { visible = it } }
    val trayState = rememberTrayState()
    val notification by app.notification.collectAsState()
    LaunchedEffect(notification) {
        notification?.let { (title, text) ->
            trayState.sendNotification(Notification(title, text, Notification.Type.Info))
            app.notification.value = null
        }
    }
    val icon = remember {
        BitmapPainter(ImageIO.read(Thread.currentThread().contextClassLoader.getResource("icon.png")).toComposeImageBitmap())
    }
    val status by app.status.collectAsState()

    Tray(
        icon = icon,
        state = trayState,
        tooltip = "IYKYK",
        onAction = { windowVisible.value = true },
        menu = {
            Item("Открыть IYKYK", onClick = { windowVisible.value = true })
            Item(if (status == VpnStatus.DISCONNECTED) "Подключить" else "Отключить", onClick = app::toggle)
            Separator()
            Item("Выход", onClick = {
                app.disconnect()
                app.shutdown()
                exitApplication()
            })
        },
    )

    Window(
        // Closing the window keeps the VPN and the tray icon; "Выход" quits.
        onCloseRequest = { windowVisible.value = false },
        visible = visible,
        title = "IYKYK",
        icon = icon,
        state = rememberWindowState(size = DpSize(430.dp, 860.dp)),
    ) {
        // The installer replaces the app's files: quit once it has started.
        App(app, quit = { exitApplication() })
    }
}

private enum class Screen { MAIN, SETTINGS, PROGRAMS }

@Composable
private fun App(app: DesktopController, quit: () -> Unit) {
    val settings by app.settings.collectAsState()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    val message by app.message.collectAsState()

    IykykTheme(dark = dark) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.MAIN -> MainScreen(
                    status = app.status.collectAsState().value,
                    profiles = app.profiles.collectAsState().value,
                    error = app.error.collectAsState().value,
                    refreshing = app.refreshing.collectAsState().value,
                    pings = app.pings.collectAsState().value,
                    pinging = app.pinging.collectAsState().value,
                    sortByPing = settings.sortByPing,
                    canBuy = true,
                    locked = app.locked.collectAsState().value,
                    clipboardLink = null,
                    tunnel = app.tunnel.collectAsState().value,
                    connectedAt = app.connectedAt.collectAsState().value,
                    shareLink = app.profiles.collectAsState().value.subscription?.url,
                    devices = app.devices.collectAsState().value,
                    onToggle = app::toggle,
                    onPasteLink = app::pasteFromClipboard,
                    onRefresh = app::refresh,
                    onSelectServer = app::select,
                    onPing = app::pingServers,
                    onSortChange = { value -> app.updateSettings { it.copy(sortByPing = value) } },
                    onScanQr = { app.message.value = "На компьютере нет камеры: скопируйте ссылку на подписку и нажмите «Вставить»" },
                    onSendToTv = {
                        app.message.value = "Отправить на телевизор можно с телефона: IYKYK на телефоне → ⋯ → «Отправить на TV»"
                    },
                    onOpenBot = { app.openUrl("https://t.me/dlyasvoihVPS_bot") },
                    onOpenUrl = app::openUrl,
                    onImportText = app::importText,
                    onClipboardDismiss = {},
                    onCheck = app::check,
                    onSendReport = app::saveReport,
                    onCopyLink = app::copyLink,
                    onDelete = app::deleteSubscription,
                    onLoadDevices = app::loadDevices,
                    onDeleteDevice = app::deleteDevice,
                    onRenameDevice = app::renameDevice,
                    onOpenSettings = { screen = Screen.SETTINGS },
                    update = app.updater.state.collectAsState().value,
                    onUpdate = { app.installUpdate(onQuit = quit) },
                    whatsNew = app.whatsNew.collectAsState().value,
                    onWhatsNewSeen = app::whatsNewSeen,
                )
                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    versions = app.versions.collectAsState().value,
                    onBack = { screen = Screen.MAIN },
                    onBypassChange = { value -> app.updateSettings(reconnect = true) { it.copy(bypassRussia = value) } },
                    onFragmentChange = { value -> app.updateSettings(reconnect = true) { it.copy(fragment = value) } },
                    onPreferXrayChange = { value -> app.updateSettings(reconnect = true) { it.copy(preferXray = value) } },
                    onAutoLaunchChange = { value -> app.updateSettings { it.copy(autoConnectOnLaunch = value) } },
                    onAutoBootChange = { value -> app.updateSettings { it.copy(autoConnectOnBoot = value) } },
                    onRemindersChange = { value -> app.updateSettings { it.copy(expiryReminders = value) } },
                    onOpenVpnSettings = {},
                    onRemoteDnsChange = {},
                    onDirectDnsChange = {},
                    onAppModeChange = { mode -> app.updateSettings(reconnect = true) { it.copy(appMode = mode) } },
                    onChooseApps = { screen = Screen.PROGRAMS },
                    onSystemProxyChange = { value -> app.updateSettings(reconnect = true) { it.copy(systemProxy = value) } },
                    onThemeChange = { mode -> app.updateSettings { it.copy(themeMode = mode) } },
                    mobile = false,
                    about = AboutInfo(System.getProperty("iykyk.version") ?: "dev", ""),
                    onOpenUrl = app::openUrl,
                    update = app.updater.state.collectAsState().value,
                    onCheckUpdates = if (app.updater.enabled) app::checkUpdates else null,
                )
                Screen.PROGRAMS -> ProgramsScreen(
                    selected = settings.apps,
                    onToggle = { name ->
                        app.updateSettings(reconnect = settings.appMode != AppMode.ALL) {
                            val has = it.apps.any { a -> a.equals(name, ignoreCase = true) }
                            it.copy(apps = if (has) it.apps.filterNot { a -> a.equals(name, ignoreCase = true) }.toSet() else it.apps + name)
                        }
                    },
                    onBack = { screen = Screen.SETTINGS },
                )
            }
            message?.let { text ->
                LaunchedEffect(text) {
                    delay(4_000)
                    if (app.message.value == text) app.message.value = null
                }
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text(text) }
            }
        }
    }
}
