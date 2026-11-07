package app.iykyk.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.iykyk.client.core.AppSettings
import app.iykyk.client.core.DeviceInfo
import app.iykyk.client.core.DevicesInfo
import app.iykyk.client.core.DiagCheck
import app.iykyk.client.core.DiagFix
import app.iykyk.client.core.DiagState
import app.iykyk.client.core.Diagnosis
import app.iykyk.client.core.UpdateState
import app.iykyk.client.core.Profiles
import app.iykyk.client.core.ServerInfo
import app.iykyk.client.core.SubscriptionInfo
import app.iykyk.client.vpn.TunnelState
import app.iykyk.client.vpn.VpnStatus
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the main screens (phone size, light and dark) to build/screenshots,
 * so the design can be reviewed from CI. Not an assertion test.
 */
class Screenshots {
    private val now = System.currentTimeMillis()

    private val profiles = Profiles(
        subscription = SubscriptionInfo(
            url = "https://sub.example.com/api/sub/abc",
            title = "IYKYK",
            upload = 40L shl 20,
            download = 128L shl 20,
            total = 100L shl 30,
            expire = now / 1000 + 12 * 86_400,
            supportUrl = "https://t.me/support",
            announce = "",
            webPageUrl = "https://example.com",
            updatedAt = now - 3_600_000,
            updateHours = 2,
        ),
        servers = listOf(
            ServerInfo("🇪🇺 АВТО | Европа", "vless", "", "a", "VLESS · gRPC · REALITY"),
            ServerInfo("🇳🇱 Netherlands", "vless", "", "b", "VLESS · gRPC · REALITY"),
            ServerInfo("🇩🇪 Frankfurt", "vless", "", "c", "VLESS · gRPC · REALITY"),
            ServerInfo("🇸🇪 Stockholm", "vless", "", "d", "VLESS · gRPC · REALITY"),
            ServerInfo("🇵🇱 Poland", "vless", "", "e", "VLESS · XHTTP · REALITY"),
            ServerInfo("🇷🇺 Россия (Клиентам вне РФ)", "vless", "", "f", "VLESS · TCP · REALITY"),
        ),
        selected = 1,
    )
    private val pings = mapOf("a" to 215, "b" to 174, "c" to 371, "d" to -1, "e" to 516, "f" to 88)

    @Composable
    private fun Main(
        status: VpnStatus,
        profiles: Profiles = this.profiles,
        tab: Int = 0,
        update: UpdateState = UpdateState.Idle,
        dataNotice: Boolean = false,
        whatsNew: Pair<String, String>? = null,
        diagnosis: Diagnosis? = null,
    ) {
        MainScreen(
            status = status,
            profiles = profiles,
            error = null,
            refreshing = false,
            pings = pings,
            pinging = false,
            sortByPing = false,
            canBuy = true,
            locked = true,
            clipboardLink = null,
            tunnel = if (status == VpnStatus.CONNECTED) TunnelState.Ok("80.71.232.66", "NL", 174) else null,
            connectedAt = System.nanoTime() / 1_000_000 - 754_000,
            shareLink = "https://sub.example.com/api/sub/abc",
            devices = null,
            onToggle = {}, onPasteLink = {}, onRefresh = {}, onSelectServer = {}, onPing = {},
            onSortChange = {}, onScanQr = {}, onSendToTv = {}, onOpenBot = {}, onOpenUrl = {},
            onImportText = {}, onClipboardDismiss = {}, onCheck = {}, onSendReport = {},
            onCopyLink = {}, onDelete = {}, onLoadDevices = {}, onDeleteDevice = {}, onOpenSettings = {},
            initialTab = tab,
            update = update,
            showDataNotice = dataNotice,
            whatsNew = whatsNew,
            onTroubleshoot = {},
            diagnosis = diagnosis,
        )
    }

