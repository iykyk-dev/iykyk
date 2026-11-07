package app.iykyk.client.tv

import app.iykyk.client.core.Lang

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * "Send to TV": the TV shows a QR code with its LAN address and a one-time token,
 * the phone scans it and posts the subscription URL straight to the TV over Wi-Fi.
 * Nothing leaves the local network. IYKYK itself is excluded from the tunnel, so
 * this works with the VPN on at either end. The TV imports the URL as usual,
 * so the provider lock still applies.
 */
object TvShare {
    const val SCHEME = "iykyk-tv://"
    private const val MAX_BODY = 16 * 1024

    /** The device's IPv4 address on Wi-Fi or Ethernet (not the VPN), or null. */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && !it.name.startsWith("tun") }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** Waits for one subscription from a phone; [onReceived] runs on a background thread. */
    class Receiver(private val onReceived: (String) -> Unit) : Closeable {
        private val token = randomToken()
        private val server = ServerSocket(0)

        /** What the QR code holds, or null without a local network. */
        val code: String? = localAddress()?.let { "$SCHEME$it:${server.localPort}/$token" }

        init {
            thread(isDaemon = true, name = "tv-receiver") {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    val text = runCatching { socket.use(::handle) }.getOrNull()
                    if (text != null) onReceived(text)
                }
            }
        }

        private fun handle(socket: Socket): String? {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val request = readLine(input) ?: return null
            var length = 0
            while (true) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) break
                if (line.startsWith("content-length:", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val parts = request.split(' ')
            var text: String? = null
            if (parts.size >= 2 && parts[0] == "POST" && parts[1] == "/import/$token" && length in 1..MAX_BODY) {
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                text = String(body, 0, read, Charsets.UTF_8).trim().ifEmpty { null }
            }
            val status = if (text != null) "200 OK" else "403 Forbidden"
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            return text
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    /** Sends [text] to the TV whose QR code was scanned. Blocking: call off the main thread. */
    fun send(code: String, text: String) {
        val rest = code.trim().removePrefix(SCHEME)
        val hostPort = rest.substringBefore('/')
        val token = rest.substringAfter('/', "").trimEnd('/')
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull()
        require(port != null && token.isNotEmpty() && token.all { it.isLetterOrDigit() }) { Lang.t("неверный код", "invalid code") }
        // Only a literal address in the local network: a QR code must not make the
        // phone send its subscription anywhere on the internet.
        require(Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)) { Lang.t("неверный адрес", "invalid address") }
        val address = InetAddress.getByName(host)
        require(address.isSiteLocalAddress) { Lang.t("адрес не в локальной сети", "the address is not on the local network") }

        val body = text.toByteArray(Charsets.UTF_8)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), 5_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(
                    ("POST /import/$token HTTP/1.1\r\nHost: $hostPort\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                write(body)
                flush()
            }
            val status = readLine(BufferedInputStream(socket.getInputStream())).orEmpty()
            check(status.contains(" 200")) { status.ifEmpty { Lang.t("телевизор не ответил", "the TV did not respond") } }
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 4096) return null
            line.append(c.toChar())
        }
    }

    private fun randomToken(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
