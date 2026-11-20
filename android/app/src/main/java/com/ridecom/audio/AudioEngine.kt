package com.ridecom.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import com.ridecom.protocol.RideComProtocol
import java.util.concurrent.atomic.AtomicBoolean

enum class AudioRoute(val label: String) {
    BLUETOOTH_HFP("Bluetooth Helmet"),
    WIRED_HEADSET("Wired Headset"),
    SPEAKER("Phone Speaker"),
    EARPIECE("Earpiece")
}

class AudioEngine(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    @Volatile
    var currentRoute: AudioRoute = AudioRoute.SPEAKER
        private set

    @Volatile
    var preferBluetooth: Boolean = true

    @SuppressLint("MissingPermission")
    fun start(
        onCaptureFrame: (ShortArray) -> Unit,
        onNeedPlaybackFrame: (ShortArray) -> Unit,
        onRouteChanged: (AudioRoute) -> Unit
    ) {
        if (!running.compareAndSet(false, true)) return

        configureAudioRouting(preferBluetooth)
        onRouteChanged(currentRoute)

        val frameBytes = RideComProtocol.FRAME_SAMPLES * 2
        val minRecBuf = AudioRecord.getMinBufferSize(
            RideComProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameBytes * 6)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            RideComProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minRecBuf
        )
        audioRecord = record

        val sessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        }

        val minPlayBuf = AudioTrack.getMinBufferSize(
            RideComProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameBytes * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RideComProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minPlayBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        audioTrack = track

        record.startRecording()
        track.play()

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(RideComProtocol.FRAME_SAMPLES)
            while (running.get()) {
                val read = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read == pcm.size) {
                    onCaptureFrame(pcm)
                }
            }
        }, "RideCom-AudioCapture").apply { start() }

        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(RideComProtocol.FRAME_SAMPLES)
            while (running.get()) {
                onNeedPlaybackFrame(pcm)
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            }
        }, "RideCom-AudioPlayback").apply { start() }
    }

    fun switchRoute(useBluetooth: Boolean): AudioRoute {
        preferBluetooth = useBluetooth
        configureAudioRouting(useBluetooth)
        return currentRoute
    }

    @Suppress("DEPRECATION")
    private fun configureAudioRouting(useBluetooth: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val btDevice = if (useBluetooth) {
                devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            } else null

            val wiredDevice = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }

            val speakerDevice = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

            when {
                btDevice != null -> {
                    audioManager.setCommunicationDevice(btDevice)
                    currentRoute = AudioRoute.BLUETOOTH_HFP
                }
                wiredDevice != null -> {
                    audioManager.setCommunicationDevice(wiredDevice)
                    currentRoute = AudioRoute.WIRED_HEADSET
                }
                speakerDevice != null -> {
                    audioManager.setCommunicationDevice(speakerDevice)
                    currentRoute = AudioRoute.SPEAKER
                }
                else -> {
                    audioManager.clearCommunicationDevice()
                    currentRoute = AudioRoute.EARPIECE
                }
            }
        } else {
            val hasBt = useBluetooth && audioManager.isBluetoothScoAvailableOffCall
            if (hasBt) {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                currentRoute = AudioRoute.BLUETOOTH_HFP
            } else {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
                currentRoute = AudioRoute.SPEAKER
            }
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!running.compareAndSet(true, false)) return

        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}

        captureThread?.join(400)
        playbackThread?.join(400)
        captureThread = null
        playbackThread = null

        aec?.release(); aec = null
        ns?.release(); ns = null
        agc?.release(); agc = null

        audioRecord?.release(); audioRecord = null
        audioTrack?.release(); audioTrack = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
