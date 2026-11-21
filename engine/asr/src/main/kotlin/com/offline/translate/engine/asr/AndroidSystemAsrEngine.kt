package com.offline.translate.engine.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.offline.translate.core.common.SafeLogger
import com.offline.translate.core.model.ComputeBackend
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.engine.api.AsrEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Android SpeechRecognizer engine supporting streaming partial results (`EXTRA_PARTIAL_RESULTS = true`),
 * Russian (`ru-RU`), English (`en-US`), Turkish (`tr-TR`), and LocalAgreement-2 stabilization.
 */
@Singleton
class AndroidSystemAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : AsrEngine {

    override val engineId: String = "android_system_speech_recognizer"

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isLoaded = MutableStateFlow(true)
    override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(replay = 1, extraBufferCapacity = 32)
    override val events: Flow<SpeechRecognitionEvent> = _events.asSharedFlow()

    private val segmentIdGenerator = AtomicLong(2000L)
    private val agreementPolicy = LocalAgreementPolicy()

    private var speechRecognizer: SpeechRecognizer? = null
    private var activeLanguageCode: String = "ru"
    private var currentSegmentId: Long = 2000L
    private var streamStartMs: Long = 0L
    private var lastPartialText: String = ""
    private var lastRmsDb: Float = -45f

    override suspend fun loadModel(
        languageCode: String,
        modelDir: File?,
        profile: QualityProfile,
        backend: ComputeBackend,
        cpuThreads: Int
    ) {
        activeLanguageCode = if (languageCode == Language.AUTO_CODE) "ru" else languageCode
        _isLoaded.value = true
    }

    override suspend fun startStream(languageCode: String) = withContext(Dispatchers.Main) {
        activeLanguageCode = if (languageCode == Language.AUTO_CODE) "ru" else languageCode
        currentSegmentId = segmentIdGenerator.incrementAndGet()
        streamStartMs = System.currentTimeMillis()
        lastPartialText = ""
        agreementPolicy.reset()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _events.tryEmit(
                SpeechRecognitionEvent(
                    segmentId = currentSegmentId,
                    languageCode = activeLanguageCode,
                    confirmedText = "",
                    unstableTailText = "",
                    isFinal = true,
                    errorMessage = "Служба распознавания речи (SpeechRecognizer) недоступна на этом устройстве. Проверьте, установлен ли Google Speech Services."
                )
            )
            return@withContext
        }

        destroyRecognizerOnMain()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        val bcp47 = when (activeLanguageCode.lowercase()) {
            "ru" -> "ru-RU"
            "tr" -> "tr-TR"
            "en" -> "en-US"
            else -> Language.byCodeOrCreate(activeLanguageCode).bcp47Tag
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                SafeLogger.d("AndroidSystemAsr", "Ready for speech ($bcp47)")
            }

            override fun onBeginningOfSpeech() {
                streamStartMs = System.currentTimeMillis()
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Convert Android SpeechRecognizer RMS (-2..10 dB) into -60..0 dBFS scale
                lastRmsDb = (rmsdB * 4f - 40f).coerceIn(-60f, 0f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                // If we already captured partial text before speech end/no_match, finalize with that partial text
                if (lastPartialText.isNotBlank() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    val split = agreementPolicy.update(lastPartialText, isFinal = true)
                    mainScope.launch {
                        _events.emit(
                            SpeechRecognitionEvent(
                                segmentId = currentSegmentId,
                                languageCode = activeLanguageCode,
                                confirmedText = split.confirmedText,
                                unstableTailText = "",
                                isFinal = true,
                                audioRmsDb = -60f,
                                processingLatencyMs = (System.currentTimeMillis() - streamStartMs).coerceAtLeast(1L)
                            )
                        )
                    }
                    return
                }

                val errMsg = mapSpeechError(error)
                mainScope.launch {
                    _events.emit(
                        SpeechRecognitionEvent(
                            segmentId = currentSegmentId,
                            languageCode = activeLanguageCode,
                            confirmedText = lastPartialText,
                            unstableTailText = "",
                            isFinal = true,
                            audioRmsDb = -60f,
                            errorMessage = errMsg
                        )
                    )
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestText = matches?.firstOrNull()?.trim().orEmpty().ifBlank { lastPartialText }
                val split = agreementPolicy.update(bestText, isFinal = true)
                val latency = (System.currentTimeMillis() - streamStartMs).coerceAtLeast(1L)
                mainScope.launch {
                    _events.emit(
                        SpeechRecognitionEvent(
                            segmentId = currentSegmentId,
                            languageCode = activeLanguageCode,
                            confirmedText = split.confirmedText,
                            unstableTailText = "",
                            isFinal = true,
                            audioRmsDb = -58f,
                            processingLatencyMs = latency
                        )
                    )
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim().orEmpty()
                if (partial.isNotBlank()) {
                    lastPartialText = partial
                    val split = agreementPolicy.update(partial, isFinal = false)
                    val latency = (System.currentTimeMillis() - streamStartMs).coerceAtLeast(1L)
                    mainScope.launch {
                        _events.emit(
                            SpeechRecognitionEvent(
                                segmentId = currentSegmentId,
                                languageCode = activeLanguageCode,
                                confirmedText = split.confirmedText,
                                unstableTailText = split.unstableTailText,
                                isFinal = false,
                                audioRmsDb = lastRmsDb,
                                processingLatencyMs = latency
                            )
                        )
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, bcp47)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        recognizer.startListening(intent)
    }

    override suspend fun feedPcm16(samples: ShortArray, sampleRateHz: Int) = Unit

    override suspend fun finishStream(): SpeechRecognitionEvent? = withContext(Dispatchers.Main) {
        runCatching { speechRecognizer?.stopListening() }
        if (lastPartialText.isNotBlank()) {
            val split = agreementPolicy.update(lastPartialText, isFinal = true)
            val ev = SpeechRecognitionEvent(
                segmentId = currentSegmentId,
                languageCode = activeLanguageCode,
                confirmedText = split.confirmedText,
                unstableTailText = "",
                isFinal = true,
                audioRmsDb = -60f,
                processingLatencyMs = 80L
            )
            _events.emit(ev)
            lastPartialText = ""
            ev
        } else {
            null
        }
    }

    override suspend fun unload() = withContext(Dispatchers.Main) {
        destroyRecognizerOnMain()
    }

    private fun destroyRecognizerOnMain() {
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun mapSpeechError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи аудио с микрофона."
        SpeechRecognizer.ERROR_CLIENT -> "Распознавание остановлено."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Отсутствует разрешение на запись звука (RECORD_AUDIO). Разрешите доступ к микрофону."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ошибка сети при распознавании речи. Проверьте интернет или скачайте офлайн-пакет речи в настройках Android."
        SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана. Говорите чётче или ближе к микрофону."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Служба распознавания речи занята. Подождите секунду и попробуйте снова."
        SpeechRecognizer.ERROR_SERVER -> "Сервер распознавания речи временно недоступен."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Звук речи не обнаружен (тайм-аут тишины)."
        else -> "Ошибка распознавания речи (код $error)."
    }
}
