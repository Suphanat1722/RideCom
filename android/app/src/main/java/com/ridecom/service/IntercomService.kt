package com.ridecom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ridecom.audio.AudioCueMixer
import com.ridecom.audio.AudioEngine
import com.ridecom.audio.AudioRoute
import com.ridecom.audio.CueTone
import com.ridecom.audio.MusicSharer
import com.ridecom.audio.SystemAudioSharer
import com.ridecom.audio.VoxGate
import com.ridecom.codec.OpusCodec
import com.ridecom.data.RideComPrefs
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
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue

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
    val voxThresholdDb: Float = -58f,
    val rttMs: Int = 0,
    val packetLossPct: Float = 0f,
    val bufferMs: Int = 40,
    val jitterPreset: JitterPreset = JitterPreset.BALANCED,
    val bitrateKbps: Int = 24,
    val audioRoute: AudioRoute = AudioRoute.SPEAKER,
    val preferBluetooth: Boolean = true,
    val audioCuesEnabled: Boolean = true,
    val musicLoaded: Boolean = false,
    val musicPlaying: Boolean = false,
    val musicTrackTitle: String = "",
    val musicVolume: Float = 0.35f,
    val appAudioSharing: Boolean = false
)

class IntercomService : Service() {

    private lateinit var prefs: RideComPrefs
    private lateinit var musicSharer: MusicSharer

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var audioEngine: AudioEngine? = null
    private var udpClient: UdpIntercomClient? = null
    private var opusCodec: OpusCodec? = null
    private val jitterBuffer = JitterBuffer(JitterPreset.BALANCED)
    private val voxGate = VoxGate()
    private val cueMixer = AudioCueMixer()
    private val systemAudioSharer = SystemAudioSharer()
    private val localMusicQueue = ArrayBlockingQueue<ShortArray>(8)

    private var consecutivePlc = 0
    private var wasPlayingRemote = false
    private var lastRemoteAudioMs = 0L
    private var wasPeerConnected = false
    private var uiTickCounter = 0

