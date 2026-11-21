#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <mutex>
#include <cstdint>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace {

struct NativeAsrContext {
    std::string modelPath;
    std::string languageCode;
    int numThreads = 4;
    bool useInt8 = true;
    std::vector<float> pcmFloatWindow;
    std::mutex mutex;
};

// ARM NEON accelerated int16 -> normalized float32 [-1.0, 1.0] conversion for Whisper / Zipformer frontend
void convertPcm16ToFloatNeon(const int16_t* src, float* dst, size_t count) {
    const float scale = 1.0f / 32768.0f;
    size_t i = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    float32x4_t vScale = vdupq_n_f32(scale);
    for (; i + 8 <= count; i += 8) {
        int16x8_t vShort = vld1q_s16(src + i);
        int32x4_t vLow = vmovl_s16(vget_low_s16(vShort));
        int32x4_t vHigh = vmovl_s16(vget_high_s16(vShort));
        float32x4_t fLow = vmulq_f32(vcvtq_f32_s32(vLow), vScale);
        float32x4_t fHigh = vmulq_f32(vcvtq_f32_s32(vHigh), vScale);
        vst1q_f32(dst + i, fLow);
        vst1q_f32(dst + i + 4, fHigh);
    }
#endif
    for (; i < count; ++i) {
        dst[i] = static_cast<float>(src[i]) * scale;
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_offline_translate_engine_asr_jni_NativeAsrBridge_nativeInitContext(
    JNIEnv* env,
    jobject /* thiz */,
    jstring modelPath,
    jstring languageCode,
    jint numThreads,
    jboolean useInt8
) {
    const char* pathChars = env->GetStringUTFChars(modelPath, nullptr);
    const char* langChars = env->GetStringUTFChars(languageCode, nullptr);

    auto* ctx = new NativeAsrContext();
    ctx->modelPath = pathChars ? pathChars : "";
    ctx->languageCode = langChars ? langChars : "ru";
    ctx->numThreads = static_cast<int>(numThreads);
    ctx->useInt8 = static_cast<bool>(useInt8);
    ctx->pcmFloatWindow.reserve(16000 * 15); // Pre-allocate 15s sliding window

    if (pathChars) env->ReleaseStringUTFChars(modelPath, pathChars);
    if (langChars) env->ReleaseStringUTFChars(languageCode, langChars);

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_offline_translate_engine_asr_jni_NativeAsrBridge_nativeFeedPcm16(
    JNIEnv* env,
    jobject /* thiz */,
    jlong contextHandle,
    jshortArray pcmSamples
) {
    if (contextHandle == 0 || pcmSamples == nullptr) return 0;
    auto* ctx = reinterpret_cast<NativeAsrContext*>(contextHandle);
    std::lock_guard<std::mutex> lock(ctx->mutex);

    const jsize length = env->GetArrayLength(pcmSamples);
    if (length <= 0) return static_cast<jint>(ctx->pcmFloatWindow.size());

    jshort* rawPtr = env->GetShortArrayElements(pcmSamples, nullptr);
    if (rawPtr != nullptr) {
        const size_t oldSize = ctx->pcmFloatWindow.size();
        ctx->pcmFloatWindow.resize(oldSize + static_cast<size_t>(length));
        convertPcm16ToFloatNeon(
            reinterpret_cast<const int16_t*>(rawPtr),
            ctx->pcmFloatWindow.data() + oldSize,
            static_cast<size_t>(length)
        );
        env->ReleaseShortArrayElements(pcmSamples, rawPtr, JNI_ABORT);
    }
    return static_cast<jint>(ctx->pcmFloatWindow.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_offline_translate_engine_asr_jni_NativeAsrBridge_nativeResetStream(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong contextHandle
) {
    if (contextHandle == 0) return;
    auto* ctx = reinterpret_cast<NativeAsrContext*>(contextHandle);
    std::lock_guard<std::mutex> lock(ctx->mutex);
    ctx->pcmFloatWindow.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_offline_translate_engine_asr_jni_NativeAsrBridge_nativeReleaseContext(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong contextHandle
) {
    if (contextHandle == 0) return;
    auto* ctx = reinterpret_cast<NativeAsrContext*>(contextHandle);
    delete ctx;
}
