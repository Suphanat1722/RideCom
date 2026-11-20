package com.ridecom.codec

import com.ridecom.protocol.RideComProtocol
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

class OpusCodec(bitrateBps: Int = 24000) {
    private val encoder: OpusEncoder = OpusEncoder(
        RideComProtocol.SAMPLE_RATE,
        RideComProtocol.CHANNELS,
        OpusApplication.OPUS_APPLICATION_VOIP
    ).apply {
        bitrate = bitrateBps
        complexity = 5 // Balanced for Samsung Galaxy A12 Exynos 850
        signalType = OpusSignal.OPUS_SIGNAL_VOICE
        useInbandFEC = true
        packetLossPercent = 15 // Tuned for 2.4 GHz Wi-Fi + BT coexistence
    }

    private val decoder: OpusDecoder = OpusDecoder(
        RideComProtocol.SAMPLE_RATE,
        RideComProtocol.CHANNELS
    )

    private val encodeScratch = ByteArray(1276)

    @Synchronized
    fun setBitrate(bitrateBps: Int) {
        encoder.bitrate = bitrateBps.coerceIn(12000, 48000)
    }

    @Synchronized
    fun encode(pcm: ShortArray): ByteArray {
        val len = encoder.encode(
            pcm,
            0,
            RideComProtocol.FRAME_SAMPLES,
            encodeScratch,
            0,
            encodeScratch.size
        )
        return encodeScratch.copyOf(len)
    }

    @Synchronized
    fun decode(opusPacket: ByteArray, outPcm: ShortArray): Int {
        return decoder.decode(
            opusPacket,
            0,
            opusPacket.size,
            outPcm,
            0,
            RideComProtocol.FRAME_SAMPLES,
            false
        )
    }

    @Synchronized
    fun decodeFec(nextOpusPacket: ByteArray, outPcm: ShortArray): Int {
        return decoder.decode(
            nextOpusPacket,
            0,
            nextOpusPacket.size,
            outPcm,
            0,
            RideComProtocol.FRAME_SAMPLES,
            true
        )
    }

    @Synchronized
    fun decodePlc(outPcm: ShortArray): Int {
        return decoder.decode(
            null,
            0,
            0,
            outPcm,
            0,
            RideComProtocol.FRAME_SAMPLES,
            false
        )
    }
}
