package com.offline.translate.engine.mt

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.offline.translate.core.common.SafeLogger
import com.offline.translate.core.model.ComputeBackend
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.RoutingMode
import com.offline.translate.engine.api.NumberUnitNormalizer
import com.offline.translate.engine.api.TechnicalOcrCleaner
import com.offline.translate.engine.api.TermAdapter
import com.offline.translate.engine.api.TranslationEngine
import com.offline.translate.engine.api.TranslationEngineOutput
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contract for optional multilingual NMT models.
 */
interface MultilingualMtEngine : TranslationEngine {
    val supportedLanguageCodes: Set<String>
    val isCommercialUseAllowed: Boolean
}

/**
 * Production 3-Tier Hybrid Neural Translation Engine supporting RU, EN, TR + Auto-Detection:
 * - Tier 1: Google ML Kit On-Device Neural Translation (`com.google.mlkit:translate`) for 100% offline NMT once models are cached.
 * - Tier 2: Multi-Endpoint HTTPS Neural Translation Client (`translate.googleapis.com` + `api.mymemory.translated.net` failover)
 *   with long-text sentence chunking, UTF-8 handling (Cyrillic + Turkish ç, ğ, ı, İ, ö, ş, ü), and LRU caching for immediate 0-second post-install use.
 * - Tier 3: Embedded Offline Trilingual Lexicon + Technical Glossary (`DefaultGlossaryTermAdapter`) with honest error diagnostics when offline without models.
 */
