package app.iykyk.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.withContext
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> reconnect()
            // ACTION_CONNECT, or a start by the system for Always-on VPN (no action).
            else -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (VpnState.status.value.isActive && commandServer != null) return
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
                updateNotification(serverName ?: getString(R.string.notification_connected))
                startTrafficStats()
            } catch (e: Exception) {
                Log.e(TAG, "failed to connect", e)
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
            }
        }
    }

    private fun formatRate(bytesPerSecond: Long): String = formatSize(bytesPerSecond.coerceAtLeast(0)) + "/с"

    private fun formatSize(bytes: Long): String {
        val units = listOf("Б", "КБ", "МБ", "ГБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$bytes ${units[0]}" else String.format(Locale.forLanguageTag("ru"), "%.1f %s", value, units[unit])
    }

    private fun stopCore() {
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
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked the permission in system settings.
        disconnect()
    }

    override fun onDestroy() {
        statsJob?.cancel()
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
