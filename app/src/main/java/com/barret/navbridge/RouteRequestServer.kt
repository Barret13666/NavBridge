package com.barret.navbridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Listens on UDP port PORT (10111) for route requests from the ESP32 board
 * (gps_nav.cpp's navRouteTaskFn, triggered by tapping a point on the map
 * then pressing GO) and answers them by asking the separately-installed
 * BRouter app (via BRouterClient) to compute an offline route, then
 * replying with the result split across one or more UDP packets.
 *
 * Wire protocol -- see gps_nav.cpp's GPS_NAV_ENABLE_ROUTING comment block
 * for the ESP32 side of this; CHUNK_POINTS here MUST match its
 * NAV_ROUTE_CHUNK_POINTS exactly, or the board will only ever see partial
 * routes:
 *   in:  "RRQ1|<reqId>|<fromLat>|<fromLon>|<toLat>|<toLon>\n"
 *   out (success): "RHD1|<reqId>|OK|<totalPoints>\n"
 *                  then ceil(totalPoints/CHUNK_POINTS) packets:
 *                  "RPT1|<reqId>|<seq>|<lat1>,<lon1>;<lat2>,<lon2>;...\n"
 *   out (failure): "RHD1|<reqId>|ERR|<short reason>\n" (final, no data packets)
 */
class RouteRequestServer(
    private val context: Context,
    // Read fresh on every request (not captured once at start()) so a
    // profile change in MainActivity's spinner takes effect on the very
    // next tap-GO, without needing to restart the foreground service.
    private val routingProfile: () -> String,
) {
    companion object {
        private const val TAG = "RouteRequestServer"
        const val PORT = 10111
        const val CHUNK_POINTS = 40 // MUST match gps_nav.cpp's NAV_ROUTE_CHUNK_POINTS
        private const val SOCKET_TIMEOUT_MS = 1000
    }

    // @Volatile: written from the IO-dispatcher coroutine (runLoop) but read
    // from whatever thread calls stop() (the service's main-thread lifecycle
    // callbacks) -- socket.close() itself is what actually unblocks a
    // pending receive() cross-thread (a standard, safe Java pattern), but
    // this field also gates the while-loop condition below, so it needs to
    // be visible promptly too.
    @Volatile
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var receiveJob: Job? = null

    fun start() {
        if (receiveJob != null) return
        receiveJob = scope.launch { runLoop() }
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
    }

    private fun runLoop() {
        val sock = try {
            DatagramSocket(PORT).apply { soTimeout = SOCKET_TIMEOUT_MS }
        } catch (e: Exception) {
            Log.e(TAG, "could not bind UDP port $PORT: ${e.message}")
            return
        }
        socket = sock
        val buf = ByteArray(1500)
        while (socket === sock) { // becomes false once stop() swaps socket to null
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue // normal -- just lets the while-condition re-check periodically
            } catch (e: Exception) {
                break // socket closed by stop(), or a genuine error -- either way, exit
            }
            val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
            val fromAddress = packet.address
            val fromPort = packet.port
            // handleRequest calls BRouter, which can take several seconds --
            // run it on its own coroutine so a slow route computation never
            // delays receiving (or replying to) the NEXT request.
            scope.launch { handleRequest(sock, fromAddress, fromPort, text) }
        }
    }

    private suspend fun handleRequest(sock: DatagramSocket, replyAddr: InetAddress, replyPort: Int, text: String) {
        val parts = text.trim().split("|")
        if (parts.size != 6 || parts[0] != "RRQ1") {
            Log.w(TAG, "ignoring unrecognized packet: ${text.take(60)}")
            return
        }
        val reqId = parts[1].toIntOrNull()
        val fromLat = parts[2].toDoubleOrNull()
        val fromLon = parts[3].toDoubleOrNull()
        val toLat = parts[4].toDoubleOrNull()
        val toLon = parts[5].toDoubleOrNull()
        if (reqId == null || fromLat == null || fromLon == null || toLat == null || toLon == null) {
            Log.w(TAG, "malformed request: ${text.take(60)}")
            return
        }

        try {
            val result = BRouterClient.route(context, fromLat, fromLon, toLat, toLon, routingProfile())
            sendHeaderOk(sock, replyAddr, replyPort, reqId, result.points.size)
            sendChunks(sock, replyAddr, replyPort, reqId, result.points)
        } catch (e: BRouterClient.RouteException) {
            sendHeaderErr(sock, replyAddr, replyPort, reqId, e.message ?: "unknown error")
        } catch (e: Exception) {
            sendHeaderErr(sock, replyAddr, replyPort, reqId, "internal error: ${e.message}")
        }
    }

    private fun sendHeaderOk(sock: DatagramSocket, addr: InetAddress, port: Int, reqId: Int, totalPoints: Int) {
        send(sock, addr, port, "RHD1|$reqId|OK|$totalPoints\n")
    }

    private fun sendHeaderErr(sock: DatagramSocket, addr: InetAddress, port: Int, reqId: Int, reason: String) {
        // Keep it short -- the ESP32 side only keeps ~64 bytes of this, and
        // it ends up in a small on-screen alert.
        val trimmed = reason.take(60).replace("\n", " ")
        send(sock, addr, port, "RHD1|$reqId|ERR|$trimmed\n")
    }

    private fun sendChunks(sock: DatagramSocket, addr: InetAddress, port: Int, reqId: Int, points: List<Pair<Double, Double>>) {
        var seq = 0
        var i = 0
        while (i < points.size) {
            val chunk = points.subList(i, minOf(i + CHUNK_POINTS, points.size))
            val sb = StringBuilder()
            sb.append("RPT1|").append(reqId).append('|').append(seq).append('|')
            for ((idx, pt) in chunk.withIndex()) {
                if (idx > 0) sb.append(';')
                sb.append(String.format(Locale.US, "%.6f,%.6f", pt.first, pt.second))
            }
            sb.append('\n')
            send(sock, addr, port, sb.toString())
            seq++
            i += CHUNK_POINTS
        }
    }

    private fun send(sock: DatagramSocket, addr: InetAddress, port: Int, text: String) {
        try {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            sock.send(DatagramPacket(bytes, bytes.size, addr, port))
        } catch (e: Exception) {
            Log.e(TAG, "send failed: ${e.message}")
        }
    }
}
