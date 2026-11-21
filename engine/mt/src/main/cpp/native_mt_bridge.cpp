#include <jni.h>
#include <string>
#include <mutex>

namespace {

struct NativeMtContext {
    std::string modelDir;
    std::string sourceLang;
    std::string targetLang;
    int beamSize = 2;
    int numThreads = 4;
    std::mutex mutex;
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_offline_translate_engine_mt_jni_NativeMtBridge_nativeInitTranslator(
    JNIEnv* env,
    jobject /* thiz */,
    jstring modelDir,
    jstring sourceLang,
    jstring targetLang,
    jint beamSize,
    jint numThreads
) {
    const char* dirChars = env->GetStringUTFChars(modelDir, nullptr);
    const char* srcChars = env->GetStringUTFChars(sourceLang, nullptr);
    const char* tgtChars = env->GetStringUTFChars(targetLang, nullptr);

    auto* ctx = new NativeMtContext();
    ctx->modelDir = dirChars ? dirChars : "";
    ctx->sourceLang = srcChars ? srcChars : "ru";
    ctx->targetLang = tgtChars ? tgtChars : "en";
    ctx->beamSize = static_cast<int>(beamSize);
    ctx->numThreads = static_cast<int>(numThreads);

    if (dirChars) env->ReleaseStringUTFChars(modelDir, dirChars);
    if (srcChars) env->ReleaseStringUTFChars(sourceLang, srcChars);
    if (tgtChars) env->ReleaseStringUTFChars(targetLang, tgtChars);

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_offline_translate_engine_mt_jni_NativeMtBridge_nativeReleaseTranslator(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0) return;
    auto* ctx = reinterpret_cast<NativeMtContext*>(handle);
    delete ctx;
}
