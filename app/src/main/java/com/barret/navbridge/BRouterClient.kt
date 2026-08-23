package com.barret.navbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import btools.routingapp.IBRouterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Talks to the separately-installed BRouter app (https://brouter.de) over
 * its published AIDL service -- the same integration point OsmAnd/Locus/
 * OruxMaps use -- to compute an offline route between two points. BRouter
 * must be installed and have routing segment data (5x5-degree tiles)
 * downloaded for the area in question; that's done through BRouter's own
 * app UI, nothing to do with this code.
 *
 * One call = one bind/request/unbind cycle. Kept simple over "stay bound
 * permanently" because route requests here are rare (one per tap-GO on the
 * dashboard, not a hot path) -- bind overhead is a non-issue at that rate.
 */
object BRouterClient {

    private const val TAG = "BRouterClient"

    private const val BROUTER_PACKAGE = "btools.routingapp"
    private const val BROUTER_SERVICE_CLASS = "btools.routingapp.BRouterService"

    // Must exceed BRouter's own "maxRunningTime" (below) with real margin --
    // this is the outer guard against a hung/never-responding bind or AIDL
    // call, BRouter's own timeout is what actually stops a runaway route
    // computation.
    private const val MAX_ROUTING_TIME_SECONDS = 25
    private const val OVERALL_TIMEOUT_MS = 28_000L

    data class RouteResult(
        val points: List<Pair<Double, Double>>,   // (lat, lon), in path order
        val hints: List<Hint> = emptyList(),      // turn instructions, may be empty
    )

    class RouteException(message: String) : Exception(message)

    private class BoundService(val binder: IBinder?, val connection: ServiceConnection, val didBind: Boolean)

    /**
     * profile: BRouter's own profile name -- "bicycle", "motorcar", or
     * "foot" (see IBRouterService.aidl's "v" param). Chosen in MainActivity,
     * read fresh per request by RouteRequestServer.
     */
    suspend fun route(
        context: Context,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        profile: String,
    ): RouteResult = try {
        withTimeout(OVERALL_TIMEOUT_MS) {
            routeInner(context, fromLat, fromLon, toLat, toLon, profile)
        }
    } catch (e: TimeoutCancellationException) {
        throw RouteException("timeout waiting for BRouter")
    }

    private suspend fun routeInner(
        context: Context,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        profile: String,
    ): RouteResult {
        val app = context.applicationContext
        val bs = bindBlocking(app)
        try {
            val binder = bs.binder
                ?: throw RouteException("BRouter app not installed or service unreachable")
            val service = IBRouterService.Stub.asInterface(binder)
            val params = Bundle().apply {
                putDoubleArray("lats", doubleArrayOf(fromLat, toLat))
                putDoubleArray("lons", doubleArrayOf(fromLon, toLon))
                putString("v", profile)
                putString("fast", "1")
                putString("trackFormat", "json")
                // Turn instructions. Without this BRouter returns a bare
                // polyline -- the hints are computed anyway, just not
                // emitted. 3 is "osmand style", but the style only affects
                // GPX output: FormatJson writes the command through
                // Formatter.getJsonCommandIndex(), which for every mode is
                // the identity map onto VoiceHint's own constants (C=1,
                // TL=2, TSLL=3, TSHL=4, TR=5, TSLR=6, TSHR=7, KL=8, KR=9,
                // TLU=10, TRU=11, OFFR=12, RNDB=13, RNLB=14, TU=15, BL=16,
                // EL=17, ER=18). So the numbers below are stable whatever
                // mode is asked for.
                //
                // Caveat worth knowing: hints only appear if the PROFILE
                // also defines priorityclassifier. Stock modern trekking/
                // car profiles do; an old downloaded profile will silently
                // return no hints at all, which is why parseHints() logs
                // the count.
                putString("turnInstructionMode", "3")
                putString("maxRunningTime", MAX_ROUTING_TIME_SECONDS.toString())
                // "pathToFileResult" intentionally omitted -- the AIDL doc's
                // recommended default for Android Q+ (this app's whole
                // minSdk 24..targetSdk 34 range included): result comes back
                // directly as the return string instead of a file on disk.
            }
            // getTrackFromParams() is a synchronous, potentially many-
            // seconds-long Binder call ("call in a background thread, heavy
            // task!" per the AIDL doc) -- make sure it's never accidentally
            // invoked from the main thread regardless of caller context.
            val result = withContext(Dispatchers.IO) {
                try {
                    service.getTrackFromParams(params)
                } catch (e: RemoteException) {
                    throw RouteException("BRouter call failed: ${e.message}")
                }
            }
            return parseResult(result)
        } finally {
            if (bs.didBind) {
                try {
                    app.unbindService(bs.connection)
                } catch (e: Exception) {
                    // already unbound (e.g. BRouter's process died mid-call) -- harmless
                }
            }
        }
    }