    @Test
    fun render() {
        val out = File("build/screenshots").apply { mkdirs() }
        val shots: List<Pair<String, @Composable () -> Unit>> = listOf(
            "main-connected-light" to { IykykTheme(dark = false) { Main(VpnStatus.CONNECTED) } },
            "main-connected-dark" to { IykykTheme(dark = true) { Main(VpnStatus.CONNECTED) } },
            "main-disconnected-light" to { IykykTheme(dark = false) { Main(VpnStatus.DISCONNECTED) } },
            "subscription-dark" to { IykykTheme(dark = true) { Main(VpnStatus.CONNECTED, tab = 1) } },
            "welcome-light" to { IykykTheme(dark = false) { Main(VpnStatus.DISCONNECTED, Profiles()) } },
            "settings-light" to {
                IykykTheme(dark = false) {
                    SettingsScreen(
                        settings = AppSettings(), versions = "iykyk-core dev\nsing-box 1.14.4\nXray 26.10.31",
                        onBack = {}, onBypassChange = {}, onFragmentChange = {}, onPreferXrayChange = {},
                        onAutoLaunchChange = {}, onAutoBootChange = {}, onRemindersChange = {}, onOpenVpnSettings = {},
                        onRemoteDnsChange = {}, onDirectDnsChange = {}, onAppModeChange = {}, onChooseApps = {},
                        onThemeChange = {}, onBankPreset = {},
                    )
                }
            },
        )
        val devices = DevicesInfo(
            used = 4, limit = 5,
            devices = listOf(
                DeviceInfo("1", "Windows", "10.0", "DESKTOP-9EROEH1", "IYKYK/1.1.1", "2026-11-05T10:00:00Z", "2026-11-07T10:00:00Z", current = true),
                DeviceInfo("2", "Android", "17", "Google Pixel 9 Pro XL", "IYKYK/1.1.1", "2026-11-01T10:00:00Z", "2026-11-07T09:00:00Z", current = false),
                DeviceInfo("3", "iOS", "26.6.1", "iPhone 15 Pro", "Happ/3.2", "2026-10-01T10:00:00Z", "2026-11-05T10:00:00Z", current = false, name = "Дочкин айфон"),
                DeviceInfo("4", "Android", "14", "Xiaomi Redmi Note 13 Pro+ 5G", "Happ/3.0", "2026-09-01T10:00:00Z", "2026-10-20T10:00:00Z", current = false),
            ),
        )
        val dialogs: List<Pair<String, @Composable () -> Unit>> = listOf(
            "devices-light" to { IykykTheme(dark = false) { DevicesDialog(devices, onDelete = {}, onRename = { _, _ -> }, onDismiss = {}) } },
            "devices-dark" to { IykykTheme(dark = true) { DevicesDialog(devices, onDelete = {}, onRename = { _, _ -> }, onDismiss = {}) } },
            "update-banner-light" to {
                IykykTheme(dark = false) { Main(VpnStatus.CONNECTED, update = UpdateState.Available("1.2.0", "")) }
            },
            "data-notice-light" to { IykykTheme(dark = false) { Main(VpnStatus.DISCONNECTED, Profiles(), dataNotice = true) } },
            "diagnosis-running-light" to {
                IykykTheme(dark = false) {
                    Main(
                        VpnStatus.CONNECTED,
                        diagnosis = Diagnosis()
                            .with(DiagCheck.INTERNET, DiagState.OK)
                            .with(DiagCheck.SUBSCRIPTION, DiagState.OK)
                            .with(DiagCheck.SERVERS, DiagState.RUNNING),
                    )
                }
            },
            "diagnosis-switch-dark" to {
                IykykTheme(dark = true) {
                    Main(
                        VpnStatus.CONNECTED,
                        diagnosis = Diagnosis()
                            .with(DiagCheck.INTERNET, DiagState.OK)
                            .with(DiagCheck.SUBSCRIPTION, DiagState.OK)
                            .with(DiagCheck.SERVERS, DiagState.WARN, "5 / 7")
                            .with(DiagCheck.TUNNEL, DiagState.SKIPPED)
                            .copy(done = true, fix = DiagFix.SWITCH_SERVER, serverIndex = 1, serverName = "🇫🇮 Финляндия"),
                    )
                }
            },
            "whats-new-dark" to {
                IykykTheme(dark = true) {
                    Main(VpnStatus.CONNECTED, whatsNew = "1.2.0" to "- Помощник «Не работает?»\n- Названия устройств\n- Быстрее подключение")
                }
            },
        )
        // The same screens with the largest common system font (130%), where cut text shows up.
        val large = (shots + dialogs).filter {
            it.first in setOf("main-connected-light", "subscription-dark", "welcome-light", "devices-light", "data-notice-light", "settings-light", "diagnosis-switch-dark")
        }
            .map { (name, content) -> "$name-font130" to content }
        // English: the longest translations, at the large font too.
        val english = (shots + dialogs).filter {
            it.first in setOf("main-connected-light", "subscription-dark", "welcome-light", "settings-light", "diagnosis-switch-dark", "devices-light")
        }
            .flatMap { (name, content) -> listOf("$name-en" to content, "$name-en-font130" to content) }
        val russian = java.util.Locale.getDefault()
        for ((name, content) in shots + dialogs + large + english) {
            java.util.Locale.setDefault(if (name.contains("-en")) java.util.Locale.ENGLISH else java.util.Locale.forLanguageTag("ru"))
            // 412 x 915 dp (a common phone) at density 2.
            val density = if (name.endsWith("-font130")) Density(2f, fontScale = 1.3f) else Density(2f)
            ImageComposeScene(width = 824, height = 1830, density = density, content = content).use { scene ->
                var image = scene.render(0)
                // Let resources load and animations settle (map fly-in, waves).
                for (frame in 1..40) {
                    Thread.sleep(25)
                    image = scene.render(frame * 100_000_000L)
                }
                val data = image.encodeToData(EncodedImageFormat.JPEG, 82) ?: error("encode $name")
                File(out, "$name.jpg").writeBytes(data.bytes)
            }
        }
        // Wide screens: a 10" tablet in landscape (1280 x 800 dp) and a TV (960 x 540 dp at density 2).
        val wide = listOf(
            Triple("tablet-main", 2560 to 1600, shots.first { it.first == "main-connected-light" }.second),
            Triple("tablet-subscription", 2560 to 1600, shots.first { it.first == "subscription-dark" }.second),
            Triple("tablet-settings", 2560 to 1600, shots.first { it.first == "settings-light" }.second),
            Triple("tv-main", 1920 to 1080, shots.first { it.first == "main-connected-dark" }.second),
        )
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru"))
        for ((name, size, content) in wide) {
            ImageComposeScene(width = size.first, height = size.second, density = Density(2f), content = content).use { scene ->
                var image = scene.render(0)
                for (frame in 1..40) {
                    Thread.sleep(25)
                    image = scene.render(frame * 100_000_000L)
                }
                val data = image.encodeToData(EncodedImageFormat.JPEG, 70) ?: error("encode $name")
                File(out, "$name.jpg").writeBytes(data.bytes)
            }
        }
        java.util.Locale.setDefault(russian)
    }
}
