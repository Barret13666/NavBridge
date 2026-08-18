package com.barret.navbridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Address search (forward geocoding) via Photon -- https://photon.komoot.io.
 *
 * WHY PHOTON and not the obvious alternatives:
 *
 *  - OsmAnd's AIDL search would be the direct analogue of BRouterClient
 *    (offline, same bind/call/unbind shape), but it means installing OsmAnd
 *    itself -- hundreds of MB of maps per region against BRouter's few MB
 *    per 5x5 tile -- and its AIDL search is asynchronous through a separate
 *    IOsmAndAidlCallback, with a package name that differs between the free
 *    build, OsmAnd+ and nightlies. A lot of moving parts for one text box.
 *  - Komoot has no public API at all; it integrates only with selected
 *    hardware partners. Photon is Komoot's OPEN-SOURCE geocoder, which is a
 *    different thing entirely and is free to use.
 *  - Nominatim's usage policy explicitly forbids type-ahead/autocomplete.
 *  - android.location.Geocoder is the least code, but it is online anyway,
 *    its quality varies by device/vendor, and it has no location-bias or
 *    search-as-you-type ranking.
 *
 * Photon is purpose-built for exactly this: search-as-you-type, location
 * bias, typo tolerance, multilingual search, no API key, no registration.
 * Being an OSM-derived index it also matches the map tiles the dashboard is
 * already drawing, so a result always lands somewhere the board can show.
 *
 * The one real cost is that it is ONLINE -- unlike BRouter, which routes
 * fully offline. In this setup that is acceptable: the phone forwards NMEA
 * to the board over WiFi and the board itself pulls OSM tiles over HTTPS,
 * so the pair already depends on connectivity. Routing stays offline, so a
 * route computed before losing signal keeps working.
 *
 * Terms of the public demo server, quoting the project: use is welcome as
 * long as request numbers stay reasonable, extensive usage gets throttled
 * or banned, and no availability guarantee is given. This client is built
 * to stay well inside that: the BOARD debounces keystrokes (see
 * NAV_SEARCH_DEBOUNCE_MS in gps_nav.cpp) so one typed address costs a
 * handful of requests, MIN_INTERVAL_MS below enforces a floor between
 * calls no matter what the board sends, and a real trip generates maybe a
 * few dozen requests. If that ever stops being true, PHOTON_BASE can point
 * at a self-hosted instance instead -- Photon is Apache-2.0 and the code
 * here does not change.
 */
object PhotonClient {

    private const val TAG = "PhotonClient"

    // Swap this for your own instance (e.g. "http://192.168.1.50:2322") if
    // you ever outgrow the public server; nothing else here needs changing.
    private const val PHOTON_BASE = "https://photon.komoot.io/api"

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000
    private const val OVERALL_TIMEOUT_MS = 9_000L

    // Hard floor between outgoing requests, on top of the board's own
    // debounce -- a stuck/garbled board, or a future firmware that forgets
    // to debounce, must not be able to hammer a free public service through
    // this app. Requests arriving inside the window are served late rather
    // than dropped: at 1/s the user perceives no difference.
    private const val MIN_INTERVAL_MS = 1000L

    // Photon caps results server-side too; asking for more than the board
    // can display just wastes packets. Must be >= the board's
    // NAV_SEARCH_MAX_RESULTS for the list to ever fill up.
    const val MAX_RESULTS = 8

    // Keeps one result inside a single UDP packet with room to spare, and
    // matches NAV_SEARCH_NAME_MAX on the board (which truncates anyway).
    private const val MAX_NAME_CHARS = 63

    data class Place(val lat: Double, val lon: Double, val name: String)

    class SearchException(message: String) : Exception(message)

    @Volatile
    private var lastRequestAt = 0L

