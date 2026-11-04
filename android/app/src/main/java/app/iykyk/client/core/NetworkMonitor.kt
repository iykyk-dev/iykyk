package app.iykyk.client.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import app.iykyk.core.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * Tracks the real (non-VPN) default network and reports it to sing-box,
 * so connections are rebuilt when the phone switches between Wi-Fi and mobile data.
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkChanged: (Network?) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val thread = HandlerThread("iykyk-network").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    var network: Network? = null
        private set

    @Volatile
    private var listener: InterfaceUpdateListener? = null

    // NetworkRequest.Builder() includes NOT_VPN by default, so our own tunnel is never reported.
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            this@NetworkMonitor.network = network
            notifyChanged()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == this@NetworkMonitor.network) notifyChanged()
        }

        override fun onLost(network: Network) {
            if (network == this@NetworkMonitor.network) {
                this@NetworkMonitor.network = null
                notifyChanged()
            }
        }
    }

    fun start() {
        network = connectivity.activeNetwork
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            // REQUEST (not LISTEN) gives exactly the default network, not every matching one.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                connectivity.requestNetwork(request, callback, handler)
            else ->
                connectivity.registerDefaultNetworkCallback(callback, handler)
        }
    }

    fun stop() {
        listener = null
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        thread.quitSafely()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        notifyChanged()
    }

    private fun notifyChanged() {
        val current = network
        onNetworkChanged(current)
        val listener = listener ?: return
        if (current == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val name = connectivity.getLinkProperties(current)?.interfaceName ?: return
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: return
        val capabilities = connectivity.getNetworkCapabilities(current)
        val expensive = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        listener.updateDefaultInterface(name, index, expensive, false)
    }
}
