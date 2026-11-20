package com.ridecom.audio

import com.ridecom.protocol.RideComProtocol
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.sin

enum class CueTone {
    CONNECTED,
    DISCONNECTED,
    MUTE_ON,
    MUTE_OFF
}

/**
 * Generates clean, soft-enveloped 16kHz PCM audio cues for in-helmet feedback
 * (so riders hear connect/disconnect/mute status while the phone is locked in a pocket).
 */
class AudioCueMixer {
    private val queue = ConcurrentLinkedQueue<ShortArray>()
    @Volatile
    var enabled: Boolean = true

    fun play(tone: CueTone) {
        if (!enabled) return
        val samples = when (tone) {
            CueTone.CONNECTED -> concat(
                synthTone(523.25, 75, 0.22), // C5
                synthTone(783.99, 105, 0.25) // G5
            )
            CueTone.DISCONNECTED -> concat(
                synthTone(622.25, 90, 0.22), // Eb5
                synthTone(415.30, 130, 0.22) // Ab4
            )
            CueTone.MUTE_ON -> synthTone(392.0, 65, 0.20)
            CueTone.MUTE_OFF -> synthTone(698.46, 65, 0.20)
        }
        var offset = 0
        while (offset < samples.size) {
            val frame = ShortArray(RideComProtocol.FRAME_SAMPLES)
            val copyLen = minOf(frame.size, samples.size - offset)
            System.arraycopy(samples, offset, frame, 0, copyLen)
            queue.offer(frame)
            offset += frame.size
        }
    }

    fun mixInto(outPcm: ShortArray) {
        val cueFrame = queue.poll() ?: return
        for (i in outPcm.indices) {
            val mixed = outPcm[i].toInt() + cueFrame[i].toInt()
            outPcm[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun clear() = queue.clear()

    private fun synthTone(freqHz: Double, durationMs: Int, gain: Double): ShortArray {
        val count = (RideComProtocol.SAMPLE_RATE * durationMs) / 1000
        val out = ShortArray(count)
        val fadeSamples = minOf(count / 4, 80) // 5ms soft envelope
        for (i in 0 until count) {
            val env = when {
                i < fadeSamples -> i.toDouble() / fadeSamples
                i > count - fadeSamples -> (count - i).toDouble() / fadeSamples
                else -> 1.0
            }
            val wave = sin(2.0 * PI * freqHz * i / RideComProtocol.SAMPLE_RATE)
            out[i] = (wave * gain * env * 32767.0).toInt().toShort()
        }
        return out
    }

    private fun concat(a: ShortArray, b: ShortArray): ShortArray {
        val out = ShortArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    companion object {
        fun applyFade(pcm: ShortArray, fadeIn: Boolean) {
            val ramp = minOf(32, pcm.size) // 2ms smooth transition
            for (i in 0 until ramp) {
                val gain = if (fadeIn) i.toFloat() / ramp else 1f - (i.toFloat() / ramp)
                val idx = if (fadeIn) i else (pcm.size - ramp + i)
                pcm[idx] = (pcm[idx] * gain).toInt().toShort()
            }
        }
    }
}
