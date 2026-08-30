package com.barret.navbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

/**
 * Foreground service: asks Android's FusedLocationProviderClient for
 * position updates (this is the SAME API the phone's own Maps app uses --
 * it automatically blends real GPS with WiFi/cell-tower network location,
 * picking whichever is available/best, no manual provider-switching logic
 * needed like the earlier Termux script had to do), converts each fix to
 * NMEA sentences, and fires them over UDP to the ESP32 nav screen.
 *
 * Runs as a foreground service (persistent notification with a Stop
 * button) so Android doesn't kill it while the screen is off or another
 * app is in front -- required for any app that wants continuous location
 * updates in the background on modern Android.
 */
class NmeaForwardService : Service() {

    companion object {
        const val ACTION_START = "com.barret.navbridge.START"
        const val ACTION_STOP = "com.barret.navbridge.STOP"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "nmea_bridge_channel"
        private const val NOTIFICATION_ID = 1

        private const val TAG = "NmeaForwardService"

        @Volatile
        var isRunning = false
            private set

        // The running instance, so the Settings screen can tell it the
        // language changed. Weak coupling on purpose: a null here just means
        // forwarding is not running, which is a perfectly normal state for
        // Settings to be opened in.
        @Volatile
        private var instance: NmeaForwardService? = null

        /**
         * The TTS engine is still pointed at the previous voice after a
         * language change. Re-resolving it here rather than at the next cue
         * means the change is audible immediately, and a missing voice pack is
         * discovered in the car park instead of at a junction.
         */
        fun onLanguageChanged() {
            instance?.announcer?.applyTtsLanguage()
        }
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var udpSocket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 10110
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // Answers the ESP32's route-to-point requests via BRouter -- see
    // RouteRequestServer's own doc comment for the wire protocol. Runs for
    // as long as GPS forwarding runs (same Start/Stop button covers both),
    // since route requests only make sense while the board is actively
    // getting a live GPS feed from this same phone anyway.
    private var routeServer: RouteRequestServer? = null

    // Speaks and vibrates the turn cues the board raises. Owned by the service
    // rather than by an Activity because cues have to keep working with the
    // screen off and the app in the background -- which is the entire point of
    // them.
    private var announcer: TurnCueAnnouncer? = null

    // Real satellite count, for the dashboard's satellite chip.
    //
    // FusedLocationProviderClient cannot supply this -- it deliberately hides
    // which technology produced a fix, which is the entire point of it -- so
    // the count is read straight from the GNSS receiver through
    // LocationManager, in parallel with the fused updates. The two are
    // independent: the position stays fused (so it still works indoors off
    // Wi-Fi), while the chip reports what the satellites are actually doing.
    //
    // Zero means "no GNSS status yet", which is the truthful answer for a
    // network-derived fix, and is what the chip will show for the first
    // second or two after Start.
    @Volatile
    private var satellitesInFix: Int = 0

    // Last known altitude, for the notification title. Null when the current
    // fix has none. Named differently from NmeaBuilder.build()'s
    // altitudeMeters parameter on purpose -- the two are a line apart at the
    // call site and confusing them would be easy.
    @Volatile
    private var lastAltitudeM: Double? = null
    private var gnssCallback: GnssStatus.Callback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    private fun routingProfilePref(): String =
        getSharedPreferences(LocaleHelper.PREFS, MODE_PRIVATE).getString("routing_profile", "bicycle") ?: "bicycle"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForwarding()
                stopSelf()
            }
            else -> {
                val ip = intent?.getStringExtra(EXTRA_IP) ?: "192.168.4.1"
                val port = intent?.getIntExtra(EXTRA_PORT, 10110) ?: 10110
                startForwarding(ip, port)
            }
        }
        return START_STICKY
    }

    private fun startForwarding(ip: String, port: Int) {
        if (isRunning) {
            stopForwarding()
        }
        targetPort = port

        // Foreground promotion has to happen fast (within a few seconds of
        // startForegroundService()), so do it immediately with a
        // placeholder message, before the address lookup / first fix.
        startForeground(NOTIFICATION_ID, buildNotification(str(R.string.notif_waiting)))

        if (announcer == null) {
            announcer = TurnCueAnnouncer(applicationContext).also { it.start() }
        }
        if (routeServer == null) {
            routeServer = RouteRequestServer(applicationContext, { routingProfilePref() }, announcer)
        }
        routeServer?.start()

        serviceScope.launch {
            try {
                targetAddress = InetAddress.getByName(ip)
                udpSocket = DatagramSocket()
            } catch (e: Exception) {
                updateNotification(str(R.string.notif_address_error, e.message ?: ""))
            }
        }

        // 1Hz, not the 3s it used to be. Three seconds is 85 metres at
        // motorway speed: the map jumped a screen-width at a time, the arrow
        // was always most of a hundred metres behind where you were, and the
        // distance to the next turn stepped down in 85m increments. Every
        // consumer of a fix on the dashboard -- the map centre, the snap, the
        // turn distance, the wrong-way test -- gets three times the
        // resolution from this one line.
        //
        // The cost is battery, and it is smaller than it looks: the GNSS chip
        // is already tracking continuously to answer at all, so the extra
        // work is packet assembly and a UDP send, not another fix acquisition.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        startGnssStatus()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val sentences = NmeaBuilder.build(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeMeters = if (loc.hasAltitude()) loc.altitude else 0.0,
                    speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                    bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
                    satellitesUsed = satellitesInFix,
                )
                sendSentences(sentences)
                val summary = String.format(
                    Locale.US, "lat=%.5f lon=%.5f ±%.0fm", loc.latitude, loc.longitude, loc.accuracy
                )
                // Altitude moved here from the dashboard, where its chip is now
                // the selected gear. It goes on the TITLE line rather than into
                // the body: the body is already a full line of coordinates, and
                // a notification's title is what a watch or a lock screen shows
                // when it has room for one line only.
                //
                // Only when the fix actually carries an altitude -- a network
                // fix often does not, and "ALT 0 m" would be a fabricated
                // number rather than a missing one.
                lastAltitudeM = if (loc.hasAltitude()) loc.altitude else null
                updateNotification(summary)
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            isRunning = true
        } catch (e: SecurityException) {
            updateNotification(str(R.string.notif_no_permission))
        }
    }

    private fun sendSentences(sentences: String) {
        val socket = udpSocket ?: return
        val address = targetAddress ?: return
        val port = targetPort
        serviceScope.launch {
            try {
                val bytes = sentences.toByteArray(Charsets.US_ASCII)
                val packet = DatagramPacket(bytes, bytes.size, address, port)
                socket.send(packet)
            } catch (e: Exception) {
                updateNotification(str(R.string.notif_send_error, e.message ?: ""))
            }
        }
    }

    private fun stopForwarding() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        udpSocket?.close()
        udpSocket = null
        lastAltitudeM = null
        stopGnssStatus()
        routeServer?.stop()
        routeServer = null
        announcer?.stop()
        announcer = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopForwarding()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    /**
     * Subscribes to raw GNSS status purely to count the satellites used in the
     * current fix. Best effort throughout: a device with no GNSS hardware, or
     * a permission that has been revoked since Start, simply leaves the count
     * at zero rather than taking the service down with it.
     */
    private fun startGnssStatus() {
        if (gnssCallback != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                // usedInFix, not the total in view. "In view" counts every
                // satellite the receiver can hear, including ones too weak or
                // too low to contribute, and would read high exactly when the
                // fix is worst -- the opposite of what the chip is for.
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                satellitesInFix = used
            }
        }
        try {
            manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            gnssCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "GNSS status unavailable: ${e.message}")
        }
    }

    private fun stopGnssStatus() {
        val callback = gnssCallback ?: return
        gnssCallback = null
        satellitesInFix = 0
        try {
            val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            manager?.unregisterGnssStatusCallback(callback)
        } catch (e: Exception) {
            // already gone -- nothing to do
        }
    }

    /**
     * Strings in the language chosen in Settings, not the system one.
     *
     * A Service's base Context carries the system configuration, so a plain
     * getString() here would ignore the setting entirely and put the
     * notification in whatever language the phone happens to be in. See
     * LocaleHelper.string().
     */
    private fun str(resId: Int, vararg args: Any): String =
        LocaleHelper.string(this, resId, *args)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "NavBridge", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, NmeaForwardService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                lastAltitudeM?.let {
                    String.format(Locale.US, "%s alt=%.0fm", str(R.string.notif_title), it)
                } ?: str(R.string.notif_title)
            )
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(R.drawable.ic_notification, str(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
