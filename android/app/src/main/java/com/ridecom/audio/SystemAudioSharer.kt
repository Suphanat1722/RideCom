package com.ridecom.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.ridecom.protocol.RideComProtocol
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures playback audio from other Android apps (YouTube, Chrome, TikTok, etc.)
 * via Android 10+ AudioPlaybackCapture (MediaProjection), with smooth Auto Voice Ducking.
 *
 * Excludes USAGE_VOICE_COMMUNICATION so RideCom never captures its own intercom voice.
 */
class SystemAudioSharer {
    private val running = AtomicBoolean(false)
    private val frameQueue = ArrayBlockingQueue<ShortArray>(8)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile var isSharing: Boolean = false
        private set
    @Volatile var volume: Float = 0.50f

    private var currentDuckGain: Float = 1.0f

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
            ).coerceAtLeast(frameBytes * 6)

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

            mediaProjection = projection
            audioRecord = record
            running.set(true)
            isSharing = true
            currentDuckGain = 1.0f
            frameQueue.clear()

            record.startRecording()

            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val pcm = ShortArray(RideComProtocol.FRAME_SAMPLES)
                while (running.get()) {
                    val read = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read == pcm.size) {
                        val copy = pcm.copyOf()
                        if (!frameQueue.offer(copy)) {
                            frameQueue.poll()
                            frameQueue.offer(copy)
                        }
                    }
                }
            }, "RideCom-SystemAudioCapture").apply { start() }

            true
        } catch (_: Throwable) {
            stop()
            false
        }
    }

    /**
     * Pulls the next 20ms system-audio frame scaled by volume + smooth auto voice ducking.
     * Returns null when not sharing or silent.
     */
    fun nextFrame(voiceActive: Boolean): ShortArray? {
        if (!isSharing) return null
        val raw = frameQueue.poll() ?: return null

        val targetDuck = if (voiceActive) 0.25f else 1.0f
        val alpha = if (targetDuck < currentDuckGain) 0.35f else 0.06f
        currentDuckGain += (targetDuck - currentDuckGain) * alpha

        val gain = volume.coerceIn(0f, 1.0f) * currentDuckGain
        val out = ShortArray(raw.size)
        var nonZero = false
        for (i in raw.indices) {
            val s = (raw[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            if (s != 0.toShort()) nonZero = true
            out[i] = s
        }
        return if (nonZero) out else null
    }

    fun stop() {
        running.set(false)
        isSharing = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        captureThread?.join(300)
        captureThread = null
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        frameQueue.clear()
        currentDuckGain = 1.0f
    }
}
