package com.ridecom.codec

import com.ridecom.protocol.RideComProtocol
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

class OpusCodec(bitrateBps: Int = 24000) {
    private val encodeLock = Any()
    private val decodeLock = Any()

    private val encoder: OpusEncoder = OpusEncoder(
        RideComProtocol.SAMPLE_RATE,
        RideComProtocol.CHANNELS,
        OpusApplication.OPUS_APPLICATION_VOIP
    ).apply {
        bitrate = bitrateBps
        complexity = 1 // Ultra-fast pure-Java SILK encode (~1-2ms/frame on Cortex-A55)
        signalType = OpusSignal.OPUS_SIGNAL_VOICE
        useInbandFEC = false
    }

    private val decoder: OpusDecoder = OpusDecoder(
        RideComProtocol.SAMPLE_RATE,
        RideComProtocol.CHANNELS
    )

    private val encodeScratch = ByteArray(1276)

    fun setBitrate(bitrateBps: Int) {
        synchronized(encodeLock) {
            encoder.bitrate = bitrateBps.coerceIn(12000, 48000)
        }
    }

    fun encode(pcm: ShortArray): ByteArray {
        synchronized(encodeLock) {
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
    }

    fun decode(opusPacket: ByteArray, outPcm: ShortArray): Int {
        synchronized(decodeLock) {
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
    }

    fun decodeFec(nextOpusPacket: ByteArray, outPcm: ShortArray): Int {
        synchronized(decodeLock) {
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
    }

    fun decodePlc(outPcm: ShortArray): Int {
        synchronized(decodeLock) {
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
}