    /**
     * @param lat/lon current position, used purely as a location bias so
     *        "Rynek" ranks the nearby one first rather than an identically
     *        named square four countries away. Pass NaN to skip biasing.
     * @param lang  affects which localized name Photon returns where one
     *        exists. Photon supports a limited set here; anything else is
     *        rejected by the server, so unknown values are dropped rather
     *        than passed through. "default" means the OSM local name, which
     *        is what you want on a map showing local labels.
     */
    suspend fun search(
        query: String,
        lat: Double,
        lon: Double,
        lang: String = "default",
        limit: Int = MAX_RESULTS,
    ): List<Place> = try {
        withTimeout(OVERALL_TIMEOUT_MS) {
            searchInner(query, lat, lon, lang, limit)
        }
    } catch (e: TimeoutCancellationException) {
        throw SearchException("timeout")
    }

    private suspend fun searchInner(
        query: String,
        lat: Double,
        lon: Double,
        lang: String,
        limit: Int,
    ): List<Place> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        throttle()

        val sb = StringBuilder(PHOTON_BASE)
        sb.append("?q=").append(URLEncoder.encode(trimmed, "UTF-8"))
        sb.append("&limit=").append(limit.coerceIn(1, MAX_RESULTS))
        if (lang in SUPPORTED_LANGS) sb.append("&lang=").append(lang)
        if (!lat.isNaN() && !lon.isNaN()) {
            // Photon's own param names -- note it is lon/lat, and that these
            // only bias ranking, they do not filter by radius.
            sb.append(String.format(Locale.US, "&lat=%.6f&lon=%.6f", lat, lon))
        }

