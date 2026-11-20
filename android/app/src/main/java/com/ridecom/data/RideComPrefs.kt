package com.ridecom.data

import android.content.Context
import com.ridecom.network.PeerDiscovery
import com.ridecom.protocol.JitterPreset

class RideComPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ridecom_prefs", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString("device_name", PeerDiscovery.defaultDeviceName()) ?: PeerDiscovery.defaultDeviceName()
        set(value) = prefs.edit().putString("device_name", value).apply()

    var lastPeerIp: String
        get() = prefs.getString("last_peer_ip", "") ?: ""
        set(value) = prefs.edit().putString("last_peer_ip", value).apply()

    var lastPeerName: String
        get() = prefs.getString("last_peer_name", "") ?: ""
        set(value) = prefs.edit().putString("last_peer_name", value).apply()

    var voxThresholdDb: Float
        get() = prefs.getFloat("vox_threshold_db", -58f)
        set(value) = prefs.edit().putFloat("vox_threshold_db", value).apply()

    var jitterPreset: JitterPreset
        get() {
            val name = prefs.getString("jitter_preset", JitterPreset.BALANCED.name)
            return JitterPreset.entries.firstOrNull { it.name == name } ?: JitterPreset.BALANCED
        }
        set(value) = prefs.edit().putString("jitter_preset", value.name).apply()

    var bitrateKbps: Int
        get() = prefs.getInt("bitrate_kbps", 24)
        set(value) = prefs.edit().putInt("bitrate_kbps", value).apply()

    var audioCuesEnabled: Boolean
        get() = prefs.getBoolean("audio_cues_enabled", true)
        set(value) = prefs.edit().putBoolean("audio_cues_enabled", value).apply()

    var musicVolume: Float
        get() = prefs.getFloat("music_volume", 0.35f)
        set(value) = prefs.edit().putFloat("music_volume", value).apply()
}
