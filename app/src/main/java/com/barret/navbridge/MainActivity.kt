package com.barret.navbridge

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.barret.navbridge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    // BRouter's own profile names (see IBRouterService.aidl's "v" param) --
    // index must line up with R.array.routing_profile_labels, position for
    // position.
    private val routingProfileValues = listOf("bicycle", "motorcar", "foot")

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
        prefs = getSharedPreferences("nmea_bridge", MODE_PRIVATE)

        binding.etIp.setText(prefs.getString("esp32_ip", "192.168.4.1"))
        binding.etPort.setText(prefs.getString("esp32_port", "10110"))
        setupProfileSpinner()

        updateButtonLabel(NmeaForwardService.isRunning)

        binding.btnStartStop.setOnClickListener {
            if (NmeaForwardService.isRunning) {
                stopForwarding()
            } else {
                saveSettings()
                ensurePermissionsThenStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonLabel(NmeaForwardService.isRunning)
    }

    private fun saveSettings() {
        val profileIdx = binding.spinnerProfile.selectedItemPosition
            .coerceIn(0, routingProfileValues.size - 1)
        prefs.edit()
            .putString("esp32_ip", binding.etIp.text.toString().trim())
            .putString("esp32_port", binding.etPort.text.toString().trim())
            .putString("routing_profile", routingProfileValues[profileIdx])
            .apply()
    }

    private fun setupProfileSpinner() {
        val labels = resources.getStringArray(R.array.routing_profile_labels)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProfile.adapter = adapter
        val saved = prefs.getString("routing_profile", "bicycle") ?: "bicycle"
        val idx = routingProfileValues.indexOf(saved).let { if (it < 0) 0 else it }
        binding.spinnerProfile.setSelection(idx)

        // Persisted immediately on change (not just on Start) -- the
        // service reads this pref fresh per route request (see
        // NmeaForwardService.routingProfilePref()), so a profile switch
        // made WHILE already running takes effect on the very next tap-GO
        // without a Stop/Start cycle.
        binding.spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("routing_profile", routingProfileValues[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
