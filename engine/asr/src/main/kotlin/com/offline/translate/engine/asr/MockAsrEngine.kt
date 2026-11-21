package com.offline.translate.engine.asr

import com.offline.translate.core.model.ComputeBackend
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.engine.api.AsrEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic Mock ASR engine for Dev Mode & UI testing without multi-hundred-MB model weights.
 * Simulates 300 ms streaming partial updates with LocalAgreement-2 stabilization across technical domains,
 * and records contextual hotwords biasing from the active glossary.
 */
@Singleton
class MockAsrEngine @Inject constructor() : AsrEngine {

    override val engineId: String = "mock_streaming_asr"

    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(replay = 1, extraBufferCapacity = 32)
    override val events: Flow<SpeechRecognitionEvent> = _events.asSharedFlow()

    private val _activeHotwords = MutableStateFlow<Map<String, Float>>(emptyMap())
    val activeHotwords: StateFlow<Map<String, Float>> = _activeHotwords.asStateFlow()

    private val segmentIdGenerator = AtomicLong(1000L)
    private val agreementPolicy = LocalAgreementPolicy()

    private var activeLanguageCode: String = "ru"
    private var activeProfile: QualityProfile = QualityProfile.BALANCED
    private var currentSegmentId: Long = 1000L
    private var accumulatedFrames: Int = 0
    private var sampleCursor: Int = 0

    private val sampleUtterancesByLang: Map<String, List<String>> = mapOf(
        "ru" to listOf(
            "рабочее давление на клапане DN-50 составляет пять мегапаскалей при температуре сто двадцать градусов Цельсия",
            "проверьте задержку вызова функции parseTelemetryPacket в модуле контроллера при частоте двести мегагерц",
            "напряжение питания шины постоянного тока двадцать четыре вольта и ток короткого замыкания десять килоампер"
        ),
        "en" to listOf(
            "operating pressure on valve DN-50 is five megapascals at one hundred twenty degrees Celsius",
            "verify latency of function parseTelemetryPacket in the microcontroller module at two hundred megahertz",
            "direct current bus supply voltage is twenty four volts with short circuit current ten kiloamperes"
        ),
        "tr" to listOf(
            "DN-50 vanasındaki çalışma basıncı yüz yirmi santigrat derecede beş megapaskal seviyesindedir",
            "iki yüz megahertz frekansında mikrodenetleyici modülündeki parseTelemetryPacket fonksiyonunun gecikmesini kontrol edin",
            "doğru akım bara besleme gerilimi yirmi dört volt ve kısa devre akımı on kiloamperdir"
        )
    )

    override suspend fun loadModel(
        languageCode: String,
        modelDir: File?,
        profile: QualityProfile,
        backend: ComputeBackend,
        cpuThreads: Int
    ) {
        activeLanguageCode = languageCode
        activeProfile = profile
        _isLoaded.value = true
    }

    override fun configureHotwords(hotwordsWithBoost: Map<String, Float>) {
        _activeHotwords.value = hotwordsWithBoost.toMap()
    }

    override suspend fun startStream(languageCode: String) {
        activeLanguageCode = languageCode
        currentSegmentId = segmentIdGenerator.incrementAndGet()
        accumulatedFrames = 0
        agreementPolicy.reset()
    }

    override suspend fun feedPcm16(samples: ShortArray, sampleRateHz: Int) {
        accumulatedFrames++
        if (accumulatedFrames % 10 == 0) {
            val utterance = currentSampleUtterance()
            val words = utterance.split(" ")
            val step = (accumulatedFrames / 10).coerceAtMost(words.size)
            val prefixTranscript = words.take(step).joinToString(" ")
            val split = agreementPolicy.update(prefixTranscript, isFinal = false)
            _events.emit(
                SpeechRecognitionEvent(
                    segmentId = currentSegmentId,
                    languageCode = activeLanguageCode,
                    confirmedText = split.confirmedText,
                    unstableTailText = split.unstableTailText,
                    isFinal = false,
                    audioRmsDb = -18f,
                    processingLatencyMs = 85L
                )
            )
        }
    }

    suspend fun simulateStreamingUtterance(customText: String? = null, languageCode: String = activeLanguageCode) {
        startStream(languageCode)
        val utterance = customText ?: currentSampleUtterance()
        sampleCursor++
        val words = utterance.split(" ")
        val chunkSize = 2
        var count = chunkSize
        while (count < words.size) {
            val partial = words.take(count).joinToString(" ")
            agreementPolicy.update(words.take((count - 1).coerceAtLeast(1)).joinToString(" "), isFinal = false)
            val split = agreementPolicy.update(partial, isFinal = false)
            _events.emit(
                SpeechRecognitionEvent(
                    segmentId = currentSegmentId,
                    languageCode = languageCode,
                    confirmedText = split.confirmedText,
                    unstableTailText = split.unstableTailText,
                    isFinal = false,
                    audioRmsDb = -16f - (count % 5) * 2f,
                    processingLatencyMs = 95L
                )
            )
            delay(activeProfile.partialUpdateIntervalMs)
            count += chunkSize
        }
        val finalSplit = agreementPolicy.update(utterance, isFinal = true)
        _events.emit(
            SpeechRecognitionEvent(
                segmentId = currentSegmentId,
                languageCode = languageCode,
                confirmedText = finalSplit.confirmedText,
                unstableTailText = "",
                isFinal = true,
                audioRmsDb = -52f,
                processingLatencyMs = 110L
            )
        )
    }

    override suspend fun finishStream(): SpeechRecognitionEvent? {
        val utterance = currentSampleUtterance()
        sampleCursor++
        val split = agreementPolicy.update(utterance, isFinal = true)
        val finalEvent = SpeechRecognitionEvent(
            segmentId = currentSegmentId,
            languageCode = activeLanguageCode,
            confirmedText = split.confirmedText,
            unstableTailText = "",
            isFinal = true,
            audioRmsDb = -55f,
            processingLatencyMs = 110L
        )
        _events.emit(finalEvent)
        return finalEvent
    }

    override suspend fun unload() {
        agreementPolicy.reset()
        _isLoaded.value = false
    }

    private fun currentSampleUtterance(): String {
        val list = sampleUtterancesByLang[activeLanguageCode] ?: sampleUtterancesByLang.getValue("en")
        return list[sampleCursor % list.size]
    }
}
