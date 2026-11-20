package com.ridecom.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.ridecom.protocol.RideComProtocol
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes any local audio file (MP3, M4A, AAC, WAV) to 16kHz mono 20ms PCM frames
 * using Android's native MediaExtractor + MediaCodec, with smooth Auto Voice Ducking.
 */
class MusicSharer(private val context: Context) {
    private val frameQueue = ArrayBlockingQueue<ShortArray>(24)
    private val decoding = AtomicBoolean(false)
    private var decoderThread: Thread? = null

    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var isLoaded: Boolean = false
        private set
    @Volatile var trackTitle: String = ""
        private set
    @Volatile var volume: Float = 0.35f

    private var currentUri: Uri? = null
    private var currentDuckGain: Float = 1.0f
    private var lastSpeechMs: Long = 0L

    fun loadAndPlay(uri: Uri) {
        stop()
        currentUri = uri
        trackTitle = queryDisplayName(uri)
        isLoaded = true
        isPlaying = true
        startDecodeThread(uri)
    }

    fun togglePlayPause(): Boolean {
        if (!isLoaded) return false
        isPlaying = !isPlaying
        return isPlaying
    }

    fun stop() {
        decoding.set(false)
        isPlaying = false
        isLoaded = false
        trackTitle = ""
        currentUri = null
        decoderThread?.interrupt()
        decoderThread?.join(300)
        decoderThread = null
        frameQueue.clear()
        currentDuckGain = 1.0f
    }

    /**
     * Pulls the next 20ms music frame scaled by user volume + smooth voice ducking.
     * Returns null when paused or not loaded.
     */
    fun nextFrame(voiceActive: Boolean, nowMs: Long = System.currentTimeMillis()): ShortArray? {
        if (!isPlaying) return null
        val raw = frameQueue.poll() ?: return null

        if (voiceActive) {
            lastSpeechMs = nowMs
        }
        val shouldDuck = (nowMs - lastSpeechMs) <= 750L
        val targetDuck = if (shouldDuck) 0.15f else 1.0f
        // Fast attack (duck down quickly when someone speaks), smooth release
        val alpha = if (targetDuck < currentDuckGain) 0.45f else 0.05f
        currentDuckGain += (targetDuck - currentDuckGain) * alpha

        val gain = volume.coerceIn(0f, 0.85f) * currentDuckGain
        val out = ShortArray(raw.size)
        for (i in raw.indices) {
            out[i] = (raw[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun startDecodeThread(uri: Uri) {
        decoding.set(true)
        decoderThread = Thread({
            while (decoding.get()) {
                val ok = decodeFileOnce(uri)
                if (!ok) break // Stop loop if file cannot be decoded
            }
        }, "RideCom-MusicDecoder").apply { start() }
    }

    private fun decodeFileOnce(uri: Uri): Boolean {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) return false

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            var srcRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            val accum = ShortArray(RideComProtocol.FRAME_SAMPLES)
            var accumPos = 0
            var resamplePhase = 0.0

            while (decoding.get() && !outputEos) {
                if (!isPlaying) {
                    Thread.sleep(40)
                    continue
                }

                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val totalShorts = shortBuf.remaining()
                            val pcm = ShortArray(totalShorts)
                            shortBuf.get(pcm)

                            val srcFrames = totalShorts / srcChannels.coerceAtLeast(1)
                            val step = srcRate.toDouble() / RideComProtocol.SAMPLE_RATE.toDouble()

                            var pos = resamplePhase
                            while (pos < srcFrames && decoding.get()) {
                                val f0 = pos.toInt()
                                val f1 = (f0 + 1).coerceAtMost(srcFrames - 1)
                                val frac = (pos - f0).toFloat()

                                val s0 = readMonoSample(pcm, f0, srcChannels)
                                val s1 = readMonoSample(pcm, f1, srcChannels)
                                val sample = (s0 + (s1 - s0) * frac).toInt().toShort()

                                accum[accumPos++] = sample
                                if (accumPos >= RideComProtocol.FRAME_SAMPLES) {
                                    val frame = accum.copyOf()
                                    while (decoding.get()) {
                                        if (frameQueue.offer(frame, 50, TimeUnit.MILLISECONDS)) break
                                    }
                                    accumPos = 0
                                }
                                pos += step
                            }
                            resamplePhase = pos - srcFrames
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                    }
                }
            }
            true
        } catch (_: InterruptedException) {
            false
        } catch (_: Throwable) {
            false
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private fun readMonoSample(pcm: ShortArray, frameIdx: Int, channels: Int): Float {
        if (channels <= 1) return pcm[frameIdx].toFloat()
        val base = frameIdx * channels
        var sum = 0f
        for (c in 0 until channels) {
            sum += pcm[base + c].toFloat()
        }
        return sum / channels
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Shared Music"
    }

    companion object {
        fun mixInto(target: ShortArray, music: ShortArray) {
            for (i in target.indices) {
                val sum = target[i].toInt() + music[i].toInt()
                target[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }
}
