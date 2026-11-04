package app.iykyk.client.vpn

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ;

    val isActive: Boolean get() = this == CONNECTING || this == CONNECTED
}

/** What the last tunnel check found (see IykykVpnService.check). */
sealed interface TunnelState {
    data object Checking : TunnelState
    data class Ok(val ip: String, val country: String, val latency: Int) : TunnelState
    data object NoInternet : TunnelState
    data object Reconnecting : TunnelState
    data object Failed : TunnelState
}
