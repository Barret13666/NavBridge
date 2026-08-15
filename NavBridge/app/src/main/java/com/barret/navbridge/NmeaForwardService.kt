package com.barret.navbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
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

        @Volatile
        var isRunning = false
            private set
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

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    private fun routingProfilePref(): String =
        getSharedPreferences("nmea_bridge", MODE_PRIVATE).getString("routing_profile", "bicycle") ?: "bicycle"

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
        startForeground(NOTIFICATION_ID, buildNotification("ожидание первой координаты..."))

        if (routeServer == null) {
            routeServer = RouteRequestServer(applicationContext) { routingProfilePref() }
        }
        routeServer?.start()

        serviceScope.launch {
            try {
                targetAddress = InetAddress.getByName(ip)
                udpSocket = DatagramSocket()
            } catch (e: Exception) {
                updateNotification("ошибка адреса: ${e.message}")
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val sentences = NmeaBuilder.build(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeMeters = if (loc.hasAltitude()) loc.altitude else 0.0,
                    speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                    bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
                )
                sendSentences(sentences)
                val summary = String.format(
                    Locale.US, "lat=%.5f lon=%.5f ±%.0fm", loc.latitude, loc.longitude, loc.accuracy
                )
                updateNotification(summary)
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            isRunning = true
        } catch (e: SecurityException) {
            updateNotification("нет разрешения на геолокацию")
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
                updateNotification("ошибка отправки: ${e.message}")
            }
        }
    }

    private fun stopForwarding() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        udpSocket?.close()
        udpSocket = null
        routeServer?.stop()
        routeServer = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopForwarding()
        serviceScope.cancel()
        super.onDestroy()
    }

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
            .setContentTitle("NavBridge работает")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(R.drawable.ic_notification, "Стоп", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
