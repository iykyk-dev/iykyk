package app.iykyk.client

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.iykyk.client.ui.IykykTheme
import app.iykyk.client.ui.MainScreen
import app.iykyk.client.vpn.IykykVpnService
import app.iykyk.client.vpn.VpnState

class MainActivity : ComponentActivity() {

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) IykykVpnService.start(this)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()

        setContent {
            IykykTheme {
                val status = VpnState.status.collectAsStateWithLifecycle().value
                MainScreen(status = status, onToggle = ::toggleVpn)
            }
        }
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

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
