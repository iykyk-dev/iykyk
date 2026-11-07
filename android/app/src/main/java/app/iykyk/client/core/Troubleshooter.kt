package app.iykyk.client.core

import android.content.Context
import app.iykyk.client.vpn.IykykVpnService
import app.iykyk.client.vpn.TunnelState
import app.iykyk.client.vpn.VpnState
import app.iykyk.client.vpn.VpnStatus
import app.iykyk.core.iykyk.Iykyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Не работает?": checks the network, the subscription, the servers and the
 * tunnel one by one, and proposes one fix. Every step goes to the app log, so
 * a report sent afterwards shows what was found.
 */
object Troubleshooter {
    private val _state = MutableStateFlow<Diagnosis?>(null)
    val state: StateFlow<Diagnosis?> = _state

    fun close() {
        _state.value = null
    }

    suspend fun run(context: Context) {
        if (_state.value?.done == false) return
        var d = Diagnosis()
        fun set(next: Diagnosis) {
            d = next
            _state.value = next
        }
        AppLog.i("diagnosis: started")

        // 1. The network itself. IYKYK is excluded from its own tunnel, so this goes direct.
        set(d.with(DiagCheck.INTERNET, DiagState.RUNNING))
        val online = withContext(Dispatchers.IO) { reachable("https://www.gstatic.com/generate_204") || reachable("https://ya.ru") }
        AppLog.i("diagnosis: internet ${if (online) "ok" else "fail"}")
        if (!online) {
            set(
                d.with(DiagCheck.INTERNET, DiagState.FAIL)
                    .with(DiagCheck.SUBSCRIPTION, DiagState.SKIPPED)
                    .with(DiagCheck.SERVERS, DiagState.SKIPPED)
                    .with(DiagCheck.TUNNEL, DiagState.SKIPPED)
                    .copy(done = true, fix = DiagFix.NO_INTERNET),
            )
            return
        }
        set(d.with(DiagCheck.INTERNET, DiagState.OK))

        // 2. The subscription: fresh data, device limit, expiry and traffic.
        set(d.with(DiagCheck.SUBSCRIPTION, DiagState.RUNNING))
        var subFix: DiagFix? = null
        if (ProfileStore.profiles.value.subscription != null) {
            try {
                ProfileStore.refresh(context)
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                AppLog.i("diagnosis: subscription refresh failed: $message")
                if (message.startsWith("hwid:")) subFix = DiagFix.DEVICE_LIMIT
            }
            val sub = ProfileStore.profiles.value.subscription
            if (subFix == null && sub != null) {
                val days = sub.daysLeft()
                val outOfTraffic = sub.total > 0 && sub.upload + sub.download >= sub.total
                if (sub.hwidLimitReached) subFix = DiagFix.DEVICE_LIMIT
                else if ((days != null && days < 0) || outOfTraffic) subFix = DiagFix.RENEW
                AppLog.i("diagnosis: subscription days=$days outOfTraffic=$outOfTraffic hwidFull=${sub.hwidLimitReached}")
            }
        }
        if (subFix != null) {
            set(
                d.with(DiagCheck.SUBSCRIPTION, DiagState.FAIL)
                    .with(DiagCheck.SERVERS, DiagState.SKIPPED)
                    .with(DiagCheck.TUNNEL, DiagState.SKIPPED)
                    .copy(done = true, fix = subFix),
            )
            return
        }
        set(d.with(DiagCheck.SUBSCRIPTION, DiagState.OK))

        // 3. The servers: as configured, then with fragmentation, then with the other engine.
        set(d.with(DiagCheck.SERVERS, DiagState.RUNNING))
        val profiles = ProfileStore.profiles.value
        val links = profiles.servers.map { it.link }
        val settings = SettingsStore.settings.value
        val base = ping(context, links, settings.fragment, settings.preferXray)
        val working = base.count { it > 0 }
        val detail = "$working / ${links.size}"
        AppLog.i("diagnosis: servers $detail answer")
        var fix = DiagFix.NONE
        var serverIndex = -1
        val currentOk = base.getOrNull(profiles.selected)?.let { it > 0 } == true
        if (working == 0) {
            val fragmented = if (!settings.fragment) ping(context, links, true, settings.preferXray) else base
            val otherEngine = ping(context, links, settings.fragment, !settings.preferXray)
            fix = when {
                fragmented.any { it > 0 } -> DiagFix.ENABLE_FRAGMENT
                otherEngine.any { it > 0 } -> DiagFix.SWITCH_ENGINE
                else -> DiagFix.SERVERS_DOWN
            }
            AppLog.i("diagnosis: no server answers, proposing $fix")
            set(d.with(DiagCheck.SERVERS, DiagState.FAIL, detail))
        } else if (!currentOk) {
            serverIndex = base.indices.filter { base[it] > 0 }.minBy { base[it] }
            fix = DiagFix.SWITCH_SERVER
            AppLog.i("diagnosis: current server is down, best is #$serverIndex")
            set(d.with(DiagCheck.SERVERS, DiagState.WARN, detail))
        } else {
            set(d.with(DiagCheck.SERVERS, DiagState.OK, detail))
        }

        // 4. The tunnel, if connected: does data actually go through it?
        if (fix == DiagFix.NONE) {
            if (VpnState.status.value == VpnStatus.CONNECTED) {
                set(d.with(DiagCheck.TUNNEL, DiagState.RUNNING))
                // A fresh check, not the result of the last one.
                VpnState.setTunnel(TunnelState.Checking)
                IykykVpnService.check(context)
                val result = withTimeoutOrNull(12_000) {
                    VpnState.tunnel.first { it is TunnelState.Ok || it is TunnelState.Failed || it is TunnelState.NoInternet }
                }
                AppLog.i("diagnosis: tunnel $result")
                if (result is TunnelState.Ok) {
                    set(d.with(DiagCheck.TUNNEL, DiagState.OK, result.country.ifBlank { result.ip }))
                } else {
                    fix = DiagFix.RECONNECT
                    set(d.with(DiagCheck.TUNNEL, DiagState.FAIL))
                }
            } else {
                fix = DiagFix.CONNECT
                set(d.with(DiagCheck.TUNNEL, DiagState.WARN))
            }
        } else {
            set(d.with(DiagCheck.TUNNEL, DiagState.SKIPPED))
        }

        set(
            d.copy(
                done = true,
                fix = fix,
                serverIndex = serverIndex,
                serverName = profiles.servers.getOrNull(serverIndex)?.name.orEmpty(),
            ),
        )
        AppLog.i("diagnosis: done, fix=$fix")
    }

    private suspend fun ping(context: Context, links: List<String>, fragment: Boolean, preferXray: Boolean): List<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = Iykyk.realPingServers(
                    JSONArray(links).toString(),
                    SettingsStore.coreOptions(context, fragment = fragment, preferXray = preferXray),
                )
                val results = JSONArray(json)
                links.indices.map { results.optInt(it, -1) }
            }.getOrElse { links.map { -1 } }
        }

    private fun reachable(url: String): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.instanceFollowRedirects = false
        try {
            connection.responseCode in 200..399
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