        val url = URL(sb.toString())
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // Identifying the client is basic courtesy toward a free service
            // and makes us blockable in isolation rather than as anonymous
            // traffic if this ever misbehaves.
            setRequestProperty("User-Agent", "NavBridge/1.0 (ESP32 dashboard companion)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 429) throw SearchException("rate limited, slow down")
            if (code !in 200..299) throw SearchException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            parse(body)
        } catch (e: SearchException) {
            throw e
        } catch (e: Exception) {
            throw SearchException("network: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun throttle() {
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private val SUPPORTED_LANGS = setOf("default", "en", "de", "fr", "it")

    /**
     * Photon answers with GeoJSON: a FeatureCollection whose features carry
     * a Point geometry and a properties bag. Which of those properties are
     * present depends entirely on what the OSM object is -- a house has
     * street+housenumber, a POI has name, a city has only name+state -- so
     * the label is assembled from whatever showed up rather than assuming
     * any particular field exists.
     *
     * The second pass exists because a query like "ВДНХ" legitimately
     * returns four DIFFERENT OSM objects -- the metro station, the park, the
     * exhibition centre, the surrounding district -- that all carry the same
     * name and the same city, and so all rendered as the identical string
     * "ВДНХ, Москва". Four indistinguishable rows is not a useful result
     * list, so near-identical entries are dropped and the rest are given
     * something that tells them apart.
     */
    private fun parse(body: String): List<Place> {
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return emptyList()

        val out = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            // GeoJSON order is [lon, lat] -- easy to get backwards.
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val props = f.optJSONObject("properties") ?: JSONObject()

            var label = buildLabel(props)

            // Drop results the board physically cannot draw -- see
            // isRenderable(). Done before dedupe so a dropped foreign-script
            // entry does not occupy one of the four slots or influence the
            // disambiguation counters.
            if (!isRenderable(label)) continue

            // Same label AND essentially the same spot: one real place
            // indexed twice (a node and its enclosing way, typically).
            // Nothing to choose between them, so keep the first.
            if (out.any { it.name == label && metersBetween(it.lat, it.lon, lat, lon) < 150 }) continue

            // Same label, genuinely different location: add whatever
            // distinguishes them. district first (it is localized, same as
            // the name), then street, then the raw OSM tag value -- English,
            // but "station" vs "attraction" still answers "which ВДНХ?".
            if (out.any { it.name == label }) {
                val extra = sequenceOf("district", "street", "osm_value")
                    .mapNotNull { key -> props.optString(key, "").takeIf { it.isNotBlank() } }
                    .firstOrNull()
                if (extra != null) label = "$label - $extra"
            }

            // Still identical (no distinguishing field at all): number them,
            // so at least the rows are visibly separate entries rather than
            // looking like a rendering bug.
            if (out.any { it.name == label }) {
                label = "$label (" + (out.count { it.name.startsWith(label) } + 1) + ")"
            }

            out.add(Place(lat, lon, sanitize(label)))
        }
        return out
    }

    /**
     * True if every character of the label has a glyph in the board's search
     * font (ui_font_nav14.c). Photon returns names in the LOCAL language of
     * wherever the result is, so a query of "146K2" happily comes back with
     * a road in South Korea alongside the Moscow matches -- and on the board
     * that row rendered as a line of empty boxes, occupying one of only four
     * result slots.
     *
     * MUST be kept in sync with the lv_font_conv range list used to build
     * ui_font_nav14.c. That font covers European Latin and Cyrillic, which
     * is the realistic reach of a device that routes with BRouter over
     * locally downloaded tiles; anything in Hangul, CJK, Arabic, Greek,
     * Hebrew or Thai is dropped here rather than shipped over UDP to be
     * drawn as boxes.
     */
    private fun isRenderable(label: String): Boolean = label.all { ch ->
        val c = ch.code
        when {
            c == 0x0A || c == 0x0D -> true               // stripped later by sanitize()
            c in 0x20..0x7F -> true                       // ASCII
            c in 0xA0..0xFF && c != 0xAD -> true          // Latin-1 supplement (accents, guillemets)
            c in 0x100..0x17F -> true                     // Latin Extended-A (Polish, Czech, Baltic...)
            c in 0x400..0x45F -> true                     // Cyrillic
            c == 0x490 || c == 0x491 -> true              // Ukrainian Ґ/ґ
            c in 0x2013..0x2014 -> true                   // en/em dash
            c in 0x2018..0x2019 -> true                   // curly single quotes
            c in 0x201C..0x201E -> true                   // curly double quotes
            c == 0x2026 -> true                           // ellipsis
            c == 0x2116 -> true                           // numero sign, very common in RU addresses
            else -> false
        }
    }

    /**
     * Equirectangular approximation -- at the ~150m scale this is used for,
     * the error against a proper haversine is far below the threshold being
     * tested, and it costs two trig calls instead of six.
     */
    private fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latRad = Math.toRadians((lat1 + lat2) / 2.0)
        val dx = Math.toRadians(lon2 - lon1) * Math.cos(latRad)
        val dy = Math.toRadians(lat2 - lat1)
        return Math.sqrt(dx * dx + dy * dy) * 6371000.0
    }

    private fun buildLabel(p: JSONObject): String {
        fun str(key: String): String? = p.optString(key, "").takeIf { it.isNotBlank() }

        // Primary: the thing itself. A named POI wins; otherwise street plus
        // house number; otherwise fall back to the administrative name so a
        // bare city/district still shows something.
        val head = str("name")
            ?: listOfNotNull(str("street"), str("housenumber")).joinToString(" ").takeIf { it.isNotBlank() }
            ?: str("city")
            ?: str("state")
            ?: str("country")
            ?: "?"

        // Secondary: enough context to tell two same-named results apart,
        // which on a 480px screen is worth more than a full postal address.
        val tail = listOfNotNull(
            str("city")?.takeIf { it != head },
            str("state")?.takeIf { it != head },
        ).firstOrNull()

        return if (tail != null) "$head, $tail" else head
    }

    /**
     * The wire format is pipe-delimited and newline-terminated, so either of
     * those inside a name would corrupt the packet on the board. Stripped
     * rather than escaped: the board has no unescaper, and OSM names
     * essentially never contain them. Applied last, after any disambiguation
     * suffix has been appended, so the length cap covers the final string.
     */
    private fun sanitize(label: String): String =
        label.replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(MAX_NAME_CHARS)
}
