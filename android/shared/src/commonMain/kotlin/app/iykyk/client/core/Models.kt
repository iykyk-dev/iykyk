package app.iykyk.client.core

/** Days before the end of the subscription when the app starts reminding. */
const val EXPIRY_REMIND_DAYS = 3

data class ServerInfo(
    val name: String,
    val protocol: String,
    val address: String,
    val link: String = "",
    /** e.g. "VLESS · gRPC · REALITY". */
    val details: String = "",
)

data class SubscriptionInfo(
    val url: String,
    val title: String,
    val upload: Long,
    val download: Long,
    /** Bytes; 0 means unlimited. */
    val total: Long,
    /** Unix seconds; 0 means no expiry. */
    val expire: Long,
    val supportUrl: String,
    val announce: String,
    /** Provider's site (Profile-Web-Page-Url), used for "Renew". */
    val webPageUrl: String = "",
    val updatedAt: Long,
    val updateHours: Int = 0,
    /** Backup subscription URLs announced by the panel. */
    val fallbacks: List<String> = emptyList(),
    /** Provider routing (JSON) from the Iykyk-Routing header, or "". */
    val routing: String = "",
    /** Remnawave limits devices for this subscription (x-hwid-active). */
    val hwidLimited: Boolean = false,
    /** The device limit is reached (x-hwid-max-devices-reached). */
    val hwidLimitReached: Boolean = false,
    /** Where to ask about connected devices (Iykyk-Devices-Api), or "". */
    val devicesApi: String = "",
)

/** Whole days until the subscription expires (negative once expired), or null if it never does. */
fun SubscriptionInfo.daysLeft(now: Long = System.currentTimeMillis()): Int? {
    if (expire <= 0) return null
    val millis = expire * 1000 - now
    return if (millis < 0) -1 else (millis / 86_400_000L).toInt()
}

/** «Поддержка» in the app: the project's support bot, whatever the panel sends. */
const val SUPPORT_URL = "https://t.me/dlyasvoih_support_bot"

/** Where "Renew" leads: the provider's site, else the support link. */
val SubscriptionInfo.renewUrl: String get() = webPageUrl.ifBlank { supportUrl }

data class Profiles(
    val subscription: SubscriptionInfo? = null,
    val servers: List<ServerInfo> = emptyList(),
    val selected: Int = 0,
) {
    val current: ServerInfo? get() = servers.getOrNull(selected) ?: servers.firstOrNull()

    val hasServers: Boolean get() = servers.isNotEmpty()
}


/** Which apps go through the VPN. */
enum class AppMode { ALL, ONLY_SELECTED, EXCEPT_SELECTED }

/** Light, dark or follow the system. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    /** Russian sites and IPs go directly, not through the VPN. On by default: many of them block foreign IPs. */
    val bypassRussia: Boolean = true,
    val appMode: AppMode = AppMode.ALL,
    val apps: Set<String> = emptySet(),
    /** Split TLS handshakes to get past DPI that blocks by server name. */
    val fragment: Boolean = false,
    /** Sort the server list by the last ping. */
    val sortByPing: Boolean = false,
    /** Connect when the app is opened. */
    val autoConnectOnLaunch: Boolean = false,
    /** Connect when the phone starts. */
    val autoConnectOnBoot: Boolean = false,
    /** Remind before the subscription expires. */
    val expiryReminders: Boolean = true,
    /** DNS for tunnelled traffic: cloudflare, google, quad9, adguard or a DoH URL. */
    val remoteDns: String = "cloudflare",
    /** DNS for proxy servers and bypassed sites: yandex, cloudflare, google or an IP. */
    val directDns: String = "yandex",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Run VLESS/VMess/Trojan/Shadowsocks through Xray instead of sing-box (uses more battery). */
    val preferXray: Boolean = false,
)


data class DeviceInfo(
    val id: String,
    val platform: String,
    val os: String,
    val model: String,
    val app: String,
    val added: String,
    val lastSeen: String,
    val current: Boolean,
)

/** Devices on the subscription: [used] of [limit] (0 = unlimited). */
data class DevicesInfo(val used: Int, val limit: Int, val devices: List<DeviceInfo>)
