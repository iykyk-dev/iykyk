package app.iykyk.client.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single source of truth for the VPN state, observed by the UI, the tile and the notification. */
object VpnState {
    private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    /** Why the last connection attempt failed; cleared when a new attempt starts. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** When the session started (System.nanoTime() in ms), or 0. Automatic reconnects keep it. */
    private val _connectedAt = MutableStateFlow(0L)
    val connectedAt: StateFlow<Long> = _connectedAt.asStateFlow()

    /** "↓ 1,2 МБ/с  ↑ 80 КБ/с" while connected and the screen is on, else null. */
    private val _speed = MutableStateFlow<String?>(null)
    val speed: StateFlow<String?> = _speed.asStateFlow()

    internal fun setSpeed(text: String?) {
        _speed.value = text
    }

    private val _tunnel = MutableStateFlow<TunnelState?>(null)
    val tunnel: StateFlow<TunnelState?> = _tunnel.asStateFlow()

    internal fun set(status: VpnStatus) {
        if (status == VpnStatus.CONNECTING) _error.value = null
        if (status != VpnStatus.CONNECTED) {
            _tunnel.value = null
            _speed.value = null
        }
        when (status) {
            VpnStatus.CONNECTED -> if (_connectedAt.value == 0L) _connectedAt.value = System.nanoTime() / 1_000_000
            VpnStatus.DISCONNECTED, VpnStatus.DISCONNECTING -> _connectedAt.value = 0L
            VpnStatus.CONNECTING -> {}
        }
        _status.value = status
    }

    internal fun setTunnel(state: TunnelState?) {
        _tunnel.value = state
    }

    internal fun fail(message: String) {
        _error.value = message
    }
}
