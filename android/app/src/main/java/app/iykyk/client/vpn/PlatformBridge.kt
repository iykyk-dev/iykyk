package app.iykyk.client.vpn

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.os.Build
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import app.iykyk.client.core.NetworkMonitor
import app.iykyk.core.libbox.BridgeOptions
import app.iykyk.core.libbox.BridgeSession
import app.iykyk.core.libbox.ConnectionOwner
import app.iykyk.core.libbox.InterfaceUpdateListener
import app.iykyk.core.libbox.Libbox
import app.iykyk.core.libbox.LocalDNSTransport
import app.iykyk.core.libbox.NeighborUpdateListener
import app.iykyk.core.libbox.NetworkInterfaceIterator
import app.iykyk.core.libbox.Notification
import app.iykyk.core.libbox.PlatformInterface
import app.iykyk.core.libbox.PlatformUser
import app.iykyk.core.libbox.RoutePrefix
import app.iykyk.core.libbox.RoutePrefixIterator
import app.iykyk.core.libbox.ShellSession
import app.iykyk.core.libbox.StringIterator
import app.iykyk.core.libbox.TunOptions
import app.iykyk.core.libbox.WIFIState
import java.net.InetAddress
import java.net.NetworkInterface
import app.iykyk.core.libbox.NetworkInterface as BoxNetworkInterface

/**
 * What sing-box asks of the operating system: opening the TUN, protecting sockets,
 * and describing the network. Modeled on the official sing-box Android client.
 */
class PlatformBridge(
    private val service: IykykVpnService,
    private val networkMonitor: NetworkMonitor,
) : PlatformInterface {

    private val connectivity = service.getSystemService(ConnectivityManager::class.java)

    // --- Sockets ----------------------------------------------------------------------

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!service.protect(fd)) error("failed to protect socket")
    }

    // --- TUN --------------------------------------------------------------------------

    override fun openTun(options: TunOptions): Int {
        if (android.net.VpnService.prepare(service) != null) error("VPN permission is missing")

        val builder = service.Builder()
            .setSession("IYKYK")
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        options.inet4Address.forEach { builder.addAddress(it.address(), it.prefix()) }
        options.inet6Address.forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                options.dnsServerAddress.forEach { builder.addDnsServer(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addRoutesWithExclusions(builder, options)
            } else {
                options.inet4RouteRange.forEach { builder.addRoute(it.address(), it.prefix()) }
                options.inet6RouteRange.forEach { builder.addRoute(it.address(), it.prefix()) }
            }
            options.includePackage.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
            }
            options.excludePackage.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
            }
        }

        val tun = builder.establish() ?: error("VPN permission was revoked")
        service.attachTun(tun)
        return tun.fd
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun addRoutesWithExclusions(builder: android.net.VpnService.Builder, options: TunOptions) {
        val inet4Routes = options.inet4RouteAddress.toList()
        if (inet4Routes.isNotEmpty()) {
            inet4Routes.forEach { builder.addRoute(it.toIpPrefix()) }
        } else if (options.inet4Address.hasNext()) {
            builder.addRoute("0.0.0.0", 0)
        }
        val inet6Routes = options.inet6RouteAddress.toList()
        if (inet6Routes.isNotEmpty()) {
            inet6Routes.forEach { builder.addRoute(it.toIpPrefix()) }
        } else if (options.inet6Address.hasNext()) {
            builder.addRoute("::", 0)
        }
        options.inet4RouteExcludeAddress.forEach { builder.excludeRoute(it.toIpPrefix()) }
        options.inet6RouteExcludeAddress.forEach { builder.excludeRoute(it.toIpPrefix()) }
    }

    // --- Network description ----------------------------------------------------------

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = mutableListOf<BoxNetworkInterface>()
        for (network in connectivity.allNetworks) {
            val link = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val name = link.interfaceName ?: continue
            val javaInterface = javaInterfaces.find { it.name == name } ?: continue

            val box = BoxNetworkInterface()
            box.setName(name)
            box.setIndex(javaInterface.index)
            runCatching { box.setMTU(javaInterface.mtu) }
            box.setAddresses(
                StringArray(
                    javaInterface.interfaceAddresses.map {
                        it.address.hostAddress.orEmpty().substringBefore('%') + "/" + it.networkPrefixLength
                    },
                ),
            )
            box.setDNSServer(StringArray(link.dnsServers.mapNotNull { it.hostAddress }))
            box.setGateway(
                StringArray(
                    link.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway }
                        .filterNot { it.isAnyLocalAddress }
                        .mapNotNull { it.hostAddress },
                ),
            )
            box.setType(
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                },
            )
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (javaInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (javaInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (javaInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            box.setFlags(flags)
            box.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            result += box
        }
        return InterfaceArray(result)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {}

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun registerMyInterface(name: String) {}

    // --- Features IYKYK does not use --------------------------------------------------

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner = throw UnsupportedOperationException("process rules are not used")

    override fun sendNotification(notification: Notification) {
        Log.i(TAG, "core notification: ${notification.title}: ${notification.body}")
    }

    override fun cancelNotification(identifier: String, typeID: Int) {}

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw UnsupportedOperationException()
    }

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = throw UnsupportedOperationException()

    override fun lookupUser(username: String): PlatformUser = throw UnsupportedOperationException()

    override fun lookupSFTPServer(): String = throw UnsupportedOperationException()

    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException()

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession = throw UnsupportedOperationException()

    private companion object {
        const val TAG = "IykykPlatform"
    }
}

// --- gomobile iterator helpers -----------------------------------------------------------

class StringArray(private val items: List<String>) : StringIterator {
    private var index = 0
    override fun len(): Int = items.size
    override fun hasNext(): Boolean = index < items.size
    override fun next(): String = items[index++]
}

private class InterfaceArray(private val items: List<BoxNetworkInterface>) : NetworkInterfaceIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun next(): BoxNetworkInterface = items[index++]
}

private inline fun StringIterator.forEach(action: (String) -> Unit) {
    while (hasNext()) action(next())
}

private inline fun RoutePrefixIterator.forEach(action: (RoutePrefix) -> Unit) {
    while (hasNext()) action(next())
}

private fun RoutePrefixIterator.toList(): List<RoutePrefix> {
    val list = mutableListOf<RoutePrefix>()
    forEach { list += it }
    return list
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RoutePrefix.toIpPrefix(): IpPrefix = IpPrefix(InetAddress.getByName(address()), prefix())