    private suspend fun bindBlocking(app: Context): BoundService =
        suspendCancellableCoroutine { cont ->
            val intent = Intent().apply {
                component = ComponentName(BROUTER_PACKAGE, BROUTER_SERVICE_CLASS)
            }
            var resumed = false
            lateinit var connection: ServiceConnection
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (cont.isActive && !resumed) {
                        resumed = true
                        // If cont gets cancelled between this resume() call and the
                        // caller actually running, invokeOnCancellation below (already
                        // registered, and didBind is true by this point) unbinds --
                        // no separate per-resume cancellation handler needed, hence
                        // onCancellation = null here (this build's kotlinx-coroutines
                        // resolves cont.resume(value) to the member overload that
                        // requires this argument explicitly, with no default).
                        cont.resume(BoundService(binder, connection, true), onCancellation = null)
                    }
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            val didBind = try {
                app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                // Most likely cause: this app's AndroidManifest is missing the
                // <queries><package android:name="btools.routingapp"/></queries>
                // block required for package visibility on Android 11+.
                false
            }
            if (!didBind) {
                if (cont.isActive && !resumed) {
                    resumed = true
                    cont.resume(BoundService(null, connection, false), onCancellation = null)
                }
            }
            cont.invokeOnCancellation {
                if (didBind) {
                    try { app.unbindService(connection) } catch (e: Exception) {}
                }
            }
        }

    /**
     * BRouter's getTrackFromParams() returns EITHER the track (starts with
     * "{" for trackFormat=json, a GeoJSON FeatureCollection) OR a plain-text
     * error message (e.g. "position not mapped in existing datafile" --
     * meaning: no segment data downloaded for that area; or "start island
     * detected..." -- point unreachable from the road graph) -- see
     * IBRouterService.aidl's doc comment. Told apart by that leading "{".
     */
    private fun parseResult(result: String?): RouteResult {
        if (result == null) throw RouteException("empty result from BRouter")
        val trimmed = result.trim()
        if (!trimmed.startsWith("{")) {
            throw RouteException(trimmed.ifBlank { "unknown BRouter error" })
        }
        val root = JSONObject(trimmed)
        val features = root.getJSONArray("features")
        if (features.length() == 0) throw RouteException("no route in BRouter response")
        val geometry = features.getJSONObject(0).getJSONObject("geometry")
        val coords = geometry.getJSONArray("coordinates")
        val points = ArrayList<Pair<Double, Double>>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            val lon = c.getDouble(0) // GeoJSON order is [lon, lat, (elev)] -- easy to get backwards
            val lat = c.getDouble(1)
            points.add(lat to lon)
        }
        if (points.size < 2) throw RouteException("route has too few points (${points.size})")

        val props = features.getJSONObject(0).optJSONObject("properties")
        val hints = parseHints(props, points.size)
        return RouteResult(points, hints)
    }

    /**
     * One turn instruction: which track point it sits on, what to do there,
     * and (roundabouts only) which exit.
     */
    data class Hint(val pointIndex: Int, val command: Int, val exitNumber: Int)

    /**
     * properties.voicehints is an array of arrays, each
     * [indexInTrack, command, exitNumber, distanceToNext, angle] -- see
     * FormatJson.java. Only the first three are forwarded to the board: it
     * already has the polyline, so it can measure distance along the actual
     * route itself, which stays correct as you move. BRouter's
     * distanceToNext is fixed at route time and would only be right at the
     * moment you passed the previous hint.
     *
     * Parsed defensively: a missing voicehints key is not an error, it is
     * what an old profile without priorityclassifier produces, and a route
     * without turn arrows is still a usable route.
     */
    private fun parseHints(props: JSONObject?, pointCount: Int): List<Hint> {
        val arr = props?.optJSONArray("voicehints")
        if (arr == null) {
            Log.i(TAG, "no voicehints in response -- profile may lack priorityclassifier")
            return emptyList()
        }
        val out = ArrayList<Hint>(arr.length())
        for (i in 0 until arr.length()) {
            val h = arr.optJSONArray(i) ?: continue
            if (h.length() < 2) continue
            val idx = h.optInt(0, -1)
            val cmd = h.optInt(1, 0)
            // An index outside the track would make the board look up a
            // point that doesn't exist; drop rather than clamp, since a hint
            // at the wrong place is worse than one missing.
            if (idx < 0 || idx >= pointCount) continue
            if (cmd <= 0) continue
            out.add(Hint(idx, cmd, h.optInt(2, 0)))
        }
        Log.i(TAG, "voicehints: ${out.size} of ${arr.length()} usable")
        return out
    }
}
