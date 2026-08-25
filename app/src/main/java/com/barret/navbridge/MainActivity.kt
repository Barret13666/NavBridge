package com.barret.navbridge

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.barret.navbridge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

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
    }

    override fun onResume() {
        super.onResume()
        updateButtonLabel(NmeaForwardService.isRunning)
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
    }

    private fun stopForwarding() {
        val intent = Intent(this, NmeaForwardService::class.java).apply {
            action = NmeaForwardService.ACTION_STOP
        }
        startService(intent)
        updateButtonLabel(false)
        binding.tvStatus.text = getString(R.string.status_stopped)
    }

    private fun updateButtonLabel(running: Boolean) {
        binding.btnStartStop.text = if (running) getString(R.string.stop) else getString(R.string.start)
    }
}
