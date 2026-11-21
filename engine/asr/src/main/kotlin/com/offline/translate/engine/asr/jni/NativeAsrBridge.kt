package com.offline.translate.engine.asr.jni

import com.offline.translate.core.common.SafeLogger

/**
 * JNI wrapper around `liboffline_asr_jni.so` (ARMv8.2-A NEON/DotProd int8 ASR runtime for whisper.cpp / sherpa-onnx).
 * Gracefully degrades on JVM host tests where Android native libraries are not loaded.
 */
object NativeAsrBridge {

    val isNativeLibraryLoaded: Boolean = runCatching {
        System.loadLibrary("offline_asr_jni")
        true
    }.onFailure {
        SafeLogger.d("NativeAsrBridge", "Native library offline_asr_jni not loaded (JVM/Host mode)")
    }.getOrDefault(false)

    external fun nativeInitContext(
        modelPath: String,
        languageCode: String,
        numThreads: Int,
        useInt8: Boolean
    ): Long

    external fun nativeFeedPcm16(
        contextHandle: Long,
        pcmSamples: ShortArray
    ): Int

    external fun nativeResetStream(contextHandle: Long)

    external fun nativeReleaseContext(contextHandle: Long)
}
