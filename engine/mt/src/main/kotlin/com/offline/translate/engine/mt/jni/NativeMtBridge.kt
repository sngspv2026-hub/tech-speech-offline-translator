package com.offline.translate.engine.mt.jni

import com.offline.translate.core.common.SafeLogger

/**
 * JNI bridge for CTranslate2 / ONNX Runtime int8 Marian (OPUS-MT) and NLLB models.
 */
object NativeMtBridge {
    val isLoaded: Boolean = runCatching {
        System.loadLibrary("offline_mt_jni")
        true
    }.onFailure {
        SafeLogger.d("NativeMtBridge", "Native library offline_mt_jni not loaded (JVM/Host mode)")
    }.getOrDefault(false)

    external fun nativeInitTranslator(
        modelDir: String,
        sourceLang: String,
        targetLang: String,
        beamSize: Int,
        numThreads: Int
    ): Long

    external fun nativeReleaseTranslator(handle: Long)
}
