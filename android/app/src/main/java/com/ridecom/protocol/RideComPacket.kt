package com.ridecom.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object RideComProtocol {
    const val MAGIC_0: Byte = 0x52 // 'R'
    const val MAGIC_1: Byte = 0x43 // 'C'
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 12
    const val DEFAULT_PORT: Int = 50000
    const val SERVICE_TYPE: String = "_ridecom._udp."

    const val SAMPLE_RATE: Int = 16000
    const val CHANNELS: Int = 1
    const val FRAME_MS: Int = 20
    const val FRAME_SAMPLES: Int = SAMPLE_RATE * FRAME_MS / 1000 // 320 samples

    const val FLAG_VOICE_ACTIVE: Int = 1 shl 0
    const val FLAG_PTT_ACTIVE: Int = 1 shl 1
    const val FLAG_FEC_PRESENT: Int = 1 shl 2
}

enum class PacketType(val code: Int) {
    AUDIO(0x1),
    HELLO(0x2),
    HELLO_ACK(0x3),
    PING(0x4),
    PONG(0x5),
    BYE(0x6);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

data class RideComPacket(
    val type: PacketType,
    val flags: Int = 0,
    val sequence: Int = 0,
    val senderId: Int = 0,
    val timestampMs: Long = 0L,
    val payload: ByteArray = ByteArray(0)
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(RideComProtocol.HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(RideComProtocol.MAGIC_0)
        buf.put(RideComProtocol.MAGIC_1)
        val verType = ((RideComProtocol.VERSION and 0x0F) shl 4) or (type.code and 0x0F)
        buf.put(verType.toByte())
        buf.put((flags and 0xFF).toByte())
        buf.putShort((sequence and 0xFFFF).toShort())
        buf.putShort((senderId and 0xFFFF).toShort())
        buf.putInt((timestampMs and 0xFFFFFFFFL).toInt())
        if (payload.isNotEmpty()) {
            buf.put(payload)
        }
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray, length: Int = bytes.size): RideComPacket? {
            if (length < RideComProtocol.HEADER_SIZE) return null
            if (bytes[0] != RideComProtocol.MAGIC_0 || bytes[1] != RideComProtocol.MAGIC_1) return null

            val verType = bytes[2].toInt() and 0xFF
            val version = (verType ushr 4) and 0x0F
            if (version != RideComProtocol.VERSION) return null

            val type = PacketType.fromCode(verType and 0x0F) ?: return null
            val flags = bytes[3].toInt() and 0xFF

            val buf = ByteBuffer.wrap(bytes, 4, length - 4).order(ByteOrder.BIG_ENDIAN)
            val sequence = buf.short.toInt() and 0xFFFF
            val senderId = buf.short.toInt() and 0xFFFF
            val timestampMs = buf.int.toLong() and 0xFFFFFFFFL

            val payloadLen = length - RideComProtocol.HEADER_SIZE
            val payload = if (payloadLen > 0) {
                bytes.copyOfRange(RideComProtocol.HEADER_SIZE, length)
            } else {
                ByteArray(0)
            }

            return RideComPacket(
                type = type,
                flags = flags,
                sequence = sequence,
                senderId = senderId,
                timestampMs = timestampMs,
                payload = payload
            )
        }
    }
}
