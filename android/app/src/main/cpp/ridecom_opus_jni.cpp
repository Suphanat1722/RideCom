#include <jni.h>
#include <cstdint>
#include "opus.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ridecom_codec_OpusCodec_nativeCreateEncoder(
    JNIEnv*,
    jobject,
    jint sampleRate,
    jint channels,
    jint bitrateBps
) {
    int err = OPUS_OK;
    OpusEncoder* enc = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == nullptr) {
        return 0L;
    }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrateBps));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(15));
    opus_encoder_ctl(enc, OPUS_SET_DTX(0));
    return reinterpret_cast<jlong>(enc);
}

JNIEXPORT void JNICALL
Java_com_ridecom_codec_OpusCodec_nativeDestroyEncoder(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle != 0L) {
        opus_encoder_destroy(reinterpret_cast<OpusEncoder*>(handle));
    }
}

JNIEXPORT void JNICALL
Java_com_ridecom_codec_OpusCodec_nativeSetBitrate(
    JNIEnv*,
    jobject,
    jlong handle,
    jint bitrateBps
) {
    if (handle != 0L) {
        opus_encoder_ctl(reinterpret_cast<OpusEncoder*>(handle), OPUS_SET_BITRATE(bitrateBps));
    }
}

JNIEXPORT void JNICALL
Java_com_ridecom_codec_OpusCodec_nativeSetMusicMode(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean musicMode,
    jint voiceBitrateBps
) {
    if (handle != 0L) {
        auto* enc = reinterpret_cast<OpusEncoder*>(handle);
        if (musicMode) {
            opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_AUTO));
            opus_encoder_ctl(enc, OPUS_SET_BITRATE(40000));
        } else {
            opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
            opus_encoder_ctl(enc, OPUS_SET_BITRATE(voiceBitrateBps));
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_ridecom_codec_OpusCodec_nativeEncode(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray pcmArray,
    jint frameSamples,
    jbyteArray outArray
) {
    if (handle == 0L || pcmArray == nullptr || outArray == nullptr) return -1;

    jshort* pcm = env->GetShortArrayElements(pcmArray, nullptr);
    jbyte* out = env->GetByteArrayElements(outArray, nullptr);
    jsize maxBytes = env->GetArrayLength(outArray);

    int encoded = opus_encode(
        reinterpret_cast<OpusEncoder*>(handle),
        reinterpret_cast<const opus_int16*>(pcm),
        frameSamples,
        reinterpret_cast<unsigned char*>(out),
        maxBytes
    );

    env->ReleaseShortArrayElements(pcmArray, pcm, JNI_ABORT);
    env->ReleaseByteArrayElements(outArray, out, 0);
    return encoded;
}

JNIEXPORT jlong JNICALL
Java_com_ridecom_codec_OpusCodec_nativeCreateDecoder(
    JNIEnv*,
    jobject,
    jint sampleRate,
    jint channels
) {
    int err = OPUS_OK;
    OpusDecoder* dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == nullptr) {
        return 0L;
    }
    return reinterpret_cast<jlong>(dec);
}

JNIEXPORT void JNICALL
Java_com_ridecom_codec_OpusCodec_nativeDestroyDecoder(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle != 0L) {
        opus_decoder_destroy(reinterpret_cast<OpusDecoder*>(handle));
    }
}

JNIEXPORT jint JNICALL
Java_com_ridecom_codec_OpusCodec_nativeDecode(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray packetArray,
    jint packetLen,
    jshortArray outPcmArray,
    jint frameSamples,
    jboolean decodeFec
) {
    if (handle == 0L || outPcmArray == nullptr) return -1;

    jbyte* packet = (packetArray != nullptr && packetLen > 0)
        ? env->GetByteArrayElements(packetArray, nullptr)
        : nullptr;
    jshort* outPcm = env->GetShortArrayElements(outPcmArray, nullptr);

    int decoded = opus_decode(
        reinterpret_cast<OpusDecoder*>(handle),
        reinterpret_cast<const unsigned char*>(packet),
        packet != nullptr ? packetLen : 0,
        reinterpret_cast<opus_int16*>(outPcm),
        frameSamples,
        decodeFec ? 1 : 0
    );

    if (packet != nullptr) {
        env->ReleaseByteArrayElements(packetArray, packet, JNI_ABORT);
    }
    env->ReleaseShortArrayElements(outPcmArray, outPcm, 0);
    return decoded;
}

} // extern "C"
