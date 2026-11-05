package dev.moriya.phonenode

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Minimal HTTP status endpoint on loopback, port [PORT].
 *
 * The helper app polls this endpoint. It deliberately answers from the same
 * process that runs the node service, and reports [NodeState] verbatim so a
 * death masked by a process restart is still visible in the values.
 */
class StatusServer {
    companion object {
        const val PORT = 18090
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "status-server", isDaemon = true) {
            try {
                val socket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(name = "status-client", isDaemon = true) {
                        handle(client)
                    }
                }
            } catch (e: Exception) {
                // Port busy or socket closed during shutdown; nothing to do.
            }
        }
    }

    private fun handle(client: java.net.Socket) {
        client.use { s ->
            s.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            reader.readLine() // request line; content is not needed
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val now = System.currentTimeMillis()
            val body = buildString {
                append("{")
                append("\"app\":\"phonenode\",")
                append("\"serviceStartedAtMs\":").append(NodeState.serviceStartedAtMs).append(",")
                append("\"heartbeat\":").append(NodeState.heartbeat.get()).append(",")
                append("\"uptimeMs\":").append(NodeState.uptimeMs(now)).append(",")
                append("\"proxyListening\":").append(NodeState.proxyListening).append(",")
                append("\"prootRunning\":").append(NodeState.prootRunning)
                append("}")
            }
            val writer = PrintWriter(s.getOutputStream())
            writer.print("HTTP/1.0 200 OK\r\n")
            writer.print("Content-Type: application/json\r\n")
            writer.print("Content-Length: ${body.toByteArray().size}\r\n")
            writer.print("Connection: close\r\n\r\n")
            writer.print(body)
            writer.flush()
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        serverSocket = null
    }
}
