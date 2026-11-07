package app.iykyk.client.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.iykyk.client.core.ProfileStore
import app.iykyk.client.core.SettingsStore

/** Connects when the phone starts, if the user enabled it and the VPN permission is granted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SettingsStore.load(context)
        // After a self-update: reconnect if the VPN was on when the user tapped "Update".
        val afterUpdate = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED &&
            app.iykyk.client.update.Updater.consumeReconnect(context)
        if (!SettingsStore.settings.value.autoConnectOnBoot && !afterUpdate) return
        ProfileStore.load(context)
        if (!ProfileStore.profiles.value.hasServers) return
        // Without the permission the system would need to show a dialog: skip silently.
        if (VpnService.prepare(context) != null) return
        IykykVpnService.start(context)
    }
}
