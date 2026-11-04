package app.iykyk.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.iykyk.client.MainActivity
import app.iykyk.client.R

/**
 * The VPN service.
 *
 * Stage 1 (this build): runs as a foreground service with a persistent notification,
 * but does NOT open a tunnel yet, so traffic still goes directly. Opening a TUN without
 * a core behind it would cut the phone off the internet.
 *
 * Stage 2: [connect] will call establish() and hand the TUN file descriptor to the Go core.
 */
class IykykVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "app.iykyk.client.action.CONNECT"
        const val ACTION_DISCONNECT = "app.iykyk.client.action.DISCONNECT"

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnect()
            return START_NOT_STICKY
        }
        // ACTION_CONNECT, or a start by the system for Always-on VPN (no action).
        connect()
        return START_STICKY
    }

    private fun connect() {
        VpnState.set(VpnStatus.CONNECTING)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
        // TODO(stage 2): build the TUN with Builder().establish() and start the core.
        VpnState.set(VpnStatus.CONNECTED)
    }

    private fun disconnect() {
        VpnState.set(VpnStatus.DISCONNECTING)
        // TODO(stage 2): stop the core and close the TUN.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnState.set(VpnStatus.DISCONNECTED)
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked permission in system settings.
        disconnect()
    }

    override fun onDestroy() {
        VpnState.set(VpnStatus.DISCONNECTED)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
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
            .setContentTitle(getString(R.string.notification_connected))
            .setContentText(getString(R.string.notification_demo))
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
