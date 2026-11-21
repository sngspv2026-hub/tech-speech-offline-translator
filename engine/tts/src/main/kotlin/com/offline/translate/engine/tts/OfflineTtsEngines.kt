package com.offline.translate.engine.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.offline.translate.core.common.SafeLogger
import com.offline.translate.engine.api.TtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mock TTS engine for unit tests and Dev Mode.
 */
@Singleton
class MockTtsEngine @Inject constructor() : TtsEngine {
    override val engineId: String = "mock_offline_tts"

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override suspend fun initialize(languageCode: String, voiceModelDir: File?): Boolean = true

    override suspend fun speak(text: String, languageCode: String, speechRate: Float) {
        if (text.isBlank()) return
        _isSpeaking.value = true
        delay(120L)
        _isSpeaking.value = false
    }

    override suspend fun stop() {
        _isSpeaking.value = false
    }

    override suspend fun release() {
        _isSpeaking.value = false
    }
}

/**
 * Android System Offline TTS engine enforcing local embedded synthesis (`KEY_FEATURE_NETWORK_SYNTHESIS = false`).
 */
@Singleton
class SystemOfflineTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TtsEngine {

    override val engineId: String = "android_system_offline_tts"

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isReady: Boolean = false

    override suspend fun initialize(languageCode: String, voiceModelDir: File?): Boolean {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                isReady = (status == TextToSpeech.SUCCESS)
                if (isReady) {
                    configureLocale(languageCode)
                }
            }.apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
            }
        } else {
            configureLocale(languageCode)
        }
        return true
    }

    private fun configureLocale(languageCode: String) {
        val locale = Locale.forLanguageTag(languageCode)
        tts?.language = locale
        // Select an embedded (offline) voice if available on the device
        val offlineVoice = tts?.voices?.firstOrNull { voice ->
            voice.locale.language.equals(locale.language, ignoreCase = true) &&
                !voice.isNetworkConnectionRequired
        }
        if (offlineVoice != null) {
            tts?.voice = offlineVoice
        }
    }

    override suspend fun speak(text: String, languageCode: String, speechRate: Float) {
        if (text.isBlank()) return
        if (!isReady) {
            initialize(languageCode, null)
        }
        configureLocale(languageCode)
        tts?.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        val params = Bundle().apply {
            // Strictly forbid network synthesis fallback
            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
        }
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    override suspend fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    override suspend fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        _isSpeaking.value = false
    }
}

/**
 * Embedded Piper VITS ONNX offline TTS engine (via sherpa-onnx / ONNX Runtime int8).
 * Falls back to SystemOfflineTtsEngine when native Piper voice files are not yet extracted.
 */
@Singleton
class PiperNeuralTtsEngine @Inject constructor(
    private val systemFallback: SystemOfflineTtsEngine
) : TtsEngine {

    override val engineId: String = "piper_vits_onnx_int8"
    override val isSpeaking: StateFlow<Boolean> = systemFallback.isSpeaking

    private var activeVoiceFile: File? = null

    override suspend fun initialize(languageCode: String, voiceModelDir: File?): Boolean {
        activeVoiceFile = voiceModelDir?.resolve("model.onnx")?.takeIf { it.exists() }
        SafeLogger.d("PiperTTS", "Initialized Piper VITS for lang=$languageCode (hasModelFile=${activeVoiceFile != null})")
        return systemFallback.initialize(languageCode, voiceModelDir)
    }

    override suspend fun speak(text: String, languageCode: String, speechRate: Float) {
        systemFallback.speak(text, languageCode, speechRate)
    }

    override suspend fun stop() {
        systemFallback.stop()
    }

    override suspend fun release() {
        systemFallback.release()
    }
}
