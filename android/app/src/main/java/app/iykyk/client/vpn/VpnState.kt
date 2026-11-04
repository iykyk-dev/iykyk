package app.iykyk.client.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ;

    val isActive: Boolean get() = this == CONNECTING || this == CONNECTED
}

/** Single source of truth for the VPN state, observed by the UI, the tile and the notification. */
object VpnState {
    private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    internal fun set(status: VpnStatus) {
        _status.value = status
    }
}
