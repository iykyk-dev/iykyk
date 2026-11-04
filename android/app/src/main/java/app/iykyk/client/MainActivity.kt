package app.iykyk.client

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.iykyk.client.core.AppMode
import app.iykyk.client.core.ProfileStore
import app.iykyk.client.core.SettingsStore
import app.iykyk.client.core.ThemeMode
import app.iykyk.client.tv.TvShare
import app.iykyk.client.ui.AppPickerScreen
import app.iykyk.client.ui.IykykTheme
import app.iykyk.client.ui.MainScreen
import app.iykyk.client.ui.SettingsScreen
import app.iykyk.client.vpn.IykykVpnService
import app.iykyk.client.vpn.VpnState
import app.iykyk.core.iykyk.Iykyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private enum class Screen { MAIN, SETTINGS, APPS }

class MainActivity : ComponentActivity() {

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) IykykVpnService.start(this)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { importText(it.trim()) }
    }

    private val refreshing = MutableStateFlow(false)
    private val pinging = MutableStateFlow(false)
    private val pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val versions by lazy { runCatching { Iykyk.versions() }.getOrDefault("") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()
        if (savedInstanceState == null) {
            if (!handleImportIntent(intent)) autoConnectOnLaunch()
        }

        setContent {
            val settings = SettingsStore.settings.collectAsStateWithLifecycle().value
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                // Status bar icons must follow the app's theme, not the system's.
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(style, style)
            }
            IykykTheme(dark = dark) {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                BackHandler(enabled = screen != Screen.MAIN) {
                    screen = if (screen == Screen.APPS) Screen.SETTINGS else Screen.MAIN
                }
                when (screen) {
                    Screen.MAIN -> MainScreen(
                        status = VpnState.status.collectAsStateWithLifecycle().value,
                        profiles = ProfileStore.profiles.collectAsStateWithLifecycle().value,
                        error = VpnState.error.collectAsStateWithLifecycle().value,
                        refreshing = refreshing.collectAsStateWithLifecycle().value,
                        pings = pings.collectAsStateWithLifecycle().value,
                        pinging = pinging.collectAsStateWithLifecycle().value,
                        sortByPing = settings.sortByPing,
                        onToggle = ::toggleVpn,
                        onPasteLink = ::pasteLink,
                        onRefresh = ::refreshSubscription,
                        onSelectServer = ::selectServer,
                        onPing = ::pingServers,
                        onScanQr = ::scanQr,
                        onSendToTv = ::scanTvCode,
                        onOpenUrl = ::openUrl,
                        onImportText = ::importText,
                        onSortChange = { value -> SettingsStore.update(this) { it.copy(sortByPing = value) } },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        versions = versions,
                        onBack = { screen = Screen.MAIN },
                        onBypassChange = { value -> changeSettings { it.copy(bypassRussia = value) } },
                        onFragmentChange = { value -> changeSettings { it.copy(fragment = value) } },
                        onAutoLaunchChange = { value -> SettingsStore.update(this) { it.copy(autoConnectOnLaunch = value) } },
                        onAutoBootChange = { value -> SettingsStore.update(this) { it.copy(autoConnectOnBoot = value) } },
                        onRemindersChange = { value -> SettingsStore.update(this) { it.copy(expiryReminders = value) } },
                        onOpenVpnSettings = ::openVpnSettings,
                        onRemoteDnsChange = { value -> changeSettings { it.copy(remoteDns = value) } },
                        onDirectDnsChange = { value -> changeSettings { it.copy(directDns = value) } },
                        onAppModeChange = { mode -> changeSettings { it.copy(appMode = mode) } },
                        onChooseApps = { screen = Screen.APPS },
                        onThemeChange = { mode -> SettingsStore.update(this) { it.copy(themeMode = mode) } },
                    )
                    Screen.APPS -> AppPickerScreen(
                        selected = settings.apps,
                        onBack = {
                            screen = Screen.SETTINGS
                            // Apply the new app list once, when leaving the picker.
                            if (settings.appMode != AppMode.ALL) IykykVpnService.reconnect(this)
                        },
                        onToggle = { pkg ->
                            SettingsStore.update(this) {
                                it.copy(apps = if (pkg in it.apps) it.apps - pkg else it.apps + pkg)
                            }
                        },
                    )
                }
            }
        }
    }

    private var lastPingAt = 0L

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            ProfileStore.refreshIfStale(this@MainActivity)
            // Re-test the servers when the app opens, at most every 10 minutes.
            if (System.currentTimeMillis() - lastPingAt > 10 * 60_000L) pingServers()
        }
    }

    private fun changeSettings(transform: (app.iykyk.client.core.AppSettings) -> app.iykyk.client.core.AppSettings) {
        SettingsStore.update(this, transform)
        IykykVpnService.reconnect(this)
    }

    private fun toggleVpn() {
        if (VpnState.status.value.isActive) {
            IykykVpnService.stop(this)
            return
        }
        // null means the user has already allowed this app to act as a VPN.
        val consent = VpnService.prepare(this)
        if (consent == null) {
            IykykVpnService.start(this)
        } else {
            vpnPermission.launch(consent)
        }
    }

    private fun pasteLink() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty()) {
            toast(getString(R.string.link_clipboard_empty))
            return
        }
        importText(text)
    }

    /** Imports a subscription URL (or, in an unlocked build, a link) from any source. */
    private fun importText(text: String) {
        // A TV's "receive from phone" code scanned from anywhere: send to that TV.
        if (text.startsWith(TvShare.SCHEME)) {
            sendToTv(text)
            return
        }
        if (text.startsWith("http", ignoreCase = true)) toast(getString(R.string.link_loading))
        lifecycleScope.launch {
            try {
                val description = ProfileStore.import(this@MainActivity, text)
                toast(getString(R.string.link_saved, description))
                pings.value = emptyMap()
                IykykVpnService.reconnect(this@MainActivity)
            } catch (_: ProfileStore.NotAllowedException) {
                toast(getString(R.string.link_not_allowed))
            } catch (e: Exception) {
                toast(getString(R.string.link_invalid, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * iykyk://add/<URL>, iykyk://import?url=<URL> (e.g. a button on the subscription
     * page or in a Telegram bot), or text shared to IYKYK. Returns true if it imported.
     */
    private fun handleImportIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(::linkFromDeepLink)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::firstUrl)
            else -> null
        } ?: return false
        intent.action = null // do not import again on configuration changes
        importText(text)
        return true
    }

    private fun linkFromDeepLink(uri: Uri): String? {
        if (uri.scheme != "iykyk") return null
        uri.getQueryParameter("url")?.let { return it }
        val raw = uri.toString()
        return listOf("iykyk://add/", "iykyk://import/", "iykyk://sub/")
            .firstOrNull { raw.startsWith(it) }
            ?.let { Uri.decode(raw.removePrefix(it)) }
    }

    private fun firstUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', ']') ?: text.trim().ifEmpty { null }

    private fun scanQr() = launchScanner(getString(R.string.qr_prompt))

    /** "Send to TV": scan the code the TV shows in "Receive from phone". */
    private fun scanTvCode() {
        if (tvPayload() == null) {
            toast(getString(R.string.tv_nothing_to_send))
            return
        }
        launchScanner(getString(R.string.tv_scan_prompt))
    }

    /** The subscription URL, or the single link in an unlocked build. */
    private fun tvPayload(): String? {
        val profiles = ProfileStore.profiles.value
        return profiles.subscription?.url?.takeIf { it.isNotBlank() }
            ?: profiles.current?.link?.takeIf { it.isNotBlank() && profiles.servers.size == 1 }
    }

    private fun sendToTv(code: String) {
        val payload = tvPayload()
        if (payload == null) {
            toast(getString(R.string.tv_nothing_to_send))
            return
        }
        toast(getString(R.string.tv_sending))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { TvShare.send(code, payload) }
                toast(getString(R.string.tv_sent))
            } catch (e: Exception) {
                toast(getString(R.string.tv_send_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun launchScanner(prompt: String) {
        qrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(prompt)
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun openVpnSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
    }

    private fun autoConnectOnLaunch() {
        if (!SettingsStore.settings.value.autoConnectOnLaunch) return
        if (VpnState.status.value.isActive || !ProfileStore.profiles.value.hasServers) return
        toggleVpn()
    }

    private fun refreshSubscription() {
        if (refreshing.value) return
        refreshing.value = true
        lifecycleScope.launch {
            try {
                ProfileStore.refresh(this@MainActivity)
                toast(getString(R.string.refresh_done))
            } catch (e: Exception) {
                toast(getString(R.string.refresh_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                refreshing.value = false
            }
        }
    }

    private fun selectServer(index: Int) {
        val previous = ProfileStore.profiles.value.selected
        ProfileStore.select(this, index)
        if (index != previous) IykykVpnService.reconnect(this)
    }

    private fun pingServers() {
        if (pinging.value) return
        val servers = ProfileStore.profiles.value.servers
        if (servers.isEmpty()) return
        pinging.value = true
        lifecycleScope.launch {
            try {
                val links = servers.map { it.link }
                val options = SettingsStore.coreOptions(this@MainActivity)
                // A real HTTPS request through each server, not just a TCP connect.
                val json = withContext(Dispatchers.IO) { Iykyk.realPingServers(JSONArray(links).toString(), options) }
                val results = JSONArray(json)
                pings.value = links.indices.associate { links[it] to results.optInt(it, -1) }
                lastPingAt = System.currentTimeMillis()
            } catch (_: Exception) {
            } finally {
                pinging.value = false
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
