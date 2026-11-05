package app.iykyk.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.iykyk.client.core.AppSettings
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
            title = "VPN для своих",
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
    private fun Main(status: VpnStatus, profiles: Profiles = this.profiles, tab: Int = 0) {
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
                        onThemeChange = {},
                    )
                }
            },
        )
        for ((name, content) in shots) {
            // 412 x 915 dp (a common phone) at density 2.
            ImageComposeScene(width = 824, height = 1830, density = Density(2f), content = content).use { scene ->
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
    }
}
