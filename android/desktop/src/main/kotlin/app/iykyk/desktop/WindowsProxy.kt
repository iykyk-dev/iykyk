package app.iykyk.desktop

import java.io.File
import java.util.Properties

/**
 * The Windows system proxy (the "Proxy" page in Settings) for the "system proxy"
 * mode: on connect it points to IYKYK's local proxy, on disconnect the user's
 * previous setting comes back. Set per user, so the app (not the service) does it.
 */
object WindowsProxy {
    /** Where the core listens in this mode (127.0.0.1 only). */
    const val PORT = 10809
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    private const val OURS = "127.0.0.1:$PORT"
    // Local addresses never go through the proxy.
    private const val BYPASS = "<local>;localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.2*;172.30.*;172.31.*;192.168.*"

    private val saved = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "IYKYK/proxy-before.properties")

    @Synchronized
    fun enable() {
        if (!isOurs()) {
            // Remember the user's own setting to restore it later.
            val before = Properties().apply {
                setProperty("enable", query("ProxyEnable") ?: "0x0")
                query("ProxyServer")?.let { setProperty("server", it) }
                query("ProxyOverride")?.let { setProperty("override", it) }
            }
            runCatching { saved.parentFile.mkdirs(); saved.outputStream().use { before.store(it, "proxy before IYKYK") } }
        }
        reg("add", KEY, "/v", "ProxyServer", "/t", "REG_SZ", "/d", OURS, "/f")
        reg("add", KEY, "/v", "ProxyOverride", "/t", "REG_SZ", "/d", BYPASS, "/f")
        reg("add", KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f")
        refresh()
    }

    /** Restores the previous setting, only if the proxy is still ours (the user may have changed it). */
    @Synchronized
    fun disableIfOurs() {
        if (!isOurs()) return
        val before = Properties().apply { runCatching { saved.inputStream().use { load(it) } } }
        val server = before.getProperty("server")
        if (server != null && server != OURS) {
            reg("add", KEY, "/v", "ProxyServer", "/t", "REG_SZ", "/d", server, "/f")
            before.getProperty("override")?.let { reg("add", KEY, "/v", "ProxyOverride", "/t", "REG_SZ", "/d", it, "/f") }
        }
        val enable = if (before.getProperty("enable", "0x0").endsWith("1") && server != null && server != OURS) "1" else "0"
        reg("add", KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", enable, "/f")
        saved.delete()
        refresh()
    }

    private fun isOurs(): Boolean =
        query("ProxyEnable")?.endsWith("1") == true && query("ProxyServer") == OURS

    /** A value from `reg query`, e.g. "0x1" or "127.0.0.1:10809", or null. */
    private fun query(name: String): String? = runCatching {
        val process = ProcessBuilder("reg", "query", KEY, "/v", name).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        out.lines().firstOrNull { it.trim().startsWith(name) }
            ?.trim()?.split(Regex("\\s+"), limit = 3)?.getOrNull(2)?.trim()
    }.getOrNull()

    private fun reg(vararg args: String) {
        runCatching { ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start().waitFor() }
    }

    /** Tells running programs (browsers) that the proxy changed, like the Settings page does. */
    private fun refresh() {
        val script = """
            ${'$'}sig = '[DllImport("wininet.dll", SetLastError=true)] public static extern bool InternetSetOption(System.IntPtr h, int o, System.IntPtr b, int l);'
            ${'$'}w = Add-Type -MemberDefinition ${'$'}sig -Name WinInet -Namespace IYKYK -PassThru
            [void]${'$'}w::InternetSetOption([System.IntPtr]::Zero, 39, [System.IntPtr]::Zero, 0)
            [void]${'$'}w::InternetSetOption([System.IntPtr]::Zero, 37, [System.IntPtr]::Zero, 0)
        """.trimIndent()
        runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start().waitFor()
        }
    }
}
