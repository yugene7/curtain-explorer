package my.neuton.curtainexplorer

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny web server so a phone on the car's Wi-Fi hotspot can open the report
 * and copy it. It only serves the report text; it accepts no input.
 */
class ReportServer(private val port: Int = 8080) {

    @Volatile var report: String = "No scan yet. Tap SCAN on the car screen first."
    private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        socket = try { ServerSocket(port) } catch (_: Throwable) { null }
        val s = socket ?: return
        thread(isDaemon = true, name = "report-server") {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: Throwable) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(c: Socket) {
        c.use {
            try {
                // Read and discard the request line + headers.
                val reader = it.getInputStream().bufferedReader()
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val body = report.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Disposition: inline; filename=\"curtain-report.txt\"\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                it.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
            } catch (_: Throwable) {}
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
    }

    /** Addresses a phone on the same network could use. */
    fun urls(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:$port" }
    } catch (_: Throwable) { emptyList() }
}
