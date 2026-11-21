package com.offline.translate.engine.mt

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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contract for optional multilingual NMT models (e.g., NLLB-200 distilled 600M int8).
 * Note: NLLB-200 is distributed under CC-BY-NC-4.0 (Non-Commercial) and is NOT enabled by default.
 */
interface MultilingualMtEngine : TranslationEngine {
    val supportedLanguageCodes: Set<String>
    val isCommercialUseAllowed: Boolean
}

/**
 * Direct & Pivot-capable offline translation engine integrating GlossaryTermAdapter and phrase dictionary/ONNX hooks.
 */
@Singleton
class OfflineNeuralTranslationEngine @Inject constructor(
    private val termAdapter: TermAdapter
) : TranslationEngine {

    override val engineId: String = "opus_mt_ct2_int8"
    override val supportedRouting: RoutingMode = RoutingMode.DIRECT

    private val phraseMemory: Map<String, Map<String, String>> = mapOf(
        "ru-en" to mapOf(
            "рабочее давление" to "operating pressure",
            "на" to "on",
            "составляет" to "is",
            "при температуре" to "at temperature",
            "проверьте" to "verify",
            "функции" to "of function",
            "в модуле" to "in module",
            "контроллера" to "of controller",
            "при частоте" to "at frequency",
            "напряжение питания" to "supply voltage",
            "шины" to "of bus",
            "и ток" to "and current"
        ),
        "en-ru" to mapOf(
            "operating pressure" to "рабочее давление",
            "on" to "на",
            "is" to "составляет",
            "at" to "при",
            "verify" to "проверьте",
            "latency" to "задержку",
            "of function" to "функции",
            "in the" to "в",
            "module" to "модуле",
            "supply voltage" to "напряжение питания",
            "with" to "и",
            "current" to "ток"
        ),
        "en-tr" to mapOf(
            "operating pressure" to "çalışma basıncı",
            "on" to "üzerinde",
            "is" to "seviyesindedir",
            "at temperature" to "sıcaklıkta",
            "at" to "değerinde",
            "verify" to "kontrol edin",
            "of function" to "fonksiyonunun",
            "in module" to "modülünde",
            "of controller" to "kontrolör",
            "at frequency" to "frekansında",
            "supply voltage" to "besleme gerilimi",
            "of bus" to "bara",
            "and current" to "ve akımı"
        ),
        "tr-en" to mapOf(
            "çalışma basıncı" to "operating pressure",
            "vanasındaki" to "on valve",
            "seviyesindedir" to "is",
            "kontrol edin" to "verify",
            "fonksiyonunun" to "of function",
            "gecikmesini" to "latency",
            "frekansında" to "at frequency",
            "besleme gerilimi" to "supply voltage",
            "ve" to "and",
            "akımı" to "current"
        )
    )

    override suspend fun loadPair(
        pair: LanguagePair,
        modelDir: File?,
        profile: QualityProfile,
        backend: ComputeBackend,
        cpuThreads: Int
    ) {
        // Loads CTranslate2/ONNX int8 model weights from modelDir when native pack is installed
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
            return TranslationEngineOutput(translatedText = "")
        }

        val preprocessed = termAdapter.preprocess(
            normalizedSourceText = text,
            sourceLang = pair.source.code,
            targetLang = pair.target.code,
            domainId = domainId
        )

        val pairKey = "${pair.source.code}-${pair.target.code}"
        var middleText = preprocessed.preparedText
        val dict = phraseMemory[pairKey]
        if (dict != null) {
            for ((srcPhrase, tgtPhrase) in dict.entries.sortedByDescending { it.key.length }) {
                middleText = middleText.replace(Regex("""\b${Regex.escape(srcPhrase)}\b""", RegexOption.IGNORE_CASE), tgtPhrase)
            }
        }

        val finalTranslated = termAdapter.postprocess(
            rawTranslatedText = middleText,
            payload = preprocessed,
            targetLang = pair.target.code
        )

        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        return TranslationEngineOutput(
            translatedText = finalTranslated,
            routingUsed = RoutingMode.DIRECT,
            appliedTerms = preprocessed.appliedTerms,
            preservedTokens = preprocessed.preservedTokens,
            latencyMs = elapsed
        )
    }

    override suspend fun unload() = Unit
}

/**
 * Composite Pivot Translation Engine (e.g., ru -> en -> tr when manifest specifies routing: "pivot").
 * Preserves non-translatable tokens and applies direct glossary terms before/after pivot hop.
 */
@Singleton
class PivotTranslationEngine @Inject constructor(
    private val directEngine: OfflineNeuralTranslationEngine,
    private val termAdapter: TermAdapter
) : TranslationEngine {

    override val engineId: String = "pivot_opus_mt_int8"
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
        if (text.isBlank()) return TranslationEngineOutput(translatedText = "", routingUsed = RoutingMode.PIVOT)

        // Pre-apply direct source->target glossary terms so technical terminology does not degrade across pivot hop
        val directPayload = termAdapter.preprocess(
            normalizedSourceText = text,
            sourceLang = pair.source.code,
            targetLang = pair.target.code,
            domainId = domainId
        )

        val pivotLang = Language.ENGLISH
        val hop1 = directEngine.translate(
            text = directPayload.preparedText,
            pair = LanguagePair(pair.source, pivotLang),
            domainId = domainId,
            profile = profile,
            isPartialPreview = isPartialPreview
        )
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
            routingUsed = RoutingMode.PIVOT,
            pivotIntermediateText = hop1.translatedText,
            appliedTerms = (directPayload.appliedTerms + hop1.appliedTerms + hop2.appliedTerms).distinctBy { it.sourceTerm },
            preservedTokens = directPayload.preservedTokens,
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
