package app.iykyk.client

import android.app.Application
import android.content.ComponentName
import android.service.quicksettings.TileService
import app.iykyk.client.core.Core
import app.iykyk.client.core.ProfileStore
import app.iykyk.client.core.SettingsStore
import app.iykyk.client.core.SubscriptionWorker
import app.iykyk.client.vpn.IykykTileService
import app.iykyk.client.vpn.VpnState
import app.iykyk.client.vpn.VpnWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IykykApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Core.setup(this)
        ProfileStore.load(this)
        SettingsStore.load(this)
        SubscriptionWorker.schedule(this)
        // Keep the home-screen widget and the Quick Settings tile in sync with the VPN.
        appScope.launch {
            VpnState.status.collect {
                VpnWidget.updateAll(this@IykykApp)
                runCatching {
                    TileService.requestListeningState(
                        this@IykykApp,
                        ComponentName(this@IykykApp, IykykTileService::class.java),
                    )
                }
            }
        }
    }
}