    private var perfEncCount = 0
    private var perfEncTotalUs = 0L
    private var perfDecCount = 0
    private var perfDecTotalUs = 0L
    private var perfLastLogMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = RideComPrefs(this)
        musicSharer = MusicSharer(this)
        loadSavedSettings()
        createNotificationChannel()
    }

    private fun loadSavedSettings() {
        val vox = prefs.voxThresholdDb
        val preset = prefs.jitterPreset
        val bitrate = prefs.bitrateKbps
        val cues = prefs.audioCuesEnabled
        val musicVol = prefs.musicVolume

        voxGate.thresholdDb = vox
        jitterBuffer.setPreset(preset)
        cueMixer.enabled = cues
        musicSharer.volume = musicVol

        _state.update {
            it.copy(
                voxThresholdDb = vox,
                jitterPreset = preset,
                bufferMs = preset.targetFrames * RideComProtocol.FRAME_MS,
                bitrateKbps = bitrate,
                audioCuesEnabled = cues,
                musicVolume = musicVol
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val targetIp = intent.getStringExtra(EXTRA_TARGET_IP)
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: prefs.deviceName
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
        cueMixer.enabled = current.audioCuesEnabled
        cueMixer.clear()
        localMusicQueue.clear()
        jitterBuffer.reset()
        jitterBuffer.setPreset(current.jitterPreset)
        consecutivePlc = 0
        wasPlayingRemote = false
        wasPeerConnected = false
        lastRemoteAudioMs = 0L

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
                if (connected && !wasPeerConnected) {
                    wasPeerConnected = true
                    cueMixer.play(CueTone.CONNECTED)
                    if (ip.isNotBlank()) {
                        prefs.lastPeerIp = ip
                        if (name.isNotBlank()) prefs.lastPeerName = name
                    }
                } else if (!connected && wasPeerConnected) {
                    wasPeerConnected = false
                    cueMixer.play(CueTone.DISCONNECTED)
                }

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
                val label = if (connected) "Connected: $name (${rtt}ms)" else "Waiting for rider..."
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
                val shouldTxVoice = when {
                    s.muted -> false
                    s.pttMode -> s.pttPressed
                    else -> gateOpen
                }

                val nowMs = SystemClock.elapsedRealtime()
                val peerSpeaking = (nowMs - lastRemoteAudioMs) < 450L
                val anyVoice = shouldTxVoice || peerSpeaking

                val musicFrame = musicSharer.nextFrame(voiceActive = anyVoice)
                if (musicFrame != null) {
                    if (!localMusicQueue.offer(musicFrame)) {
                        localMusicQueue.poll()
                        localMusicQueue.offer(musicFrame)
                    }
                }
                val sysAudioFrame = systemAudioSharer.nextFrame(voiceActive = anyVoice)

                var txPcm: ShortArray? = if (shouldTxVoice) pcm.copyOf() else null
                if (musicFrame != null) {
                    if (txPcm == null) txPcm = musicFrame.copyOf()
                    else MusicSharer.mixInto(txPcm, musicFrame)
                }
                if (sysAudioFrame != null) {
                    if (txPcm == null) txPcm = sysAudioFrame
                    else MusicSharer.mixInto(txPcm, sysAudioFrame)
                }

                if (txPcm != null) {
                    val t0 = System.nanoTime()
                    val encoded = codec.encode(txPcm)
                    perfEncTotalUs += (System.nanoTime() - t0) / 1000L
                    perfEncCount++
                    if (encoded.isNotEmpty()) {
                        val flags = (if (shouldTxVoice) RideComProtocol.FLAG_VOICE_ACTIVE else 0) or
                            (if (s.pttMode && s.pttPressed) RideComProtocol.FLAG_PTT_ACTIVE else 0)
                        client.sendAudio(encoded, flags)
                    }
                }

                if (nowMs - perfLastLogMs >= 2000L) {
                    perfLastLogMs = nowMs
                    val encAvg = if (perfEncCount > 0) perfEncTotalUs / perfEncCount / 1000.0 else 0.0
                    val decAvg = if (perfDecCount > 0) perfDecTotalUs / perfDecCount / 1000.0 else 0.0
                    Log.d(
                        "RideComPerf",
                        String.format(
                            Locale.US,
                            "enc=%.2fms(%d) dec=%.2fms(%d) loss=%.1f%% buf=%dms",
                            encAvg, perfEncCount, decAvg, perfDecCount,
                            jitterBuffer.packetLossPercent(), jitterBuffer.currentBufferMs()
                        )
                    )
                    perfEncCount = 0; perfEncTotalUs = 0L
                    perfDecCount = 0; perfDecTotalUs = 0L
                }

                uiTickCounter++
                if (uiTickCounter >= 4) {
                    uiTickCounter = 0
                    _state.update {
                        it.copy(
                            inputLevelDb = voxGate.lastLevelDb,
                            voxOpen = shouldTxVoice
                        )
                    }
                }
            },
            onNeedPlaybackFrame = { outPcm ->
                var hasRemoteAudio = false
                when (val result = jitterBuffer.pop()) {
                    is PopResult.Frame -> {
                        consecutivePlc = 0
                        try {
                            val t0 = System.nanoTime()
                            val n = codec.decode(result.payload, outPcm)
                            perfDecTotalUs += (System.nanoTime() - t0) / 1000L
                            perfDecCount++
                            if (n > 0) {
                                hasRemoteAudio = true
                                if ((result.flags and RideComProtocol.FLAG_VOICE_ACTIVE) != 0) {
                                    lastRemoteAudioMs = SystemClock.elapsedRealtime()
                                }
                                if (!wasPlayingRemote) {
                                    AudioCueMixer.applyFade(outPcm, fadeIn = true)
                                }
                            }
                        } catch (_: Throwable) {
                            outPcm.fill(0)
                        }
                    }
                    is PopResult.FecRecovery -> {
                        consecutivePlc = 0
                        try {
                            if (codec.decodeFec(result.nextPayload, outPcm) > 0) {
                                hasRemoteAudio = true
                            }
                        } catch (_: Throwable) {
                            outPcm.fill(0)
                        }
                    }
                    is PopResult.Missing -> {
                        if (consecutivePlc < 4) {
                            consecutivePlc++
                            try {
                                if (codec.decodePlc(outPcm) > 0) {
                                    hasRemoteAudio = true
                                    if (consecutivePlc == 4) {
                                        AudioCueMixer.applyFade(outPcm, fadeIn = false)
                                    }
                                }
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

                if (!hasRemoteAudio) {
                    outPcm.fill(0)
                }
                wasPlayingRemote = hasRemoteAudio

                // Mix local shared music monitor + in-helmet connection/mute chimes
                localMusicQueue.poll()?.let { MusicSharer.mixInto(outPcm, it) }
                cueMixer.mixInto(outPcm)
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
        systemAudioSharer.stop()
        musicSharer.stop()
        localMusicQueue.clear()
        cueMixer.clear()
        audioEngine?.stop(); audioEngine = null
        udpClient?.stop(); udpClient = null
        opusCodec?.release(); opusCodec = null
        jitterBuffer.reset()
        releaseLocks()
        _state.update {
            it.copy(
                active = false,
                peerConnected = false,
                voxOpen = false,
                inputLevelDb = -90f,
                rttMs = 0,
                packetLossPct = 0f,
                musicLoaded = false,
                musicPlaying = false,
                musicTrackTitle = "",
                appAudioSharing = false
            )
        }
    }

    fun toggleMute() {
        val nextMuted = !_state.value.muted
        cueMixer.play(if (nextMuted) CueTone.MUTE_ON else CueTone.MUTE_OFF)
        _state.update { it.copy(muted = nextMuted) }
    }

    fun setPttMode(enabled: Boolean) {
        _state.update { it.copy(pttMode = enabled, pttPressed = false) }
    }

    fun setPttPressed(pressed: Boolean) {
        _state.update { it.copy(pttPressed = pressed) }
    }

    fun setVoxThreshold(db: Float) {
        voxGate.thresholdDb = db
        prefs.voxThresholdDb = db
        _state.update { it.copy(voxThresholdDb = db) }
    }

    fun setJitterPreset(preset: JitterPreset) {
        jitterBuffer.setPreset(preset)
        prefs.jitterPreset = preset
        _state.update { it.copy(jitterPreset = preset, bufferMs = jitterBuffer.currentBufferMs()) }
    }

    fun setBitrateKbps(kbps: Int) {
        opusCodec?.setBitrate(kbps * 1000)
        prefs.bitrateKbps = kbps
        _state.update { it.copy(bitrateKbps = kbps) }
    }

    fun setAudioCuesEnabled(enabled: Boolean) {
        cueMixer.enabled = enabled
        prefs.audioCuesEnabled = enabled
        _state.update { it.copy(audioCuesEnabled = enabled) }
    }

    fun loadMusic(uri: Uri) {
        musicSharer.loadAndPlay(uri)
        _state.update {
            it.copy(
                musicLoaded = musicSharer.isLoaded,
                musicPlaying = musicSharer.isPlaying,
                musicTrackTitle = musicSharer.trackTitle
            )
        }
    }

    fun toggleMusicPlayPause() {
        val playing = musicSharer.togglePlayPause()
        _state.update { it.copy(musicPlaying = playing) }
    }

    fun stopMusic() {
        musicSharer.stop()
        localMusicQueue.clear()
        _state.update {
            it.copy(musicLoaded = false, musicPlaying = false, musicTrackTitle = "")
        }
    }

    fun setMusicVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 0.85f)
        musicSharer.volume = clamped
        systemAudioSharer.volume = clamped
        prefs.musicVolume = clamped
        _state.update { it.copy(musicVolume = clamped) }
    }

    fun startAppAudioShare(resultCode: Int, data: Intent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val statusLabel = if (_state.value.peerConnected) {
                "Connected: ${_state.value.peerName} (Sharing App Audio)"
            } else {
                "Sharing App Audio..."
            }
            startForeground(
                NOTIF_ID,
                buildNotification(statusLabel),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data) ?: return false
            systemAudioSharer.volume = _state.value.musicVolume
            val ok = systemAudioSharer.start(projection)
            _state.update { it.copy(appAudioSharing = ok) }
            ok
        } catch (_: Throwable) {
            stopAppAudioShare()
            false
        }
    }

    fun stopAppAudioShare() {
        systemAudioSharer.stop()
        _state.update { it.copy(appAudioSharing = false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && _state.value.active) {
            try {
                startForeground(
                    NOTIF_ID,
                    buildNotification(if (_state.value.peerConnected) "Connected: ${_state.value.peerName}" else "Waiting for rider..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (_: Throwable) {}
        }
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
        val muteIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, IntercomService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, IntercomService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RideCom Intercom")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Mute", muteIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", stopIntent)
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

        fun hydratePrefs(prefs: RideComPrefs) {
            if (_state.value.active) return
            _state.update {
                it.copy(
                    voxThresholdDb = prefs.voxThresholdDb,
                    jitterPreset = prefs.jitterPreset,
                    bufferMs = prefs.jitterPreset.targetFrames * RideComProtocol.FRAME_MS,
                    bitrateKbps = prefs.bitrateKbps,
                    audioCuesEnabled = prefs.audioCuesEnabled,
                    musicVolume = prefs.musicVolume
                )
            }
        }

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

        fun updateJitterPreset(prefs: RideComPrefs, preset: JitterPreset) {
            val activeSvc = instance
            if (activeSvc != null) {
                activeSvc.setJitterPreset(preset)
            } else {
                prefs.jitterPreset = preset
                _state.update { it.copy(jitterPreset = preset, bufferMs = preset.targetFrames * RideComProtocol.FRAME_MS) }
            }
        }

        fun updateBitrateKbps(prefs: RideComPrefs, kbps: Int) {
            val activeSvc = instance
            if (activeSvc != null) {
                activeSvc.setBitrateKbps(kbps)
            } else {
                prefs.bitrateKbps = kbps
                _state.update { it.copy(bitrateKbps = kbps) }
            }
        }

        fun updateAudioCues(prefs: RideComPrefs, enabled: Boolean) {
            val activeSvc = instance
            if (activeSvc != null) {
                activeSvc.setAudioCuesEnabled(enabled)
            } else {
                prefs.audioCuesEnabled = enabled
                _state.update { it.copy(audioCuesEnabled = enabled) }
            }
        }
    }
}
