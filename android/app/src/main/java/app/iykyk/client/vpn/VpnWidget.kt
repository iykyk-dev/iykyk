package app.iykyk.client.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.iykyk.client.MainActivity
import app.iykyk.client.R
import app.iykyk.client.core.ProfileStore

/** Home-screen widget: one tap to connect or disconnect, with the current state. */
class VpnWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        if (VpnState.status.value.isActive) {
            IykykVpnService.stop(context)
            return
        }
        ProfileStore.load(context)
        // No server or no VPN permission yet: that needs the app.
        if (!ProfileStore.profiles.value.hasServers || VpnService.prepare(context) != null) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        IykykVpnService.start(context)
    }

    companion object {
        private const val ACTION_TOGGLE = "app.iykyk.client.action.WIDGET_TOGGLE"

        /** Redraws the widgets with the new speed, if there are any on the home screen. */
        fun updateSpeed(context: Context) = updateAll(context)

        /** Redraws every widget instance; called whenever the VPN state changes. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val status = VpnState.status.value
            val views = RemoteViews(context.packageName, R.layout.widget_vpn)
            val connected = status == VpnStatus.CONNECTED
            views.setInt(
                R.id.widget_button,
                "setBackgroundResource",
                if (connected) R.drawable.widget_button_on else R.drawable.widget_button_off,
            )
            views.setInt(
                R.id.widget_icon,
                "setColorFilter",
                ContextCompat.getColor(context, if (connected) R.color.icon_background else R.color.brand_orange),
            )
            val text = when (status) {
                VpnStatus.DISCONNECTED -> R.string.status_disconnected
                VpnStatus.CONNECTING -> R.string.status_connecting
                VpnStatus.CONNECTED -> R.string.status_connected
                VpnStatus.DISCONNECTING -> R.string.status_disconnecting
            }
            // Two lines fit a one-row widget at any font size: what is on, and how fast.
            val server = ProfileStore.profiles.value.current?.name
            val speed = VpnState.speed.value
            views.setTextViewText(
                R.id.widget_title,
                if (connected && server != null) server else context.getString(text),
            )
            views.setTextViewText(
                R.id.widget_status,
                when {
                    connected && speed != null -> speed
                    connected -> context.getString(R.string.status_connected)
                    status == VpnStatus.DISCONNECTED -> context.getString(R.string.widget_tap_to_connect)
                    else -> context.getString(R.string.app_name)
                },
            )
            views.setTextColor(
                R.id.widget_status,
                ContextCompat.getColor(context, if (connected) R.color.brand_orange else R.color.widget_muted),
            )
            val toggle = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, VpnWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, toggle)
            manager.updateAppWidget(ids, views)
        }
    }
}
