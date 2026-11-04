package app.iykyk.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/** Windows-specific bits: start with Windows, and one running copy of the app. */
object WindowsIntegration {
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "IYKYK").apply { mkdirs() }

    /** The launcher (IYKYK.exe) that jpackage started us from. */
    private val appPath: String? get() = System.getProperty("jpackage.app-path")

    /** Registers (or removes) "IYKYK.exe --minimized" in the user's Run key. */
    fun setAutostart(enabled: Boolean) {
        val command = if (enabled) {
            val exe = appPath ?: return
            listOf("reg", "add", RUN_KEY, "/v", "IYKYK", "/t", "REG_SZ", "/d", "\"$exe\" --minimized", "/f")
        } else {
            listOf("reg", "delete", RUN_KEY, "/v", "IYKYK", "/f")
        }
        runCatching { ProcessBuilder(command).redirectErrorStream(true).start().waitFor() }
    }

    private var lock: FileLock? = null

    /**
     * True if this is the only running copy. Otherwise asks the running one to
     * show its window and returns false. [onShow] runs when another copy starts.
     */
    fun acquireSingleInstance(onShow: () -> Unit): Boolean {
        val portFile = File(dir, "app.port")
        val channel = RandomAccessFile(File(dir, "app.lock"), "rw").channel
        val acquired = runCatching { channel.tryLock() }.getOrNull()
        if (acquired == null) {
            runCatching {
                val port = portFile.readText().trim().toInt()
                Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write('s'.code) }
            }
            return false
        }
        lock = acquired
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        portFile.writeText(server.localPort.toString())
        thread(isDaemon = true, name = "single-instance") {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { if (it.getInputStream().read() == 's'.code) onShow() }
            }
        }
        return true
    }
}
