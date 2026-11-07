package app.iykyk.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.net.TrafficStats
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.iykyk.client.MainActivity
import app.iykyk.client.R
import app.iykyk.client.core.AppLog
import app.iykyk.client.core.AppMode
import app.iykyk.client.core.Core
import app.iykyk.client.core.NetworkMonitor
import app.iykyk.client.core.ProfileStore
import app.iykyk.client.core.SettingsStore
import app.iykyk.core.iykyk.Iykyk
import app.iykyk.core.libbox.CommandServer
import app.iykyk.core.libbox.CommandServerHandler
import app.iykyk.core.libbox.OverrideOptions
import app.iykyk.core.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The VPN service.
 *
 * Start: build configs from the saved link -> start the in-process engine the protocol
 * needs (Xray, Mieru or AmneziaWG; none for protocols sing-box runs itself) ->
 * start sing-box, which calls back into [PlatformBridge.openTun] to create the TUN.
 * Stop: the same in reverse.
 */
class IykykVpnService : VpnService(), CommandServerHandler {

    companion object {
        const val ACTION_CONNECT = "app.iykyk.client.action.CONNECT"
        const val ACTION_DISCONNECT = "app.iykyk.client.action.DISCONNECT"
        const val ACTION_RECONNECT = "app.iykyk.client.action.RECONNECT"
        const val ACTION_CHECK = "app.iykyk.client.action.CHECK"

        private const val TAG = "IykykVpn"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, IykykVpnService::class.java).setAction(ACTION_CONNECT)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, IykykVpnService::class.java).setAction(ACTION_DISCONNECT)
            context.startService(intent)
        }

        /** Re-checks the tunnel (the app came to the foreground or the user tapped the status). */
        fun check(context: Context) {
            if (VpnState.status.value != VpnStatus.CONNECTED) return
            runCatching {
                context.startService(Intent(context, IykykVpnService::class.java).setAction(ACTION_CHECK))
            }
        }

        /** Restarts the tunnel with the currently selected server, if it is running. */
        fun reconnect(context: Context) {
            if (!VpnState.status.value.isActive) return
            val intent = Intent(context, IykykVpnService::class.java).setAction(ACTION_RECONNECT)
            context.startService(intent)
        }
    }

    // All core calls run one at a time on this dispatcher, so start and stop never overlap.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val coreDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + coreDispatcher)

    private var commandServer: CommandServer? = null
    private var networkMonitor: NetworkMonitor? = null
    private var tun: ParcelFileDescriptor? = null
    private var serverName: String? = null
    private var statsJob: Job? = null

    // Tunnel checks and automatic reconnects (see check()).
    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private var healthJob: Job? = null
    private var networkCheckJob: Job? = null
    private var checkPort = 0L
    private var checkUser = ""
    private var checkPass = ""
    private var lastNetwork: Network? = null
    private var networkSeen = false
    private var failedReconnects = 0
    /** A check passed since this connection started: the server works, so a failure later means the tunnel died. */
    private var hadWorkingTunnel = false
    private var lastReconnectAt = 0L
    private var screenReceiver: BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> reconnect()
            ACTION_CHECK -> {
                healthScope.launch { check("app") }
                return START_STICKY
            }
            // ACTION_CONNECT, or a start by the system for Always-on VPN (no action).
            else -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (VpnState.status.value.isActive && commandServer != null) return
        failedReconnects = 0
        VpnState.set(VpnStatus.CONNECTING)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.status_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
        launchStart()
    }

    private fun reconnect() {
        if (commandServer == null) {
            connect()
            return
        }
        VpnState.set(VpnStatus.CONNECTING)
        updateNotification(getString(R.string.status_connecting))
        scope.launch { stopCore() }
        launchStart()
    }

    private fun launchStart() {
        scope.launch {
            try {
                startCore()
                VpnState.set(VpnStatus.CONNECTED)
                AppLog.i("connected")
                updateNotification(serverName ?: getString(R.string.notification_connected))
                startTrafficStats()
                startHealth()
            } catch (e: Exception) {
                Log.e(TAG, "failed to connect", e)
                AppLog.i("connect failed: ${e.message ?: e.javaClass.simpleName}")
                VpnState.fail(e.message ?: e.javaClass.simpleName)
                stopCore()
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    private fun startCore() {
        Core.setup(this)
        val profiles = ProfileStore.snapshot(this)
        val options = SettingsStore.coreOptions(this)
        val link = profiles.current?.link ?: error(getString(R.string.error_no_server))
        val profile = Iykyk.buildProfileWithOptions(link, options)
        serverName = profile.name
        checkPort = profile.checkPort
        checkUser = profile.checkUser
        checkPass = profile.checkPass
        AppLog.resetCoreLogs(this)
        AppLog.i("connecting: ${profile.name} | ${profile.details} | ${profile.address}")
        SettingsStore.settings.value.let { AppLog.i("apps: ${it.appMode}, selected=${it.apps}") }

        Iykyk.setSocketProtector { fd -> protect(fd) }
        Iykyk.startEngines(profile)

        val monitor = NetworkMonitor(this, ::onUnderlyingNetwork).also { it.start() }
        networkMonitor = monitor

        val server = CommandServer(this, PlatformBridge(this, monitor))
        commandServer = server
        server.start()
        server.startOrReloadService(profile.singBoxConfig, appRouting())
    }

    /**
     * Per-app routing: all apps, only the selected ones, or all except the selected ones.
     * IYKYK itself always stays outside the tunnel: its engines, subscription updates
     * and ping tests must reach the network directly, never through its own VPN.
     */
    private fun appRouting(): OverrideOptions {
        val settings = SettingsStore.settings.value
        val apps = settings.apps.filter { it != packageName }
        return OverrideOptions().apply {
            when (settings.appMode) {
                AppMode.ALL -> excludePackage = StringArray(listOf(packageName))
                AppMode.ONLY_SELECTED -> if (apps.isNotEmpty()) {
                    includePackage = StringArray(apps)
                } else {
                    excludePackage = StringArray(listOf(packageName))
                }
                AppMode.EXCEPT_SELECTED -> excludePackage = StringArray(apps + packageName)
            }
        }
    }

    /**
     * Speed and session traffic in the notification. All tunnelled traffic leaves
     * through the cores in this process, so the app's own traffic counters measure it.
     * Updated every 2 seconds, only while the screen is on.
     */
    private fun startTrafficStats() {
        statsJob?.cancel()
        statsJob = CoroutineScope(Dispatchers.Default).launch {
            val uid = Process.myUid()
            val power = getSystemService(PowerManager::class.java)
            val startRx = TrafficStats.getUidRxBytes(uid)
            val startTx = TrafficStats.getUidTxBytes(uid)
            if (startRx < 0 || startTx < 0) return@launch // counters unavailable
            var lastRx = startRx
            var lastTx = startTx
            var lastAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(2_000)
                if (!power.isInteractive) continue
                if (VpnState.tunnel.value == TunnelState.Failed) continue
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                val now = SystemClock.elapsedRealtime()
                val seconds = ((now - lastAt) / 1000.0).coerceAtLeast(0.5)
                val down = ((rx - lastRx) / seconds).toLong()
                val up = ((tx - lastTx) / seconds).toLong()
                lastRx = rx
                lastTx = tx
                lastAt = now
                val total = (rx - startRx) + (tx - startTx)
                val text = getString(R.string.notification_speed, formatRate(down), formatRate(up), formatSize(total))
                updateNotification(text, serverName)
                // The home-screen widget shows the speed too.
                VpnState.setSpeed("↓ ${formatRate(down)}   ↑ ${formatRate(up)}")
                VpnWidget.updateSpeed(this@IykykVpnService)
            }
        }
    }

    private fun formatRate(bytesPerSecond: Long): String = app.iykyk.client.core.Lang.rate(bytesPerSecond)

    private fun formatSize(bytes: Long): String = app.iykyk.client.core.Lang.bytes(bytes)

    private fun stopCore() {
        stopHealth()
        statsJob?.cancel()
        statsJob = null
        commandServer?.let { server ->
            runCatching { server.closeService() }.onFailure { Log.w(TAG, "closeService", it) }
            runCatching { server.close() }
        }
        commandServer = null
        runCatching { Iykyk.stopEngines() }.onFailure { Log.w(TAG, "stopEngines", it) }
        Iykyk.setSocketProtector(null)
        networkMonitor?.stop()
        networkMonitor = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun disconnect() {
        if (VpnState.status.value == VpnStatus.DISCONNECTED && commandServer == null) {
            finish()
            return
        }
        VpnState.set(VpnStatus.DISCONNECTING)
        AppLog.i("disconnect")
        scope.launch {
            stopCore()
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnState.set(VpnStatus.DISCONNECTED)
    }

    /** Called by [PlatformBridge.openTun] with the established TUN. */
    internal fun attachTun(descriptor: ParcelFileDescriptor) {
        runCatching { tun?.close() }
        tun = descriptor
    }

    private fun onUnderlyingNetwork(network: Network?) {
        // Lets Android attribute traffic and metering to the real network.
        setUnderlyingNetworks(network?.let { arrayOf(it) })
        if (network == lastNetwork) return
        val first = !networkSeen
        networkSeen = true
        lastNetwork = network
        if (network == null) {
            AppLog.i("network lost")
            return
        }
        if (first || VpnState.status.value != VpnStatus.CONNECTED) return
        AppLog.i("network changed")
        // Wi-Fi <-> mobile: give sing-box a moment to rebuild connections, then check.
        networkCheckJob?.cancel()
        networkCheckJob = healthScope.launch {
            delay(3_000)
            check("network")
        }
    }

    // --- Tunnel checks and automatic reconnect ----------------------------------------------

    /**
     * Checks after connecting, when the network changes, when the screen turns on,
     * when the app opens, and every few minutes while the screen is on. Nothing
     * runs with the screen off, so the checks cost practically no battery.
     */
    private fun startHealth() {
        healthJob?.cancel()
        val power = getSystemService(PowerManager::class.java)
        healthJob = healthScope.launch {
            // Xray auto-select balancers need a few seconds to probe their servers.
            delay(4_000)
            check("connect")
            while (isActive) {
                delay(3 * 60_000L)
                if (power.isInteractive) check("periodic")
            }
        }
        hadWorkingTunnel = false
        if (screenReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    healthScope.launch { check("screen") }
                }
            }
            registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            screenReceiver = receiver
        }
    }

    private fun stopHealth() {
        healthJob?.cancel()
        healthJob = null
        networkCheckJob?.cancel()
        networkCheckJob = null
        lastNetwork = null
        networkSeen = false
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    /**
     * A real HTTPS request through the tunnel (sing-box's local check inbound).
     * Three failures in a row while the internet itself works mean the tunnel is
     * dead: reconnect, at most 3 times in a row and not more often than every 20 s,
     * then wait for the next trigger (network change, screen on, app opened).
     */
    private suspend fun check(reason: String) {
        if (!checkMutex.tryLock()) return
        try {
            if (VpnState.status.value != VpnStatus.CONNECTED || checkPort == 0L) return
            AppLog.trimCoreLogs(this)
            if (VpnState.tunnel.value !is TunnelState.Ok) VpnState.setTunnel(TunnelState.Checking)
            for (attempt in 1..3) {
                val result = runCatching { Iykyk.checkTunnel(checkPort, checkUser, checkPass) }
                val info = result.getOrNull()
                if (info != null) {
                    if (VpnState.status.value != VpnStatus.CONNECTED) return
                    VpnState.setTunnel(TunnelState.Ok(info.ip, info.country, info.latency.toInt()))
                    if (failedReconnects > 0 || reason != "periodic") {
                        AppLog.i("check ok ($reason): ${info.country}, ${info.latency} ms")
                    }
                    failedReconnects = 0
                    hadWorkingTunnel = true
                    return
                }
                AppLog.i("check failed ($reason, $attempt/3): ${result.exceptionOrNull()?.message}")
                if (attempt < 3) delay(3_000)
            }
            if (VpnState.status.value != VpnStatus.CONNECTED) return
            if (!directInternet()) {
                AppLog.i("no internet without VPN either")
                VpnState.setTunnel(TunnelState.NoInternet)
                return
            }
            val now = SystemClock.elapsedRealtime()
            val tooSoon = now - lastReconnectAt < 30_000
            val gaveUp = failedReconnects >= 2 && now - lastReconnectAt < 5 * 60_000L
            // Reconnect only a tunnel that worked and then died (network switch,
            // server restart). If it never worked since connecting, reconnecting
            // will not help and would only interrupt traffic: tell the user instead.
            if (!hadWorkingTunnel || tooSoon || gaveUp) {
                VpnState.setTunnel(TunnelState.Failed)
                updateNotification(getString(R.string.tunnel_failed), serverName)
                return
            }
            failedReconnects++
            lastReconnectAt = now
            AppLog.i("tunnel is dead, reconnecting ($failedReconnects/2)")
            VpnState.setTunnel(TunnelState.Reconnecting)
            withContext(Dispatchers.Main) { reconnect() }
        } finally {
            checkMutex.unlock()
        }
    }

    /** Whether the internet works outside the tunnel (IYKYK itself is not tunnelled). */
    private fun directInternet(): Boolean {
        val network = networkMonitor?.network ?: return false
        return listOf("https://connectivitycheck.gstatic.com/generate_204", "https://ya.ru").any { url ->
            runCatching {
                val connection = network.openConnection(URL(url)) as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "HEAD"
                try {
                    connection.responseCode in 200..399
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
        }
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked the permission in system settings.
        disconnect()
    }

    override fun onDestroy() {
        statsJob?.cancel()
        stopHealth()
        healthScope.cancel()
        if (commandServer != null) stopCore()
        scope.cancel()
        VpnState.set(VpnStatus.DISCONNECTED)
        super.onDestroy()
    }

    // --- CommandServerHandler (sing-box asking the app to do something) -------------------

    override fun serviceStop() {
        scope.launch { withContext(Dispatchers.Main) { disconnect() } }
    }

    override fun serviceReload() {}

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(enabled: Boolean) {}

    override fun connectSSHAgent(): Int = throw UnsupportedOperationException()

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String) {
        Log.d(TAG, message)
    }

    // --- Notification ---------------------------------------------------------------------

    private fun updateNotification(text: String, title: String? = null) {
        if (!VpnState.status.value.isActive) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, title))
    }

    private fun buildNotification(text: String, title: String? = null): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_DEFAULT (silent) keeps the notification in the top section of the
        // shade; IMPORTANCE_LOW would push it down among silent notifications.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, IykykVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_tile, getString(R.string.action_disconnect), disconnect)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
