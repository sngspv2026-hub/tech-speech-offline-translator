package com.offline.translate.engine.api

import com.offline.translate.core.model.AppliedTermMatch
import com.offline.translate.core.model.ComputeBackend
import com.offline.translate.core.model.HardwareBenchmarkResult
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePackManifest
import com.offline.translate.core.model.LanguagePackState
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.RoutingMode
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.core.model.ThermalLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Voice Activity Detection (VAD) engine interface.
 */
interface VadEngine {
    val isSpeechActive: StateFlow<Boolean>
    fun processPcm16Frame(samples: ShortArray, sampleRateHz: Int = 16000): Boolean
    fun reset()
}

/**
 * Streaming Automatic Speech Recognition (ASR) engine contract.
 */
interface AsrEngine {
    val engineId: String
    val isLoaded: StateFlow<Boolean>
    val events: Flow<SpeechRecognitionEvent>

    suspend fun loadModel(
        languageCode: String,
        modelDir: File?,
        profile: QualityProfile,
        backend: ComputeBackend = ComputeBackend.CPU_ARM_NEON_INT8,
        cpuThreads: Int = profile.defaultCpuThreads
    )

    fun configureHotwords(hotwordsWithBoost: Map<String, Float>) {}

    suspend fun startStream(languageCode: String)
    suspend fun feedPcm16(samples: ShortArray, sampleRateHz: Int = 16000)
    suspend fun finishStream(): SpeechRecognitionEvent?
    suspend fun unload()
}

/**
 * Offline Spoken/Text Language Identifier (LID) for automatic language detection (RU / EN / TR).
 */
interface LanguageIdentifier {
    fun detectLanguageCode(
        utterance: String,
        candidateCodes: List<String> = listOf("ru", "en", "tr")
    ): String
}

/**
 * Cleans OCR noise from industrial equipment nameplates, PLC screens, and engineering drawings.
 */
interface TechnicalOcrCleaner {
    fun cleanOcrText(rawOcrText: String): String
}

/**
 * Neural Machine Translation (NMT) engine contract.
 */
interface TranslationEngine {
    val engineId: String
    val supportedRouting: RoutingMode

    suspend fun loadPair(
        pair: LanguagePair,
        modelDir: File?,
        profile: QualityProfile,
        backend: ComputeBackend = ComputeBackend.CPU_ARM_NEON_INT8,
        cpuThreads: Int = profile.defaultCpuThreads
    )

    suspend fun translate(
        text: String,
        pair: LanguagePair,
        domainId: String,
        profile: QualityProfile,
        isPartialPreview: Boolean = false
    ): TranslationEngineOutput

    suspend fun unload()
}

data class TranslationEngineOutput(
    val translatedText: String,
    val detectedSourceLanguage: String? = null,
    val routingUsed: RoutingMode = RoutingMode.DIRECT,
    val pivotIntermediateText: String? = null,
    val appliedTerms: List<AppliedTermMatch> = emptyList(),
    val preservedTokens: List<String> = emptyList(),
    val engineBackendName: String = "Hybrid NMT",
    val errorMessage: String? = null,
    val latencyMs: Long = 0L
)

/**
 * Text-To-Speech (TTS) engine contract.
 */
interface TtsEngine {
    val engineId: String
    val isSpeaking: StateFlow<Boolean>

    suspend fun initialize(languageCode: String, voiceModelDir: File? = null): Boolean
    suspend fun speak(text: String, languageCode: String, speechRate: Float = 1.0f)
    suspend fun stop()
    suspend fun release()
}

/**
 * Normalizes spoken numbers and technical measurement units before MT.
 */
interface NumberUnitNormalizer {
    fun normalize(text: String, languageCode: String): String
    fun convertImperialToSiInline(text: String): String = text
}

/**
 * Pre- and post-processor for technical domain glossaries (CSV/TBX/QR-Compact) and code/ID shielding.
 */
data class PreprocessedTermPayload(
    val preparedText: String,
    val placeholdersToTarget: Map<String, String>,
    val appliedTerms: List<AppliedTermMatch>,
    val preservedTokens: List<String>
)

interface TermAdapter {
    suspend fun preprocess(
        normalizedSourceText: String,
        sourceLang: String,
        targetLang: String,
        domainId: String
    ): PreprocessedTermPayload

    fun postprocess(
        rawTranslatedText: String,
        payload: PreprocessedTermPayload,
        targetLang: String
    ): String

    suspend fun extractAsrHotwords(
        sourceLang: String,
        domainId: String
    ): Map<String, Float> = emptyMap()

    suspend fun importCsv(csvContent: String, isUserDefined: Boolean = true): Int
    suspend fun exportCsv(domainId: String? = null, userOnly: Boolean = false): String
    suspend fun importTbx(tbxXmlContent: String, isUserDefined: Boolean = true): Int
    suspend fun exportTbx(domainId: String? = null, userOnly: Boolean = false): String

    suspend fun exportCompactShareCode(domainId: String? = null, userOnly: Boolean = true): String = ""
    suspend fun importCompactShareCode(shareCode: String, isUserDefined: Boolean = true): Int = 0
}

/**
 * Manages discovery, downloading, cryptographic verification, sideloading, and removal of language packs.
 */
interface LanguagePackManager {
    val packsState: StateFlow<List<LanguagePackState>>
    val availableLanguages: StateFlow<List<Language>>

    suspend fun refreshCatalog(catalogUrl: String? = null): Result<List<LanguagePackManifest>>
    suspend fun enqueueDownload(packId: String): Result<Unit>
    suspend fun cancelDownload(packId: String)
    suspend fun sideloadFromFile(archiveFile: File): Result<LanguagePackManifest>
    suspend fun uninstallPack(packId: String): Result<Unit>
    suspend fun getRoutingForPair(pair: LanguagePair): RoutingMode
    suspend fun getTotalDiskUsageBytes(): Long
}

/**
 * Monitors device temperature / thermal status and dynamically adjusts quality profile & CPU threads.
 */
interface ThermalMonitor {
    val thermalLevel: StateFlow<ThermalLevel>
    val batteryTemperatureCelsius: StateFlow<Float>
    fun effectiveProfile(requested: QualityProfile): QualityProfile
    fun effectiveCpuThreads(requested: QualityProfile): Int
}

/**
 * Runs a micro-benchmark on first start to select the fastest stable backend.
 */
interface HardwareBenchmarkRunner {
    suspend fun runMicroBenchmark(): HardwareBenchmarkResult
}

/**
 * Central registry that maps manifest engine descriptors to concrete engines and enforces the < 1.5 GB RAM budget.
 */
interface EngineRegistry {
    val activePair: StateFlow<LanguagePair?>
    val estimatedMemoryUsageMb: StateFlow<Int>
    val isDevMockMode: StateFlow<Boolean>

    fun setDevMockMode(enabled: Boolean)
    suspend fun acquireActivePair(pair: LanguagePair, profile: QualityProfile): ActiveEnginesBundle
    suspend fun releaseAll()
}

data class ActiveEnginesBundle(
    val pair: LanguagePair,
    val asrEngine: AsrEngine,
    val translationEngine: TranslationEngine,
    val ttsEngine: TtsEngine,
    val routingMode: RoutingMode
)
