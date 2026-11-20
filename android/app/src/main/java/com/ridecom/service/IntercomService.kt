package com.ridecom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ridecom.audio.AudioEngine
import com.ridecom.audio.AudioRoute
import com.ridecom.audio.VoxGate
import com.ridecom.codec.OpusCodec
import com.ridecom.network.PeerDiscovery
import com.ridecom.network.UdpIntercomClient
import com.ridecom.protocol.JitterBuffer
import com.ridecom.protocol.JitterPreset
import com.ridecom.protocol.PopResult
import com.ridecom.protocol.RideComProtocol
import com.ridecom.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class IntercomState(
    val active: Boolean = false,
    val peerConnected: Boolean = false,
    val peerName: String = "",
    val peerIp: String = "",
    val muted: Boolean = false,
    val pttMode: Boolean = false,
    val pttPressed: Boolean = false,
    val voxOpen: Boolean = false,
    val inputLevelDb: Float = -90f,
    val voxThresholdDb: Float = -42f,
    val rttMs: Int = 0,
    val packetLossPct: Float = 0f,
    val bufferMs: Int = 40,
    val jitterPreset: JitterPreset = JitterPreset.BALANCED,
    val bitrateKbps: Int = 24,
    val audioRoute: AudioRoute = AudioRoute.SPEAKER,
    val preferBluetooth: Boolean = true
)

