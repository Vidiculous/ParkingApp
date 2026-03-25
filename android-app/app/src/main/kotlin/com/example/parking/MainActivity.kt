package com.example.parking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.parking.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Permissions needed for BLE + geofence
    private val basePermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private val baseLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Location & Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        } else {
            requestBackgroundLocation()
        }
    }

    private val backgroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            GeofenceManager.register(this)
        } else {
            Toast.makeText(this, "Background location required for auto-detection", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_status   -> StatusFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_status
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val missing = basePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            baseLauncher.launch(missing.toTypedArray())
        } else {
            requestBackgroundLocation()
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bg = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            if (ContextCompat.checkSelfPermission(this, bg) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "Please select \"Allow all the time\" for location to enable auto-detection",
                    Toast.LENGTH_LONG
                ).show()
                backgroundLauncher.launch(bg)
                return
            }
        }
        GeofenceManager.register(this)
    }
}
