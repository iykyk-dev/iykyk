package app.iykyk.client

import android.app.Application
import app.iykyk.client.core.Core
import app.iykyk.client.core.ProfileStore
import app.iykyk.client.core.SettingsStore

class IykykApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.setup(this)
        ProfileStore.load(this)
        SettingsStore.load(this)
    }
}
