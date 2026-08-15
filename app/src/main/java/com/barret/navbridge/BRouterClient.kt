package com.barret.navbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
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

    private const val BROUTER_PACKAGE = "btools.routingapp"
    private const val BROUTER_SERVICE_CLASS = "btools.routingapp.BRouterService"

    // Must exceed BRouter's own "maxRunningTime" (below) with real margin --
    // this is the outer guard against a hung/never-responding bind or AIDL
    // call, BRouter's own timeout is what actually stops a runaway route
    // computation.
    private const val MAX_ROUTING_TIME_SECONDS = 25
    private const val OVERALL_TIMEOUT_MS = 28_000L

    data class RouteResult(val points: List<Pair<Double, Double>>) // (lat, lon), in path order

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
        return RouteResult(points)
    }
}
