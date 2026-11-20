package com.ridecom.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.ridecom.protocol.RideComProtocol
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures playback audio from other Android apps (YouTube, Chrome, TikTok, etc.)
 * via Android 10+ AudioPlaybackCapture (MediaProjection).
 *
 * Uses a sample-level circular ring buffer with 40ms pre-buffer to completely eliminate
 * clock-skew micro-stutters between the Media Mixer and the Microphone AudioRecord clock,
 * plus dual-side Auto Voice Ducking (ducks both remote stream and local Android playback).
 */
class SystemAudioSharer(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)

    // Sample-level ring buffer: 3200 samples = 200ms capacity @ 16kHz
    private val ringLock = Any()
    private val ring = ShortArray(3200)
    private var readPos = 0
    private var writePos = 0
    private var available = 0
    private var prebuffering = true

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    private var duckFocusRequest: AudioFocusRequest? = null
    private var localDucked = false

    @Volatile var isSharing: Boolean = false
        private set
    @Volatile var volume: Float = 0.50f

    private var currentDuckGain: Float = 1.0f
    private var lastSpeechMs: Long = 0L

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun start(projection: MediaProjection): Boolean {
        stop()

        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RideComProtocol.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val frameBytes = RideComProtocol.FRAME_SAMPLES * 2
            val minBuf = AudioRecord.getMinBufferSize(
                RideComProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameBytes * 8)

            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                projection.stop()
                return false
            }

            synchronized(ringLock) {
                readPos = 0
                writePos = 0
                available = 0
                prebuffering = true
            }

            mediaProjection = projection
            audioRecord = record
            running.set(true)
            isSharing = true
            currentDuckGain = 1.0f
            lastSpeechMs = 0L

            record.startRecording()

            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val chunk = ShortArray(RideComProtocol.FRAME_SAMPLES)
                while (running.get()) {
                    val read = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        writeRing(chunk, read)
                    }
                }
            }, "RideCom-SystemAudioCapture").apply { start() }

            true
        } catch (_: Throwable) {
            stop()
            false
        }
    }

    private fun writeRing(samples: ShortArray, count: Int) {
        synchronized(ringLock) {
            // If buffer drifts beyond 140ms (2240 samples), trim gently to 60ms (960 samples)
            if (available + count > 2240) {
                val drop = (available + count) - 960
                val actualDrop = minOf(drop, available)
                readPos = (readPos + actualDrop) % ring.size
                available -= actualDrop
            }
            for (i in 0 until count) {
                if (available < ring.size) {
                    ring[writePos] = samples[i]
                    writePos = (writePos + 1) % ring.size
                    available++
                }
            }
            if (prebuffering && available >= RideComProtocol.FRAME_SAMPLES * 2) {
                prebuffering = false
            }
        }
    }

    /**
     * Pulls the next continuous 20ms frame scaled by volume + smooth auto voice ducking.
     * Also requests temporary Android AudioFocus ducking so local YouTube/Chrome lowers
     * volume smoothly in the Android rider's own helmet when either rider speaks.
     */
    fun nextFrame(speechActive: Boolean, nowMs: Long): ShortArray? {
        if (!isSharing) return null

        if (speechActive) {
            lastSpeechMs = nowMs
        }
        val shouldDuck = (nowMs - lastSpeechMs) <= 750L
        updateLocalDucking(shouldDuck)

        val out = ShortArray(RideComProtocol.FRAME_SAMPLES)
        val gotSamples: Boolean
        synchronized(ringLock) {
            if (prebuffering || available < RideComProtocol.FRAME_SAMPLES) {
                prebuffering = true
                gotSamples = false
            } else {
                for (i in 0 until RideComProtocol.FRAME_SAMPLES) {
                    out[i] = ring[readPos]
                    readPos = (readPos + 1) % ring.size
                }
                available -= RideComProtocol.FRAME_SAMPLES
                gotSamples = true
            }
        }
        if (!gotSamples) return null

        val targetDuck = if (shouldDuck) 0.15f else 1.0f
        // Fast attack (duck down in ~40ms), silky release (~500ms)
        val alpha = if (targetDuck < currentDuckGain) 0.45f else 0.05f
        currentDuckGain += (targetDuck - currentDuckGain) * alpha

        val gain = volume.coerceIn(0f, 1.0f) * currentDuckGain
        var energySum = 0L
        for (i in out.indices) {
            val v = (out[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out[i] = v
            energySum += kotlin.math.abs(v.toInt())
        }
        // Only skip transmitting if completely digital-silent (YouTube paused)
        return if (energySum > 64L) out else null
    }

    private fun updateLocalDucking(duck: Boolean) {
        if (duck == localDucked) return
        localDucked = duck
        try {
            if (duck) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                duckFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                duckFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                duckFocusRequest = null
            }
        } catch (_: Throwable) {}
    }

    fun stop() {
        running.set(false)
        isSharing = false
        updateLocalDucking(false)
        try { audioRecord?.stop() } catch (_: Throwable) {}
        captureThread?.join(300)
        captureThread = null
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        synchronized(ringLock) {
            readPos = 0
            writePos = 0
            available = 0
            prebuffering = true
        }
        currentDuckGain = 1.0f
        lastSpeechMs = 0L
    }
}
