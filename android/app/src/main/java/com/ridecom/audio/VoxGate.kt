package com.ridecom.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Wind-noise gate (VOX) with hangover timer so word endings are never clipped.
 */
class VoxGate(
    @Volatile var thresholdDb: Float = -42f,
    @Volatile var hangoverMs: Long = 350L
) {
    @Volatile
    var lastLevelDb: Float = -90f
        private set

    private var lastOpenTimeMs: Long = 0L

    fun process(pcm: ShortArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        var sumSq = 0.0
        for (s in pcm) {
            val norm = s / 32768.0
            sumSq += norm * norm
        }
        val rms = sqrt(sumSq / pcm.size.coerceAtLeast(1))
        val db = if (rms > 1e-6) (20.0 * log10(rms)).toFloat() else -90f
        lastLevelDb = db

        return if (db >= thresholdDb) {
            lastOpenTimeMs = nowMs
            true
        } else {
            (nowMs - lastOpenTimeMs) <= hangoverMs
        }
    }
}
