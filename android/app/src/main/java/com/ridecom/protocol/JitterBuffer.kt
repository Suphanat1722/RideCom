package com.ridecom.protocol

enum class JitterPreset(val label: String, val targetFrames: Int, val adaptive: Boolean) {
    ULTRA_LOW("Ultra-Low (20ms)", 1, false),
    BALANCED("Balanced (40ms)", 2, false),
    STABLE("Stable (80ms)", 4, false),
    AUTO("Auto (20–100ms)", 2, true)
}

sealed class PopResult {
    data class Frame(val payload: ByteArray, val flags: Int) : PopResult()
    data class FecRecovery(val nextPayload: ByteArray) : PopResult()
    data object Missing : PopResult()
    data object Buffering : PopResult()
}

/**
 * Thread-safe, uint16 wrap-around-safe ring jitter buffer for 20ms Opus frames.
 */
class JitterBuffer(preset: JitterPreset = JitterPreset.BALANCED) {
    private val capacity = 32
    private val slots = arrayOfNulls<Slot>(capacity)

    private var preset: JitterPreset = preset
    private var currentTargetFrames: Int = preset.targetFrames
    private var initialized = false
    private var buffering = true
    private var nextReadSeq = 0
    private var highestSeenSeq = 0
    private var stableReads = 0

    var totalExpected: Long = 0L
        private set
    var totalLost: Long = 0L
        private set

    private data class Slot(val seq: Int, val payload: ByteArray, val flags: Int)

    @Synchronized
    fun setPreset(newPreset: JitterPreset) {
        preset = newPreset
        currentTargetFrames = newPreset.targetFrames
    }

    @Synchronized
    fun reset() {
        slots.fill(null)
        initialized = false
        buffering = true
        nextReadSeq = 0
        highestSeenSeq = 0
        stableReads = 0
        totalExpected = 0L
        totalLost = 0L
    }

    @Synchronized
    fun currentBufferMs(): Int = currentTargetFrames * RideComProtocol.FRAME_MS

    @Synchronized
    fun packetLossPercent(): Float {
        if (totalExpected <= 0L) return 0f
        return (totalLost.toFloat() / totalExpected.toFloat()) * 100f
    }

    @Synchronized
    fun push(seq: Int, payload: ByteArray, flags: Int) {
        val normSeq = seq and 0xFFFF
        if (!initialized) {
            initialized = true
            buffering = true
            nextReadSeq = normSeq
            highestSeenSeq = normSeq
        } else {
            val diffFromRead = seqDiff(normSeq, nextReadSeq)
            // Drop very late packets or reset on huge jump (e.g., sender restarted)
            if (diffFromRead < -4 || diffFromRead > 25) {
                if (diffFromRead > 25 || diffFromRead < -25) {
                    slots.fill(null)
                    nextReadSeq = normSeq
                    highestSeenSeq = normSeq
                    buffering = true
                } else {
                    return
                }
            }
            if (seqDiff(normSeq, highestSeenSeq) > 0) {
                highestSeenSeq = normSeq
            }
        }
        slots[normSeq % capacity] = Slot(normSeq, payload, flags)
    }

    @Synchronized
    fun pop(): PopResult {
        if (!initialized) return PopResult.Buffering

        val bufferedDepth = seqDiff(highestSeenSeq, nextReadSeq) + 1
        if (buffering) {
            if (bufferedDepth >= currentTargetFrames) {
                buffering = false
            } else {
                return PopResult.Buffering
            }
        }

        // Catch up if buffer grows too large (prevents creeping latency)
        val maxAllowedDepth = (currentTargetFrames + 3).coerceAtMost(10)
        if (bufferedDepth > maxAllowedDepth) {
            val skip = bufferedDepth - currentTargetFrames
            for (i in 0 until skip) {
                slots[(nextReadSeq + i) % capacity] = null
            }
            nextReadSeq = (nextReadSeq + skip) and 0xFFFF
        }

        totalExpected++
        val idx = nextReadSeq % capacity
        val slot = slots[idx]
        if (slot != null && slot.seq == nextReadSeq) {
            slots[idx] = null
            nextReadSeq = (nextReadSeq + 1) and 0xFFFF
            if (preset.adaptive) {
                stableReads++
                if (stableReads >= 150 && currentTargetFrames > 1) {
                    currentTargetFrames--
                    stableReads = 0
                }
            }
            return PopResult.Frame(slot.payload, slot.flags)
        }

        // Missing packet: check if next packet is already here for In-Band FEC recovery
        totalLost++
        stableReads = 0
        if (preset.adaptive && currentTargetFrames < 5) {
            currentTargetFrames++
        }

        val peekSeq = (nextReadSeq + 1) and 0xFFFF
        val nextSlot = slots[peekSeq % capacity]
        nextReadSeq = peekSeq

        return if (nextSlot != null && nextSlot.seq == peekSeq) {
            PopResult.FecRecovery(nextSlot.payload)
        } else {
            PopResult.Missing
        }
    }

    private fun seqDiff(a: Int, b: Int): Int {
        var diff = (a and 0xFFFF) - (b and 0xFFFF)
        if (diff > 32768) diff -= 65536
        if (diff < -32768) diff += 65536
        return diff
    }
}
