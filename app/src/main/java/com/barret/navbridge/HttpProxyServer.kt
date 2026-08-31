package com.barret.navbridge

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A small HTTP proxy, so the dashboard can reach the internet through this
 * phone rather than through its own connection.
 *
 * WHY THIS BELONGS HERE. The dashboard is a separate device on the phone's
 * hotspot, and an Android VPN does not cover tethered devices -- the tunnel is
 * per-app, and the board is not an app. This app IS an app, so its sockets go
 * through whatever VPN is active. Anything the board sends through this proxy
 * therefore takes the same route as the phone's own traffic. That is exactly
 * what a separate proxy app was being used for, and it is a few hundred lines,
 * so it may as well live next to the GPS feed and the route server the board
 * already talks to.
 *
 * WHAT IT SUPPORTS. Two forms, which is all a proxy needs to be useful here:
 *
 *   CONNECT host:port      opens a tunnel and copies bytes both ways without
 *                          looking at them. This is what https goes through --
 *                          the TLS session is end to end between the board and
 *                          the far server, and this process only sees
 *                          ciphertext.
 *   GET http://host/path   an ordinary request with an absolute URI, forwarded
 *                          to the host with the URI rewritten to origin form.
 *
 * The dashboard uses CONNECT for both, including for plain http on port 80
 * (see navProxyConnectPlain in gps_nav.cpp), so the second form is there for
 * completeness rather than necessity.
 *
 * Bound to 0.0.0.0 so it answers on whichever interface the board reaches the
 * phone by -- hotspot, shared Wi-Fi, USB tethering -- without anything having
 * to be configured or typed in. The board finds the phone's address by itself
 * from the GPS packets it is already receiving.
 */
class HttpProxyServer(private val port: Int = DEFAULT_PORT) {

    companion object {
        private const val TAG = "HttpProxyServer"
        const val DEFAULT_PORT = 8080

        const val PREFS = "nmea_bridge"
        const val KEY_PROXY_ENABLED = "http_proxy_enabled"

        // Generous, because a tunnel is idle whenever the far end is quiet --
        // a map tile being decoded, a long poll waiting. Short enough that a
        // connection whose peer vanished does not sit here for ever.
        private const val SOCKET_TIMEOUT_MS = 120_000

        private const val BUFFER_BYTES = 8 * 1024
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var running = false
        private set

    /** Live tunnels, for the status line on the main screen. */
    private val active = AtomicInteger(0)
    val activeConnections: Int get() = active.get()

    // One thread per connection, from a cached pool: a tunnel spends its life
    // blocked on read, so this cannot be done on a fixed-size pool without
    // starving new connections behind idle ones.
    private val workers = Executors.newCachedThreadPool()

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = socket
            running = true
            thread(name = "proxy-accept") { acceptLoop(socket) }
            Log.i(TAG, "listening on 0.0.0.0:$port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot listen on $port: ${e.message}")
            running = false
            serverSocket = null
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()   // makes the blocking accept() throw, ending the loop
        } catch (e: Exception) {
            // already closed
        }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                workers.execute { handle(client) }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break   // socket closed by stop(), or the listener died
            }
        }
        Log.i(TAG, "accept loop finished")
    }

    private fun handle(client: Socket) {
        active.incrementAndGet()
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // The request line and headers are read a byte at a time up to the
            // blank line. It is not elegant, and it is the only way to stop
            // exactly at the end of the headers: for CONNECT, everything after
            // that blank line belongs to the tunnel and must not be swallowed
            // into a buffer this method is about to discard.
            val header = readHeader(input) ?: return
            val requestLine = header.lineSequence().firstOrNull()?.trim() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            if (parts[0].equals("CONNECT", ignoreCase = true)) {
                tunnel(client, input, output, parts[1])
            } else {
                forward(client, output, parts, header)
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection failed: ${e.message}")
        } finally {
            try { client.close() } catch (e: Exception) { }
            active.decrementAndGet()
        }
    }

    private fun readHeader(input: InputStream): String? {
        val sb = StringBuilder()
        var consecutive = 0
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(c.toChar())
            if (c == '\n'.code) {
                consecutive++
                if (consecutive == 2) return sb.toString()
            } else if (c != '\r'.code) {
                consecutive = 0
            }
        }
        return sb.toString()
    }

    /** CONNECT host:port -- open the far end, answer 200, then copy both ways. */
    private fun tunnel(client: Socket, input: InputStream, output: OutputStream, target: String) {
        val host = target.substringBeforeLast(':', target)
        val port = target.substringAfterLast(':', "443").toIntOrNull() ?: 443

        val upstream = try {
            Socket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                connect(InetSocketAddress(host, port), 15_000)
            }
        } catch (e: Exception) {
            // 502 is the correct answer, and the dashboard reads it: it means
            // this proxy could not reach the host, as opposed to declining to
            // try. Getting that distinction right is what let the board's own
            // diagnostics point at the network instead of at itself.
            Log.w(TAG, "CONNECT $target failed: ${e.message}")
            output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
        output.flush()

        try {
            // Upstream-to-client on this thread, client-to-upstream on another:
            // both directions block on read, so neither can be allowed to wait
            // for the other.
            val up = thread(name = "proxy-up") { copy(input, upstream.getOutputStream()) }
            copy(upstream.getInputStream(), output)
            up.join(1000)
        } finally {
            try { upstream.close() } catch (e: Exception) { }
        }
    }

    /** An ordinary request with an absolute URI, forwarded as origin-form. */
    private fun forward(client: Socket, clientOut: OutputStream, parts: List<String>, header: String) {
        val url = parts[1]
        if (!url.startsWith("http://", ignoreCase = true)) {
            clientOut.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }
        val withoutScheme = url.removePrefix("http://").removePrefix("HTTP://")
        val hostPort = withoutScheme.substringBefore('/')
        val path = "/" + withoutScheme.substringAfter('/', "")
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "80").toIntOrNull() ?: 80

        val upstream = try {
            Socket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                connect(InetSocketAddress(host, port), 15_000)
            }
        } catch (e: Exception) {
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }

        try {
            // Rewrite only the request line; the headers pass through as they
            // are. Proxy-Connection is dropped because it is meaningless to
            // the origin server and some of them object to it.
            val rebuilt = StringBuilder()
            val version = parts.getOrElse(2) { "HTTP/1.1" }
            rebuilt.append(parts[0]).append(' ').append(path).append(' ')
                .append(version).append("\r\n")
            header.lineSequence().drop(1).forEach { line ->
                val trimmed = line.trimEnd('\r')
                if (trimmed.isNotEmpty() && !trimmed.startsWith("Proxy-Connection", true)) {
                    rebuilt.append(trimmed).append("\r\n")
                }
            }
            rebuilt.append("\r\n")

            upstream.getOutputStream().write(rebuilt.toString().toByteArray())
            upstream.getOutputStream().flush()
            copy(upstream.getInputStream(), clientOut)
        } finally {
            try { upstream.close() } catch (e: Exception) { }
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (e: Exception) {
            // Either side closing is the normal end of a tunnel, not a fault.
        }
    }
}
