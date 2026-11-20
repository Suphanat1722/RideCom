package com.ridecom.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.ridecom.data.RideComPrefs
import com.ridecom.network.PeerDiscovery
import com.ridecom.service.IntercomService

class MainActivity : ComponentActivity() {

    private lateinit var peerDiscovery: PeerDiscovery
    private lateinit var prefs: RideComPrefs

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions handled gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        prefs = RideComPrefs(this)
        IntercomService.hydratePrefs(prefs)

        peerDiscovery = PeerDiscovery(this)
        peerDiscovery.start(prefs.deviceName)

        val colorScheme = darkColorScheme(
            primary = Color(0xFF00E676),
            onPrimary = Color(0xFF002010),
            secondary = Color(0xFF00D2FF),
            background = Color(0xFF070A0F),
            surface = Color(0xFF11161F),
            onBackground = Color(0xFFF3F6FA),
            onSurface = Color(0xFFF3F6FA),
            error = Color(0xFFFF4757)
        )

        setContent {
            MaterialTheme(colorScheme = colorScheme) {
                RideComApp(
                    prefs = prefs,
                    peerDiscovery = peerDiscovery,
                    isBatteryOptimizationIgnored = ::isIgnoringBatteryOptimizations,
                    onRequestIgnoreBatteryOpt = ::requestIgnoreBatteryOptimizations
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onDestroy() {
        peerDiscovery.stop()
        super.onDestroy()
    }
}
