package com.barret.navbridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds a $GPRMC + $GPGGA NMEA 0183 sentence pair from a single location
 * fix, regardless of whether that fix came from real GPS hardware or from
 * FusedLocationProviderClient's network/WiFi-based estimate -- NMEA has no
 * concept of "which provider", so both look identical on the wire. This is
 * the exact same sentence-building logic as the earlier Termux Python
 * script (phone_nmea_forwarder.py), just ported to Kotlin so it can run as
 * a proper Android foreground service instead of a terminal script.
 */
object NmeaBuilder {

    private val utc = TimeZone.getTimeZone("UTC")

    fun build(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        speedMps: Double,
        bearingDeg: Double,
    ): String {
        val now = Date()
        val timeStr = timeFormat().format(now)
        val dateStr = dateFormat().format(now)

        val (latStr, latHemi) = toNmeaLat(latitude)
        val (lonStr, lonHemi) = toNmeaLon(longitude)
        val speedKnots = speedMps * 1.94384 // m/s -> knots

        val rmcBody = String.format(
            Locale.US, "GPRMC,%s,A,%s,%s,%s,%s,%.2f,%.2f,%s,,,A",
            timeStr, latStr, latHemi, lonStr, lonHemi, speedKnots, bearingDeg, dateStr
        )
        val rmc = "$" + rmcBody + "*" + checksum(rmcBody) + "\r\n"

        // Fix quality 1 = "GPS fix". NMEA has no standard "network fix"
        // quality code, so this stays 1 regardless of provider --
        // TinyGPSPlus on the ESP32 side only checks that it's non-zero.
        val ggaBody = String.format(
            Locale.US, "GPGGA,%s,%s,%s,%s,%s,1,06,1.0,%.1f,M,0.0,M,,",
            timeStr, latStr, latHemi, lonStr, lonHemi, altitudeMeters
        )
        val gga = "$" + ggaBody + "*" + checksum(ggaBody) + "\r\n"

        return rmc + gga
    }

    private fun timeFormat(): SimpleDateFormat =
        SimpleDateFormat("HHmmss.SS", Locale.US).apply { timeZone = utc }

    private fun dateFormat(): SimpleDateFormat =
        SimpleDateFormat("ddMMyy", Locale.US).apply { timeZone = utc }

    private fun checksum(body: String): String {
        var cs = 0
        for (ch in body) {
            cs = cs xor ch.code
        }
        return String.format(Locale.US, "%02X", cs)
    }

    private fun toNmeaLat(lat: Double): Pair<String, String> {
        val hemi = if (lat >= 0) "N" else "S"
        val a = Math.abs(lat)
        val deg = a.toInt()
        val minutes = (a - deg) * 60
        return String.format(Locale.US, "%02d%07.4f", deg, minutes) to hemi
    }

    private fun toNmeaLon(lon: Double): Pair<String, String> {
        val hemi = if (lon >= 0) "E" else "W"
        val a = Math.abs(lon)
        val deg = a.toInt()
        val minutes = (a - deg) * 60
        return String.format(Locale.US, "%03d%07.4f", deg, minutes) to hemi
    }
}