@Singleton
class OfflineNeuralTranslationEngine @Inject constructor(
    private val termAdapter: TermAdapter
) : TranslationEngine {

    override val engineId: String = "hybrid_mlkit_nmt_engine"
    override val supportedRouting: RoutingMode = RoutingMode.DIRECT

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Thread-safe LRU cache (up to 256 translated segments) to avoid redundant requests
    private val translationCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 256
        }
    )

    /**
     * When false (e.g., in pure offline unit tests), skips external network calls and uses only Tier 1/Tier 3.
     */
    @Volatile
    var allowNetworkFallback: Boolean = true

    override suspend fun loadPair(
        pair: LanguagePair,
        modelDir: File?,
        profile: QualityProfile,
        backend: ComputeBackend,
        cpuThreads: Int
    ) {
        // Trigger background pre-download of ML Kit On-Device models if running on Android device
        runCatching {
            val src = if (pair.source.isAuto) "ru" else pair.source.code
            triggerMlKitBackgroundDownload(src, pair.target.code)
        }
    }

    override suspend fun translate(
        text: String,
        pair: LanguagePair,
        domainId: String,
        profile: QualityProfile,
        isPartialPreview: Boolean
    ): TranslationEngineOutput = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val trimmedInput = text.trim()
        if (trimmedInput.isEmpty()) {
            return@withContext TranslationEngineOutput(
                translatedText = "",
                errorMessage = "Введите или произнесите текст для перевода."
            )
        }

        val detectedLang = if (pair.source.isAuto) {
            detectLanguageLocal(trimmedInput)
        } else {
            pair.source.code.lowercase()
        }

        val effectiveTargetLang = if (pair.source.isAuto && detectedLang == pair.target.code.lowercase()) {
            // If user set Auto -> RU and typed Russian text, automatically translate to English
            if (detectedLang == "ru") "en" else "ru"
        } else {
            pair.target.code.lowercase()
        }

        if (detectedLang == effectiveTargetLang) {
            return@withContext TranslationEngineOutput(
                translatedText = trimmedInput,
                detectedSourceLanguage = detectedLang,
                routingUsed = RoutingMode.DIRECT,
                engineBackendName = "Identity (${detectedLang.uppercase()})",
                latencyMs = 1L
            )
        }

        val preprocessed = termAdapter.preprocess(
            normalizedSourceText = trimmedInput,
            sourceLang = detectedLang,
            targetLang = effectiveTargetLang,
            domainId = domainId
        )

        val preparedText = preprocessed.preparedText
        // If the entire text was matched by glossary terms & non-translatable tokens, restore immediately!
        val remainingAlphabetic = preparedText
            .replace(Regex("""__(?:NT|TERM)_\d+__"""), "")
            .replace(Regex("""[^а-яА-ЯёЁa-zA-ZğüşıöçĞÜŞİÖÇ]"""), "")

        if (remainingAlphabetic.isEmpty()) {
            val restored = termAdapter.postprocess(preparedText, preprocessed, effectiveTargetLang)
            return@withContext TranslationEngineOutput(
                translatedText = restored,
                detectedSourceLanguage = detectedLang,
                routingUsed = RoutingMode.DIRECT,
                appliedTerms = preprocessed.appliedTerms,
                preservedTokens = preprocessed.preservedTokens,
                engineBackendName = "Offline Technical Glossary",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        }

        // Check in-memory LRU cache first
        val cacheKey = "$detectedLang->$effectiveTargetLang:$domainId:$preparedText"
        val cachedMiddle = translationCache[cacheKey]
        if (cachedMiddle != null) {
            val finalCached = termAdapter.postprocess(cachedMiddle, preprocessed, effectiveTargetLang)
            return@withContext TranslationEngineOutput(
                translatedText = finalCached,
                detectedSourceLanguage = detectedLang,
                routingUsed = RoutingMode.DIRECT,
                appliedTerms = preprocessed.appliedTerms,
                preservedTokens = preprocessed.preservedTokens,
                engineBackendName = "Cached NMT",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        }

        // Tier 1: Try Google ML Kit On-Device Offline NMT (if models are already downloaded on device)
        val mlKitResult = tryTranslateWithMlKitOnDevice(preparedText, detectedLang, effectiveTargetLang)
        if (mlKitResult != null) {
            translationCache[cacheKey] = mlKitResult
            val finalRestored = termAdapter.postprocess(mlKitResult, preprocessed, effectiveTargetLang)
            return@withContext TranslationEngineOutput(
                translatedText = finalRestored,
                detectedSourceLanguage = detectedLang,
                routingUsed = RoutingMode.DIRECT,
                appliedTerms = preprocessed.appliedTerms,
                preservedTokens = preprocessed.preservedTokens,
                engineBackendName = "ML Kit On-Device (Офлайн)",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        }

        // Tier 3 (Fast Offline Lexicon check before network if coverage is 100%, e.g. known technical/common phrases)
        val (offlineText, offlineCoverage) = EmbeddedOfflineLexicon.translateOffline(
            text = preparedText,
            sourceLang = detectedLang,
            targetLang = effectiveTargetLang
        )
        if (offlineCoverage >= 0.92f) {
            translationCache[cacheKey] = offlineText
            val finalRestored = termAdapter.postprocess(offlineText, preprocessed, effectiveTargetLang)
            return@withContext TranslationEngineOutput(
                translatedText = finalRestored,
                detectedSourceLanguage = detectedLang,
                routingUsed = RoutingMode.DIRECT,
                appliedTerms = preprocessed.appliedTerms,
                preservedTokens = preprocessed.preservedTokens,
                engineBackendName = "Embedded Offline Dictionary",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        }

        // Tier 2: Live Multi-Endpoint HTTPS NMT with paragraph & sentence chunking
        var networkErrorDetail: String? = null
        if (allowNetworkFallback) {
            val httpsResult = tryTranslateMultiParagraphHttps(preparedText, detectedLang, effectiveTargetLang)
            if (httpsResult.isSuccess) {
                val translatedMiddle = httpsResult.getOrThrow()
                translationCache[cacheKey] = translatedMiddle
                val finalRestored = termAdapter.postprocess(translatedMiddle, preprocessed, effectiveTargetLang)
                return@withContext TranslationEngineOutput(
                    translatedText = finalRestored,
                    detectedSourceLanguage = detectedLang,
                    routingUsed = RoutingMode.DIRECT,
                    appliedTerms = preprocessed.appliedTerms,
                    preservedTokens = preprocessed.preservedTokens,
                    engineBackendName = "Neural NMT (${detectedLang.uppercase()}→${effectiveTargetLang.uppercase()})",
                    latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                )
            } else {
                networkErrorDetail = httpsResult.exceptionOrNull()?.message
            }
        }

        // Fallback to partial offline translation + clear diagnostic error message
        val bestEffortOffline = termAdapter.postprocess(offlineText, preprocessed, effectiveTargetLang)
        val diagnosticError = if (offlineCoverage > 0.35f) {
            null
        } else {
            "Не удалось выполнить полный перевод (${detectedLang.uppercase()}→${effectiveTargetLang.uppercase()}): " +
                "нет подключения к сети (${networkErrorDetail ?: "offline"}), а офлайн-модель ML Kit ещё не загружена на телефон. " +
                "Подключитесь к интернету или скачайте офлайн-пакет в Настройках."
        }

        return@withContext TranslationEngineOutput(
            translatedText = if (diagnosticError == null) bestEffortOffline else "",
            detectedSourceLanguage = detectedLang,
            routingUsed = RoutingMode.DIRECT,
            appliedTerms = preprocessed.appliedTerms,
            preservedTokens = preprocessed.preservedTokens,
            engineBackendName = "Offline Fallback",
            errorMessage = diagnosticError,
            latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        )
    }

    override suspend fun unload() = Unit

    /**
     * Fast local language detection supporting Russian (Cyrillic), Turkish (ç, ğ, ı, İ, ö, ş, ü & morphology), and English.
     */
    fun detectLanguageLocal(text: String): String {
        val cyrillicCount = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        if (cyrillicCount > 0) return "ru"

        val turkishSpecialChars = setOf('ç', 'ğ', 'ı', 'İ', 'ö', 'ş', 'ü', 'Ç', 'Ğ', 'Ö', 'Ş', 'Ü')
        if (text.any { it in turkishSpecialChars }) return "tr"

        val lowerWords = text.lowercase().split(Regex("""\s+""")).map { it.trim(',', '.', '!', '?', ';', ':') }
        val turkishWords = setOf(
            "merhaba", "nasılsın", "evet", "hayır", "lütfen", "teşekkür", "ederim", "vana", "basınç",
            "ve", "ile", "için", "bir", "bu", "ne", "kadar", "nerede", "kontrol", "edin", "ayarlar",
            "beş", "on", "yirmi", "yüz", "megapaskal", "volt", "amper", "derece", "santigrat"
        )
        if (lowerWords.any { it in turkishWords || it.endsWith("dir") || it.endsWith("dır") || it.endsWith("daki") || it.endsWith("deki") }) {
            return "tr"
        }
        return "en"
    }

    private suspend fun tryTranslateWithMlKitOnDevice(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        return runCatching {
            val mlSrc = TranslateLanguage.fromLanguageTag(sourceLang) ?: return null
            val mlTgt = TranslateLanguage.fromLanguageTag(targetLang) ?: return null
            val modelManager = RemoteModelManager.getInstance()
            val srcDownloaded = modelManager.isModelDownloaded(TranslateRemoteModel.Builder(mlSrc).build()).await()
            val tgtDownloaded = modelManager.isModelDownloaded(TranslateRemoteModel.Builder(mlTgt).build()).await()

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlSrc)
                .setTargetLanguage(mlTgt)
                .build()
            val translator = Translation.getClient(options)

            if (!srcDownloaded || !tgtDownloaded) {
                // Start non-blocking background download so future calls work 100% offline
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                translator.close()
                return null
            }

            try {
                // Preserve paragraph line breaks
                val paragraphs = text.split("\n")
                val translatedParagraphs = paragraphs.map { p ->
                    if (p.isBlank()) "" else translator.translate(p).await()
                }
                translatedParagraphs.joinToString("\n")
            } finally {
                translator.close()
            }
        }.getOrNull()
    }

    private fun triggerMlKitBackgroundDownload(sourceLang: String, targetLang: String) {
        runCatching {
            val mlSrc = TranslateLanguage.fromLanguageTag(sourceLang) ?: return
            val mlTgt = TranslateLanguage.fromLanguageTag(targetLang) ?: return
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlSrc)
                .setTargetLanguage(mlTgt)
                .build()
            val translator = Translation.getClient(options)
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnCompleteListener { translator.close() }
        }
    }

    /**
     * Downloads ML Kit On-Device models explicitly and awaits completion (called from PackManager / Settings).
     */
    suspend fun downloadOfflineMlKitModels(sourceLang: String, targetLang: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mlSrc = TranslateLanguage.fromLanguageTag(sourceLang)
                    ?: error("Unsupported ML Kit language: $sourceLang")
                val mlTgt = TranslateLanguage.fromLanguageTag(targetLang)
                    ?: error("Unsupported ML Kit language: $targetLang")
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(mlSrc)
                    .setTargetLanguage(mlTgt)
                    .build()
                val translator = Translation.getClient(options)
                try {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    Unit
                } finally {
                    translator.close()
                }
            }
        }

    private fun tryTranslateMultiParagraphHttps(
        text: String,
        sourceLang: String,
        targetLang: String
    ): Result<String> {
        return runCatching {
            val paragraphs = text.split("\n")
            val outParagraphs = mutableListOf<String>()
            for (para in paragraphs) {
                if (para.isBlank()) {
                    outParagraphs.add("")
                    continue
                }
                val chunks = splitIntoSentenceChunks(para, maxChunkChars = 900)
                val translatedChunks = chunks.map { chunk ->
                    translateSingleChunkHttps(chunk, sourceLang, targetLang)
                }
                outParagraphs.add(translatedChunks.joinToString(" "))
            }
            outParagraphs.joinToString("\n")
        }
    }

    private fun splitIntoSentenceChunks(paragraph: String, maxChunkChars: Int): List<String> {
        if (paragraph.length <= maxChunkChars) return listOf(paragraph)
        val sentences = paragraph.split(Regex("""(?<=[.!?।;])\s+"""))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (s in sentences) {
            if (current.length + s.length + 1 > maxChunkChars && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(s)
        }
        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
        }
        return chunks
    }

    private fun translateSingleChunkHttps(
        chunk: String,
        sourceLang: String,
        targetLang: String
    ): String {
        val encoded = URLEncoder.encode(chunk, "UTF-8")

        // Endpoint 1: Google Translate GTX JSON endpoint
        val primaryError = runCatching {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .header("Accept-Charset", "UTF-8")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val bodyStr = resp.body?.string() ?: error("Empty response body")
                val rootArray = json.parseToJsonElement(bodyStr).jsonArray
                val sentencesArray = rootArray[0].jsonArray
                val sb = StringBuilder()
                for (item in sentencesArray) {
                    val part = item.jsonArray.getOrNull(0)?.jsonPrimitive?.content
                    if (!part.isNullOrEmpty()) {
                        sb.append(part)
                    }
                }
                val res = sb.toString().trim()
                if (res.isNotEmpty()) return res
            }
        }.exceptionOrNull()

        // Endpoint 2 (Failover): MyMemory REST Translation API
        val secondaryError = runCatching {
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$sourceLang|$targetLang"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "TechSpeechTranslator/1.2")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val bodyStr = resp.body?.string() ?: error("Empty response body")
                val obj = json.parseToJsonElement(bodyStr).jsonObject
                val translated = obj["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.content?.trim()
                if (!translated.isNullOrEmpty()) return translated
            }
        }.exceptionOrNull()

        SafeLogger.w("HybridNMT", "HTTPS translation endpoints failed: primary=${primaryError?.message}, secondary=${secondaryError?.message}")
        throw IllegalStateException(primaryError?.message ?: secondaryError?.message ?: "Сервер перевода недоступен")
    }
}

