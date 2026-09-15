package com.barret.navbridge

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.barret.navbridge.databinding.ActivityMainBinding

class MainActivity : LocaleAwareActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            startForwarding()
        } else {
            binding.tvStatus.text = getString(R.string.status_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences(LocaleHelper.PREFS, MODE_PRIVATE)

        // The action bar keeps the plain app name. The version used to be
        // appended here; it is still one tap away on the About screen, which
        // is where someone actually goes looking for it.

        binding.etIp.setText(prefs.getString("esp32_ip", "192.168.4.1"))
        binding.etPort.setText(prefs.getString("esp32_port", "10110"))

        updateButtonLabel(NmeaForwardService.isRunning)

        binding.btnStartStop.setOnClickListener {
            if (NmeaForwardService.isRunning) {
                stopForwarding()
            } else {
                saveSettings()
                ensurePermissionsThenStart()
            }
        }

        // The routing profile lives in Settings now, alongside language and
        // turn guidance -- see the comment in activity_main.xml for why this
        // screen was stripped back to the address and the start button.
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // The proxy switch. Its state is a preference, so it survives the
        // service being stopped and restarted, and the service reads it both
        // on start and whenever this toggles.
        binding.switchProxy.isChecked =
            prefs.getBoolean(HttpProxyServer.KEY_PROXY_ENABLED, false)
        binding.switchProxy.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(HttpProxyServer.KEY_PROXY_ENABLED, checked).apply()
            NmeaForwardService.refreshProxy()
            // Posted rather than called directly: the service starts the
            // listener on another thread, and asking whether it is up in the
            // same breath as telling it to start would usually get "no".
            binding.switchProxy.postDelayed({ updateProxyStatus() }, 300)
        }
        updateProxyStatus()
    }

    override fun onResume() {
        super.onResume()
        updateButtonLabel(NmeaForwardService.isRunning)
        updateProxyStatus()
    }

    /**
     * The line under the switch. Four states, because "on" alone would not
     * distinguish the two that matter: a proxy that is listening from one that
     * could not take the port, and a switch that is on while the service it
     * runs inside is stopped.
     */
    private fun updateProxyStatus() {
        val port = HttpProxyServer.DEFAULT_PORT
        val wanted = binding.switchProxy.isChecked
        binding.tvProxyStatus.text = when {
            !wanted -> getString(R.string.proxy_port, port)
            !NmeaForwardService.isRunning -> getString(R.string.proxy_needs_start, port)
            NmeaForwardService.proxyRunning -> getString(R.string.proxy_port_running, port)
            else -> getString(R.string.proxy_port_failed, port)
        }
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("esp32_ip", binding.etIp.text.toString().trim())
            .putString("esp32_port", binding.etPort.text.toString().trim())
            .apply()
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startForwarding()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun startForwarding() {
        val ip = binding.etIp.text.toString().trim()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 10110
        val intent = Intent(this, NmeaForwardService::class.java).apply {
            action = NmeaForwardService.ACTION_START
            putExtra(NmeaForwardService.EXTRA_IP, ip)
            putExtra(NmeaForwardService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(this, intent)
        updateButtonLabel(true)
        binding.tvStatus.text = getString(R.string.status_running, ip, port)
        binding.btnStartStop.postDelayed({ updateProxyStatus() }, 500)
    }

    private fun stopForwarding() {
        val intent = Intent(this, NmeaForwardService::class.java).apply {
            action = NmeaForwardService.ACTION_STOP
        }
        startService(intent)
        updateButtonLabel(false)
        binding.tvStatus.text = getString(R.string.status_stopped)
        updateProxyStatus()
    }

    private fun updateButtonLabel(running: Boolean) {
        binding.btnStartStop.text = if (running) getString(R.string.stop) else getString(R.string.start)
    }

    companion object {
        /**
         * Request codes for [openAppPendingIntent]. One per notification that
         * offers the tap, because a PendingIntent is identified by its request
         * code plus its intent: two callers sharing a code would be handed the
         * same object, and under FLAG_UPDATE_CURRENT either could quietly
         * rewrite the other's.
         */
        const val RC_OPEN_FROM_STATUS = 1
        const val RC_OPEN_FROM_CUE = 2

        /**
         * A PendingIntent that opens this screen, for the app's notifications
         * to hang off.
         *
         * ACTION_MAIN + CATEGORY_LAUNCHER rather than a bare component intent:
         * this is the same intent the launcher icon fires, so an app already
         * running in the background is brought forward with its state intact
         * (the typed IP, the proxy switch) instead of a second copy of this
         * screen being stacked on the first. Both notifications that use this
         * are posted during a ride, with the app almost always still alive in
         * the background, so that is the common case rather than the corner
         * one.
         */
        fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