class IntercomService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var audioEngine: AudioEngine? = null
    private var udpClient: UdpIntercomClient? = null
    private var opusCodec: OpusCodec? = null
    private val jitterBuffer = JitterBuffer(JitterPreset.BALANCED)
    private val voxGate = VoxGate()

    private var consecutivePlc = 0
    private var uiTickCounter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val targetIp = intent.getStringExtra(EXTRA_TARGET_IP)
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
                    ?: PeerDiscovery.defaultDeviceName()
                startIntercom(targetIp, deviceName)
            }
            ACTION_STOP -> {
                stopIntercom()
                stopSelf()
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }
        }
        return START_NOT_STICKY
    }

    private fun startIntercom(targetIp: String?, deviceName: String) {
        if (_state.value.active) return

        val notification = buildNotification("Connecting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        acquireLocks()

        val current = _state.value
        voxGate.thresholdDb = current.voxThresholdDb
        jitterBuffer.reset()
        jitterBuffer.setPreset(current.jitterPreset)
        consecutivePlc = 0

        val codec = OpusCodec(current.bitrateKbps * 1000)
        opusCodec = codec

        val client = UdpIntercomClient(localName = deviceName)
        udpClient = client

        client.start(
            targetHost = targetIp,
            onAudioPacket = { packet ->
                jitterBuffer.push(packet.sequence, packet.payload, packet.flags)
            },
            onPeerUpdate = { connected, name, ip, rtt ->
                _state.update {
                    it.copy(
                        peerConnected = connected,
                        peerName = name,
                        peerIp = ip,
                        rttMs = rtt,
                        packetLossPct = jitterBuffer.packetLossPercent(),
                        bufferMs = jitterBuffer.currentBufferMs()
                    )
                }
                val label = if (connected) "Connected: $name (${rtt}ms)" else "Waiting for peer..."
                updateNotification(label)
            }
        )

        val engine = AudioEngine(this)
        engine.preferBluetooth = current.preferBluetooth
        audioEngine = engine

        engine.start(
            onCaptureFrame = { pcm ->
                val s = _state.value
                val gateOpen = voxGate.process(pcm)
                val shouldTx = when {
                    s.muted -> false
                    s.pttMode -> s.pttPressed
                    else -> gateOpen
                }
                if (shouldTx) {
                    val encoded = codec.encode(pcm)
                    val flags = RideComProtocol.FLAG_VOICE_ACTIVE or
                        (if (s.pttMode && s.pttPressed) RideComProtocol.FLAG_PTT_ACTIVE else 0)
                    client.sendAudio(encoded, flags)
                }
                uiTickCounter++
                if (uiTickCounter >= 4) { // ~12.5 Hz UI meter update
                    uiTickCounter = 0
                    _state.update {
                        it.copy(
                            inputLevelDb = voxGate.lastLevelDb,
                            voxOpen = shouldTx
                        )
                    }
                }
            },
            onNeedPlaybackFrame = { outPcm ->
                when (val result = jitterBuffer.pop()) {
                    is PopResult.Frame -> {
                        consecutivePlc = 0
                        try {
                            codec.decode(result.payload, outPcm)
                        } catch (_: Throwable) {
                            outPcm.fill(0)
                        }
                    }
                    is PopResult.FecRecovery -> {
                        consecutivePlc = 0
                        try {
                            codec.decodeFec(result.nextPayload, outPcm)
                        } catch (_: Throwable) {
                            outPcm.fill(0)
                        }
                    }
                    is PopResult.Missing -> {
                        if (consecutivePlc < 3) {
                            consecutivePlc++
                            try {
                                codec.decodePlc(outPcm)
                            } catch (_: Throwable) {
                                outPcm.fill(0)
                            }
                        } else {
                            outPcm.fill(0)
                        }
                    }
                    is PopResult.Buffering -> {
                        outPcm.fill(0)
                    }
                }
            },
            onRouteChanged = { route ->
                _state.update { it.copy(audioRoute = route) }
            }
        )

        _state.update {
            it.copy(
                active = true,
                peerIp = targetIp ?: "",
                peerConnected = false
            )
        }
    }

    private fun stopIntercom() {
        audioEngine?.stop(); audioEngine = null
        udpClient?.stop(); udpClient = null
        opusCodec = null
        jitterBuffer.reset()
        releaseLocks()
        _state.update {
            it.copy(
                active = false,
                peerConnected = false,
                voxOpen = false,
                inputLevelDb = -90f,
                rttMs = 0,
                packetLossPct = 0f
            )
        }
    }

    fun toggleMute() {
        _state.update { it.copy(muted = !it.muted) }
    }

    fun setPttMode(enabled: Boolean) {
        _state.update { it.copy(pttMode = enabled, pttPressed = false) }
    }

    fun setPttPressed(pressed: Boolean) {
        _state.update { it.copy(pttPressed = pressed) }
    }

    fun setVoxThreshold(db: Float) {
        voxGate.thresholdDb = db
        _state.update { it.copy(voxThresholdDb = db) }
    }

    fun setJitterPreset(preset: JitterPreset) {
        jitterBuffer.setPreset(preset)
        _state.update { it.copy(jitterPreset = preset, bufferMs = jitterBuffer.currentBufferMs()) }
    }

    fun setBitrateKbps(kbps: Int) {
        opusCodec?.setBitrate(kbps * 1000)
        _state.update { it.copy(bitrateKbps = kbps) }
    }

    fun toggleAudioRoute() {
        val nextBt = !_state.value.preferBluetooth
        val route = audioEngine?.switchRoute(nextBt)
            ?: if (nextBt) AudioRoute.BLUETOOTH_HFP else AudioRoute.SPEAKER
        _state.update { it.copy(preferBluetooth = nextBt, audioRoute = route) }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideCom::IntercomWakeLock")
            .apply { acquire() }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "RideCom::LowLatencyWifiLock")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RideCom Active Intercom",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps RideCom audio active while screen is locked"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, IntercomService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RideCom Intercom")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        stopIntercom()
        instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ridecom.START"
        const val ACTION_STOP = "com.ridecom.STOP"
        const val ACTION_TOGGLE_MUTE = "com.ridecom.TOGGLE_MUTE"
        const val EXTRA_TARGET_IP = "target_ip"
        const val EXTRA_DEVICE_NAME = "device_name"

        private const val CHANNEL_ID = "ridecom_intercom_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        var instance: IntercomService? = null
            private set

        private val _state = MutableStateFlow(IntercomState())
        val state: StateFlow<IntercomState> = _state.asStateFlow()

        fun start(context: Context, targetIp: String?, deviceName: String) {
            val intent = Intent(context, IntercomService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TARGET_IP, targetIp)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, IntercomService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateJitterPreset(preset: JitterPreset) {
            val activeSvc = instance
            if (activeSvc != null) {
                activeSvc.setJitterPreset(preset)
            } else {
                _state.update { it.copy(jitterPreset = preset, bufferMs = preset.targetFrames * RideComProtocol.FRAME_MS) }
            }
        }

        fun updateBitrateKbps(kbps: Int) {
            val activeSvc = instance
            if (activeSvc != null) {
                activeSvc.setBitrateKbps(kbps)
            } else {
                _state.update { it.copy(bitrateKbps = kbps) }
            }
        }
    }
}
