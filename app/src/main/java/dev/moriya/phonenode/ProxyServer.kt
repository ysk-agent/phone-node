package dev.moriya.phonenode

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Native forward proxy, the same role as mac_proxy_server.py on the Mac:
 * clients on the tailnet connect here, and their traffic leaves through
 * this phone's network, so the egress IP is this phone's location.
 *
 * Supports CONNECT (HTTPS tunnelling) and plain HTTP proxy requests in
 * absolute-URI form. Access is limited to [ALLOWED_SOURCES] — the known
 * tailnet addresses of Moriya's own devices — mirroring the Mac proxy's
 * --allow list. Everything else is rejected.
 *
 * It runs in the main app process rather than inside proot on purpose:
 * it must start without waiting for the rootfs expansion, its liveness
 * is simple to supervise, and the proxy-only phones never need proot.
 */
class ProxyServer {
    companion object {
        const val PORT = 8080
        private val ALLOWED_SOURCES = setOf(
            "100.95.80.115",  // Muse VM (muse)
            "100.100.242.8",  // Mac (m1)
            "100.102.129.64"  // Android (moto)
        )
        private const val HEADER_LIMIT = 64 * 1024
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "proxy-server", isDaemon = true) {
            try {
                val socket = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
                serverSocket = socket
                NodeState.proxyListening = true
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(name = "proxy-client", isDaemon = true) {
                        handle(client)
                    }
                }
            } catch (e: Exception) {
                NodeState.proxyListening = false
            }
        }
    }

    fun stop() {
        running = false
        NodeState.proxyListening = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            val source = c.inetAddress?.hostAddress
            if (source == null || source !in ALLOWED_SOURCES) {
                writeSimple(c.getOutputStream(), 403, "Forbidden")
                return
            }
            c.soTimeout = 120_000
            val input = c.getInputStream()
            val headerBytes = readHeaderBlock(input) ?: return
            val headerText = String(headerBytes, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestParts = lines[0].split(" ")
            if (requestParts.size < 3) {
                writeSimple(c.getOutputStream(), 400, "Bad Request")
                return
            }
            val method = requestParts[0].uppercase()
            val target = requestParts[1]
            if (method == "CONNECT") {
                handleConnect(c, input, target)
            } else {
                handleHttp(c, input, lines, method, target, requestParts[2])
            }
        }
    }

    private fun handleConnect(client: Socket, clientIn: InputStream, authority: String) {
        val (host, port) = parseAuthority(authority, 443) ?: run {
            writeSimple(client.getOutputStream(), 400, "Bad Request")
            return
        }
        val upstream = try {
            Socket(host, port)
        } catch (e: Exception) {
            writeSimple(client.getOutputStream(), 502, "Bad Gateway")
            return
        }
        upstream.use { up ->
            up.soTimeout = 120_000
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            relayBoth(client, clientIn, up)
        }
    }

    private fun handleHttp(
        client: Socket,
        clientIn: InputStream,
        lines: List<String>,
        method: String,
        absoluteUri: String,
        version: String
    ) {
        // absoluteUri looks like http://host[:port]/path?query
        val uri = try {
            java.net.URI(absoluteUri)
        } catch (e: Exception) {
            writeSimple(client.getOutputStream(), 400, "Bad Request")
            return
        }
        val host = uri.host ?: run {
            writeSimple(client.getOutputStream(), 400, "Bad Request")
            return
        }
        val port = if (uri.port == -1) 80 else uri.port
        var path = uri.rawPath ?: "/"
        if (path.isEmpty()) path = "/"
        if (uri.rawQuery != null) path += "?" + uri.rawQuery

        val upstream = try {
            Socket(host, port)
        } catch (e: Exception) {
            writeSimple(client.getOutputStream(), 502, "Bad Gateway")
            return
        }
        upstream.use { up ->
            up.soTimeout = 120_000
            val upOut = up.getOutputStream()
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val name = line.substringBefore(':').trim().lowercase()
                if (name == "proxy-connection" || name == "proxy-authorization") continue
                head.append(line).append("\r\n")
            }
            head.append("\r\n")
            upOut.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            upOut.flush()
            relayBoth(client, clientIn, up)
        }
    }

    /** Pumps bytes in both directions until either side closes. */
    private fun relayBoth(client: Socket, clientIn: InputStream, upstream: Socket) {
        val upIn = upstream.getInputStream()
        val clientOut = client.getOutputStream()
        val upOut = upstream.getOutputStream()
        val t = thread(name = "proxy-pump-up", isDaemon = true) {
            pump(upIn, clientOut)
            try { client.shutdownOutput() } catch (e: Exception) { }
        }
        pump(clientIn, upOut)
        try { upstream.shutdownOutput() } catch (e: Exception) { }
        t.join(5_000)
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
            // Peer reset or timeout; the other direction ends via shutdown.
        }
    }

    /**
     * Reads one byte at a time until the end of the header block. Byte-wise
     * reading is deliberate: it never buffers body bytes that must later be
     * relayed verbatim.
     */
    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var state = 0 // number of matched chars of the \r\n\r\n terminator
        while (out.size() < HEADER_LIMIT) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> if (b == '\r'.code) 1 else 0
            }
            if (state == 4) break
        }
        return out.toByteArray()
    }

    private fun parseAuthority(authority: String, defaultPort: Int): Pair<String, Int>? {
        val idx = authority.lastIndexOf(':')
        return if (idx > 0) {
            val port = authority.substring(idx + 1).toIntOrNull() ?: return null
            authority.substring(0, idx) to port
        } else {
            authority to defaultPort
        }
    }

    private fun writeSimple(out: OutputStream, code: Int, message: String) {
        try {
            val text = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(text.toByteArray())
            out.flush()
        } catch (e: Exception) {
            // Client already gone.
        }
    }
}
