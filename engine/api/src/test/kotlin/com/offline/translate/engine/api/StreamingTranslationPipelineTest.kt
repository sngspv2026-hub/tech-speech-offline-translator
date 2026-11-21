package com.offline.translate.engine.api

import com.offline.translate.core.model.AppliedTermMatch
import com.offline.translate.core.model.ComputeBackend
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.RoutingMode
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.core.model.TechnicalDomain
import com.offline.translate.core.model.ThermalLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamingTranslationPipelineTest {

    private var mtCallCount = 0

    private val fakeAsr = object : AsrEngine {
        override val engineId = "fake_asr"
        override val isLoaded = MutableStateFlow(true)
        override val events: Flow<SpeechRecognitionEvent> = emptyFlow()
        override suspend fun loadModel(languageCode: String, modelDir: File?, profile: QualityProfile, backend: ComputeBackend, cpuThreads: Int) {}
        override suspend fun startStream(languageCode: String) {}
        override suspend fun feedPcm16(samples: ShortArray, sampleRateHz: Int) {}
        override suspend fun finishStream(): SpeechRecognitionEvent? = null
        override suspend fun unload() {}
    }

    private val fakeMt = object : TranslationEngine {
        override val engineId = "fake_mt"
        override val supportedRouting = RoutingMode.DIRECT
        override suspend fun loadPair(pair: LanguagePair, modelDir: File?, profile: QualityProfile, backend: ComputeBackend, cpuThreads: Int) {}
        override suspend fun translate(
            text: String,
            pair: LanguagePair,
            domainId: String,
            profile: QualityProfile,
            isPartialPreview: Boolean
        ): TranslationEngineOutput {
            mtCallCount++
            return TranslationEngineOutput(
                translatedText = "EN[$text]",
                routingUsed = RoutingMode.DIRECT,
                appliedTerms = listOf(AppliedTermMatch("клапан", "valve", domainId, false)),
                preservedTokens = listOf("DN-50", "5 MPa"),
                latencyMs = 45L
            )
        }
        override suspend fun unload() {}
    }

    private val fakeTts = object : TtsEngine {
        override val engineId = "fake_tts"
        override val isSpeaking = MutableStateFlow(false)
        var lastSpoken: String = ""
        override suspend fun initialize(languageCode: String, voiceModelDir: File?) = true
        override suspend fun speak(text: String, languageCode: String, speechRate: Float) {
            lastSpoken = text
        }
        override suspend fun stop() {}
        override suspend fun release() {}
    }

    private val fakeRegistry = object : EngineRegistry {
        override val activePair: StateFlow<LanguagePair?> = MutableStateFlow(null)
        override val estimatedMemoryUsageMb: StateFlow<Int> = MutableStateFlow(520)
        override val isDevMockMode: StateFlow<Boolean> = MutableStateFlow(true)
        override fun setDevMockMode(enabled: Boolean) {}
        override suspend fun acquireActivePair(pair: LanguagePair, profile: QualityProfile): ActiveEnginesBundle {
            return ActiveEnginesBundle(pair, fakeAsr, fakeMt, fakeTts, RoutingMode.DIRECT)
        }
        override suspend fun releaseAll() {}
    }

    private val fakeNormalizer = object : NumberUnitNormalizer {
        override fun normalize(text: String, languageCode: String): String =
            text.replace("пять мегапаскалей", "5 MPa")
    }

    private val fakeTermAdapter = object : TermAdapter {
        override suspend fun preprocess(normalizedSourceText: String, sourceLang: String, targetLang: String, domainId: String) =
            PreprocessedTermPayload(normalizedSourceText, emptyMap(), emptyList(), emptyList())
        override fun postprocess(rawTranslatedText: String, payload: PreprocessedTermPayload, targetLang: String) = rawTranslatedText
        override suspend fun importCsv(csvContent: String, isUserDefined: Boolean) = 0
        override suspend fun exportCsv(domainId: String?, userOnly: Boolean) = ""
        override suspend fun importTbx(tbxXmlContent: String, isUserDefined: Boolean) = 0
        override suspend fun exportTbx(domainId: String?, userOnly: Boolean) = ""
    }

    private val fakeThermal = object : ThermalMonitor {
        override val thermalLevel = MutableStateFlow(ThermalLevel.NORMAL)
        override val batteryTemperatureCelsius = MutableStateFlow(32f)
        override fun effectiveProfile(requested: QualityProfile) = requested
        override fun effectiveCpuThreads(requested: QualityProfile) = 4
    }

    @Test
    fun `pipeline reuses cached confirmed prefix across partial ticks and triggers TTS on final`() = runTest {
        val pipeline = StreamingTranslationPipeline(
            engineRegistry = fakeRegistry,
            numberUnitNormalizer = fakeNormalizer,
            termAdapter = fakeTermAdapter,
            thermalMonitor = fakeThermal
        )
        val pair = LanguagePair(Language.RUSSIAN, Language.ENGLISH)

        // Tick 1: confirmed = "рабочее давление", tail = "пять"
        val partial1 = pipeline.processSpeechEvent(
            event = SpeechRecognitionEvent(1L, "ru", "рабочее давление", "пять", isFinal = false),
            pair = pair,
            domainId = TechnicalDomain.MECHANICAL.id,
            requestedProfile = QualityProfile.BALANCED,
            ttsEnabled = true
        )
        assertFalse(partial1.isFinal)
        assertEquals(2, mtCallCount) // 1 for confirmed, 1 for tail

        // Tick 2: confirmed is still "рабочее давление", tail = "пять мегапаскалей"
        val partial2 = pipeline.processSpeechEvent(
            event = SpeechRecognitionEvent(1L, "ru", "рабочее давление", "пять мегапаскалей", isFinal = false),
            pair = pair,
            domainId = TechnicalDomain.MECHANICAL.id,
            requestedProfile = QualityProfile.BALANCED,
            ttsEnabled = true
        )
        // Confirmed prefix was cached -> only tail was translated (+1 call instead of +2)
        assertEquals(3, mtCallCount)
        assertEquals("EN[5 MPa]", partial2.translatedPreviewTailText)

        // Final tick: full normalized sentence translated & spoken by TTS
        val finalSeg = pipeline.processSpeechEvent(
            event = SpeechRecognitionEvent(1L, "ru", "рабочее давление пять мегапаскалей", "", isFinal = true),
            pair = pair,
            domainId = TechnicalDomain.MECHANICAL.id,
            requestedProfile = QualityProfile.BALANCED,
            ttsEnabled = true
        )
        assertTrue(finalSeg.isFinal)
        assertEquals("рабочее давление 5 MPa", finalSeg.normalizedSourceText)
        assertEquals("EN[рабочее давление 5 MPa]", fakeTts.lastSpoken)
    }
}
