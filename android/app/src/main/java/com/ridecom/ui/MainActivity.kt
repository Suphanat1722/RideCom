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
import com.ridecom.network.PeerDiscovery

class MainActivity : ComponentActivity() {

    private lateinit var peerDiscovery: PeerDiscovery

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions handled gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        peerDiscovery = PeerDiscovery(this)
        peerDiscovery.start(PeerDiscovery.defaultDeviceName())

        val colorScheme = darkColorScheme(
            primary = Color(0xFF00E676),
            onPrimary = Color(0xFF002211),
            secondary = Color(0xFF29B6F6),
            background = Color(0xFF000000),
            surface = Color(0xFF121418),
            onBackground = Color(0xFFF5F7FA),
            onSurface = Color(0xFFF5F7FA),
            error = Color(0xFFFF5252)
        )

        setContent {
            MaterialTheme(colorScheme = colorScheme) {
                RideComApp(
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
