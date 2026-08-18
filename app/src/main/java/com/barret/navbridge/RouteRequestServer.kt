package com.barret.navbridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *   in:  "RRQ1|<reqId>|<fromLat>|<fromLon>|<toLat>|<toLon>[|<profile>]\n"
 *                  <profile> (added for the on-screen transport-type button,
 *                  gps_nav.cpp's navBtnRouteProfile) is OPTIONAL -- older
 *                  firmware still sending the original 6-field RRQ1 just
 *                  gets routingProfile() (the MainActivity spinner) as
 *                  before; a 7-field RRQ1 with <profile> overrides it for
 *                  that one request. One of BRouterClient's own profile
 *                  names -- "bicycle" | "motorcar" | "foot".
 *   out (success): "RHD1|<reqId>|OK|<totalPoints>\n"
 *                  then ceil(totalPoints/CHUNK_POINTS) packets:
 *                  "RPT1|<reqId>|<seq>|<lat1>,<lon1>;<lat2>,<lon2>;...\n"
 *   out (failure): "RHD1|<reqId>|ERR|<short reason>\n" (final, no data packets)
 *   in (retry):    "RRS1|<reqId>|<seq>[,<seq>...]\n" -- the board asking for
 *                  specific RPT1 packets it never received (plain UDP, no
 *                  delivery guarantee -- a burst of up to ~10 packets back to
 *                  back is enough to occasionally lose one). Answered from
 *                  lastRoute below, WITHOUT calling BRouter again.
 *
 * Address search shares this same socket (see gps_nav.cpp's magnifier
 * button and NAV_SEARCH_* block), answered by PhotonClient rather than
 * BRouter:
 *   in:  "SRQ1|<reqId>|<lat>|<lon>|<query text>\n"
 *                  <lat>/<lon> is the board's current position, passed
 *                  straight through as Photon's location bias so a nearby
 *                  match outranks an identically-named one abroad. The
 *                  query is the LAST field and is NOT escaped, so it may
 *                  itself contain anything except '|' and '\n' -- parsed
 *                  with limit=5 on the split for exactly that reason.
 *   out (success): "SHD1|<reqId>|OK|<count>\n"
 *                  then <count> packets, one result each:
 *                  "SRT1|<reqId>|<idx>|<lat>|<lon>|<name>\n"
 *   out (failure): "SHD1|<reqId>|ERR|<short reason>\n" (final, no results)
 *
 * One result per packet, unlike the route's batched RPT1: names are
 * variable-length and losing a packet then costs one list row instead of
 * the whole reply, which is why there is no SRS1 resend counterpart.
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

        // Must match gps_nav.cpp's NAV_PROFILE_NAMES exactly, and BRouterClient.kt's
        // own doc comment on the "v" param.
        private val VALID_ROUTING_PROFILES = setOf("bicycle", "motorcar", "foot")

        // Small gap between back-to-back RPT1 sends -- a route can be up to
        // 10 packets, and sending all of them with zero pacing turned out to
        // be enough to occasionally overrun the board's UDP receive queue on
        // a busy WiFi link (that's what the RRS1 resend path below recovers
        // from, but avoiding the loss in the first place means fewer routes
        // need recovering at all). 15ms x 10 packets is at most ~150ms added
        // to a route reply that's already dominated by BRouter's own
        // multi-second compute time -- not perceptible.
        private const val CHUNK_SEND_DELAY_MS = 15L
    }

    // The most recently computed route, kept so a "RRS1" resend request can
    // be answered by re-sending the SAME already-computed RPT1 packets
    // instead of invoking BRouter a second time (which can itself take
    // several more seconds -- the resend path only exists to fix lost
    // packets, not to recompute). Single most-recent-only is enough: the
    // board only ever has one route request in flight at a time (see
    // navRouteFetchBusy in gps_nav.cpp).
    @Volatile
    private var lastRoute: CachedRoute? = null

    private data class CachedRoute(val reqId: Int, val points: List<Pair<Double, Double>>)

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
            // UTF-8 to match send() above -- an SRQ1 query typed on the
            // board's Cyrillic keyboard arrives as multi-byte UTF-8.
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val fromAddress = packet.address
            val fromPort = packet.port
            // handleRequest calls BRouter, which can take several seconds --
            // run it on its own coroutine so a slow route computation never
            // delays receiving (or replying to) the NEXT request.
            scope.launch { handleRequest(sock, fromAddress, fromPort, text) }
        }
    }

    private suspend fun handleRequest(sock: DatagramSocket, replyAddr: InetAddress, replyPort: Int, text: String) {
        val trimmed = text.trim()
        when {
            trimmed.startsWith("RRQ1|") -> handleRouteRequest(sock, replyAddr, replyPort, trimmed)
            trimmed.startsWith("RRS1|") -> handleResendRequest(sock, replyAddr, replyPort, trimmed)
            trimmed.startsWith("SRQ1|") -> handleSearchRequest(sock, replyAddr, replyPort, trimmed)
            else -> Log.w(TAG, "ignoring unrecognized packet: ${trimmed.take(60)}")
        }
    }

    private suspend fun handleRouteRequest(sock: DatagramSocket, replyAddr: InetAddress, replyPort: Int, text: String) {
        val parts = text.split("|")
        // 6 fields = the original RRQ1 (no <profile>, older firmware) -- 7 =
        // the current one with it appended. Anything else is malformed.
        if (parts.size != 6 && parts.size != 7) {
            Log.w(TAG, "malformed RRQ1: ${text.take(60)}")
            return
        }
        val reqId = parts[1].toIntOrNull()
        val fromLat = parts[2].toDoubleOrNull()
        val fromLon = parts[3].toDoubleOrNull()
        val toLat = parts[4].toDoubleOrNull()
        val toLon = parts[5].toDoubleOrNull()
        if (reqId == null || fromLat == null || fromLon == null || toLat == null || toLon == null) {
            Log.w(TAG, "malformed RRQ1: ${text.take(60)}")
            return
        }
        // Only trust a value that's actually one of BRouter's own profile
        // names (see BRouterClient.kt's doc comment on "v") -- anything else
        // (a stray/garbled packet, or a future firmware sending a name this
        // APK doesn't know yet) falls back to the spinner instead of handing
        // BRouter an unrecognized profile string.
        val requestedProfile = parts.getOrNull(6)
            ?.takeIf { it in VALID_ROUTING_PROFILES }
        if (parts.size == 7 && requestedProfile == null) {
            Log.w(TAG, "RRQ1 profile field ignored (not one of $VALID_ROUTING_PROFILES): ${parts[6]}")
        }
        val profile = requestedProfile ?: routingProfile()

        try {
            val result = BRouterClient.route(context, fromLat, fromLon, toLat, toLon, profile)
            lastRoute = CachedRoute(reqId, result.points) // cache BEFORE sending -- an RRS1 for this
                                                            // reqId could arrive while sendChunks is
                                                            // still working through the list
            sendHeaderOk(sock, replyAddr, replyPort, reqId, result.points.size)
            sendChunks(sock, replyAddr, replyPort, reqId, result.points)
        } catch (e: BRouterClient.RouteException) {
            sendHeaderErr(sock, replyAddr, replyPort, reqId, e.message ?: "unknown error")
        } catch (e: Exception) {
            sendHeaderErr(sock, replyAddr, replyPort, reqId, "internal error: ${e.message}")
        }
    }

    // "RRS1|<reqId>|<seq>[,<seq>...]" -- the board re-asking for specific
    // RPT1 packets it never received. Answered from lastRoute, no BRouter
    // call. Silently ignored if lastRoute is gone or is for a different,
    // older reqId (e.g. app process restarted, or GO was tapped again for a
    // new destination before the old resend timed out on the board) -- the
    // board's own overall NAV_ROUTE_FETCH_TIMEOUT_MS still bounds how long
    // it waits either way.
    private fun handleResendRequest(sock: DatagramSocket, replyAddr: InetAddress, replyPort: Int, text: String) {
        val parts = text.split("|")
        if (parts.size != 3) {
            Log.w(TAG, "malformed RRS1: ${text.take(60)}")
            return
        }
        val reqId = parts[1].toIntOrNull()
        val route = lastRoute
        if (reqId == null || route == null || route.reqId != reqId) {
            Log.w(TAG, "RRS1 for unknown/stale reqId=$reqId (have ${route?.reqId})")
            return
        }
        val seqs = parts[2].split(",").mapNotNull { it.toIntOrNull() }
        if (seqs.isEmpty()) {
            Log.w(TAG, "malformed RRS1 seq list: ${text.take(60)}")
            return
        }
        for (seq in seqs) {
            val start = seq * CHUNK_POINTS
            if (start >= route.points.size) continue // stale/out-of-range seq -- ignore, not fatal
            val end = minOf(start + CHUNK_POINTS, route.points.size)
            send(sock, replyAddr, replyPort, formatChunk(reqId, seq, route.points.subList(start, end)))
        }
    }

    // "SRQ1|<reqId>|<lat>|<lon>|<query>" -- address search, answered via
    // PhotonClient. Deliberately NOT cached the way lastRoute is: a search
    // reply is cheap to recompute, the board reissues the whole query on
    // every keystroke burst anyway, and a stale cached result list would be
    // actively wrong the moment the user types one more letter.
    private suspend fun handleSearchRequest(sock: DatagramSocket, replyAddr: InetAddress, replyPort: Int, text: String) {
        // limit=5: the query is the last field and may legitimately contain
        // spaces, commas, dots -- everything except '|' (stripped on the way
        // back out by PhotonClient.buildLabel). Splitting without the limit
        // would truncate any address containing a pipe-free but otherwise
        // arbitrary string at the first extra separator.
        val parts = text.split("|", limit = 5)
        if (parts.size != 5) {
            Log.w(TAG, "malformed SRQ1: ${text.take(60)}")
            return
        }
        val reqId = parts[1].toIntOrNull()
        val lat = parts[2].toDoubleOrNull()
        val lon = parts[3].toDoubleOrNull()
        val query = parts[4].trim()
        if (reqId == null) {
            Log.w(TAG, "malformed SRQ1 reqId: ${text.take(60)}")
            return
        }
        if (query.isEmpty()) {
            sendSearchHeaderOk(sock, replyAddr, replyPort, reqId, 0)
            return
        }
        try {
            // NaN lat/lon (board has no fix yet) is passed straight through
            // -- PhotonClient treats it as "no location bias" rather than
            // failing, so search still works before the first fix.
            val places = PhotonClient.search(
                query,
                lat ?: Double.NaN,
                lon ?: Double.NaN,
            )
            sendSearchHeaderOk(sock, replyAddr, replyPort, reqId, places.size)
            for ((idx, place) in places.withIndex()) {
                send(sock, replyAddr, replyPort, formatSearchResult(reqId, idx, place))
                // Same pacing reasoning as CHUNK_SEND_DELAY_MS above: a
                // back-to-back burst is what overruns the board's UDP queue.
                if (idx < places.size - 1) delay(CHUNK_SEND_DELAY_MS)
            }
        } catch (e: PhotonClient.SearchException) {
            sendSearchHeaderErr(sock, replyAddr, replyPort, reqId, e.message ?: "search failed")
        } catch (e: Exception) {
            sendSearchHeaderErr(sock, replyAddr, replyPort, reqId, "internal error: ${e.message}")
        }
    }

    private fun sendSearchHeaderOk(sock: DatagramSocket, addr: InetAddress, port: Int, reqId: Int, count: Int) {
        send(sock, addr, port, "SHD1|$reqId|OK|$count\n")
    }

    private fun sendSearchHeaderErr(sock: DatagramSocket, addr: InetAddress, port: Int, reqId: Int, reason: String) {
        val trimmed = reason.take(60).replace("\n", " ")
        send(sock, addr, port, "SHD1|$reqId|ERR|$trimmed\n")
    }

    private fun formatSearchResult(reqId: Int, idx: Int, place: PhotonClient.Place): String =
        // Locale.US on the coordinates specifically: a phone set to a
        // comma-decimal locale would otherwise emit "51,107" and the board's
        // strtod would stop at the comma, silently landing the pin at 51.0.
        String.format(Locale.US, "SRT1|%d|%d|%.6f|%.6f|%s\n", reqId, idx, place.lat, place.lon, place.name)

    private fun sendHeaderOk(sock: DatagramSocket, addr: InetAddress, port: Int, reqId: Int, totalPoints: Int) {
        send(sock, addr, port, "RHD1|$reqId|OK|$totalPoints\n")
    }

    private fun sendHeaderErr(sock: DatagramSocket, addr: InetAddress, port: Int, reqId: Int, reason: String) {
        // Keep it short -- the ESP32 side only keeps ~64 bytes of this, and
        // it ends up in a small on-screen alert.
        val trimmed = reason.take(60).replace("\n", " ")
        send(sock, addr, port, "RHD1|$reqId|ERR|$trimmed\n")
    }

    private suspend fun sendChunks(sock: DatagramSocket, addr: InetAddress, port: Int, reqId: Int, points: List<Pair<Double, Double>>) {
        var seq = 0
        var i = 0
        while (i < points.size) {
            val end = minOf(i + CHUNK_POINTS, points.size)
            send(sock, addr, port, formatChunk(reqId, seq, points.subList(i, end)))
            seq++
            i += CHUNK_POINTS
            if (i < points.size) delay(CHUNK_SEND_DELAY_MS) // see CHUNK_SEND_DELAY_MS's comment
        }
    }

    private fun formatChunk(reqId: Int, seq: Int, chunk: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        sb.append("RPT1|").append(reqId).append('|').append(seq).append('|')
        for ((idx, pt) in chunk.withIndex()) {
            if (idx > 0) sb.append(';')
            sb.append(String.format(Locale.US, "%.6f,%.6f", pt.first, pt.second))
        }
        sb.append('\n')
        return sb.toString()
    }

    private fun send(sock: DatagramSocket, addr: InetAddress, port: Int, text: String) {
        try {
            // UTF-8, not US_ASCII as this originally was: SRT1 result names
            // carry Cyrillic and Polish characters, and US_ASCII silently
            // replaces every one of them with '?'. Route/RHD1/RPT1 traffic is
            // pure ASCII digits either way, so widening this is free for
            // them. The board decodes UTF-8 directly (its search font is
            // built with those ranges -- see ui_font_nav14.c).
            val bytes = text.toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(bytes, bytes.size, addr, port))
        } catch (e: Exception) {
            Log.e(TAG, "send failed: ${e.message}")
        }
    }
}