/**
 * Composite Pivot Translation Engine (e.g., ru -> en -> tr when manifest specifies routing: "pivot").
 */
@Singleton
class PivotTranslationEngine @Inject constructor(
    private val directEngine: OfflineNeuralTranslationEngine,
    private val termAdapter: TermAdapter
) : TranslationEngine {

    override val engineId: String = "pivot_hybrid_nmt"
    override val supportedRouting: RoutingMode = RoutingMode.PIVOT

    override suspend fun loadPair(
        pair: LanguagePair,
        modelDir: File?,
        profile: QualityProfile,
        backend: ComputeBackend,
        cpuThreads: Int
    ) {
        val pivot = Language.ENGLISH
        directEngine.loadPair(LanguagePair(pair.source, pivot), modelDir, profile, backend, cpuThreads)
        directEngine.loadPair(LanguagePair(pivot, pair.target), modelDir, profile, backend, cpuThreads)
    }

    override suspend fun translate(
        text: String,
        pair: LanguagePair,
        domainId: String,
        profile: QualityProfile,
        isPartialPreview: Boolean
    ): TranslationEngineOutput {
        val startMs = System.currentTimeMillis()
        if (text.isBlank()) {
            return TranslationEngineOutput(
                translatedText = "",
                routingUsed = RoutingMode.PIVOT,
                errorMessage = "Введите или произнесите текст для перевода."
            )
        }

        val resolvedSrcCode = if (pair.source.isAuto) {
            directEngine.detectLanguageLocal(text)
        } else {
            pair.source.code.lowercase()
        }

        // Pre-apply direct source->target glossary terms so technical terminology is preserved across pivot
        val directPayload = termAdapter.preprocess(
            normalizedSourceText = text,
            sourceLang = resolvedSrcCode,
            targetLang = pair.target.code,
            domainId = domainId
        )

        val pivotLang = Language.ENGLISH
        val hop1 = directEngine.translate(
            text = directPayload.preparedText,
            pair = LanguagePair(Language.byCodeOrCreate(resolvedSrcCode), pivotLang),
            domainId = domainId,
            profile = profile,
            isPartialPreview = isPartialPreview
        )
        if (hop1.errorMessage != null && hop1.translatedText.isBlank()) {
            return hop1.copy(routingUsed = RoutingMode.PIVOT)
        }

        val hop2 = directEngine.translate(
            text = hop1.translatedText,
            pair = LanguagePair(pivotLang, pair.target),
            domainId = domainId,
            profile = profile,
            isPartialPreview = isPartialPreview
        )

        val finalRestored = termAdapter.postprocess(
            rawTranslatedText = hop2.translatedText,
            payload = directPayload,
            targetLang = pair.target.code
        )

        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(2L)
        return TranslationEngineOutput(
            translatedText = finalRestored,
            detectedSourceLanguage = resolvedSrcCode,
            routingUsed = RoutingMode.PIVOT,
            pivotIntermediateText = hop1.translatedText,
            appliedTerms = (directPayload.appliedTerms + hop1.appliedTerms + hop2.appliedTerms).distinctBy { it.sourceTerm },
            preservedTokens = directPayload.preservedTokens,
            engineBackendName = hop2.engineBackendName,
            errorMessage = hop2.errorMessage,
            latencyMs = elapsed
        )
    }

    override suspend fun unload() {
        directEngine.unload()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MtEngineModule {
    @Binds
    @Singleton
    abstract fun bindNumberUnitNormalizer(impl: DefaultNumberUnitNormalizer): NumberUnitNormalizer

    @Binds
    @Singleton
    abstract fun bindTermAdapter(impl: DefaultGlossaryTermAdapter): TermAdapter

    @Binds
    @Singleton
    abstract fun bindTechnicalOcrCleaner(impl: TechnicalOcrCleanerImpl): TechnicalOcrCleaner
}
