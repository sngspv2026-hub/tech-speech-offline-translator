package com.offline.translate.engine.api

import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.core.model.TranslationSegment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the near-real-time streaming pipeline:
 * ASR hypothesis (with domain Hotwords biasing) -> Number/Unit normalization (+ Imperial->SI inline conversion) ->
 * Glossary/ID shielding -> Incremental/Final NMT -> Optional TTS (with Acoustic Echo Ducking).
 *
 * Uses prefix translation caching so confirmed ASR prefixes are not re-translated on every 300 ms tick.
 */
@Singleton
class StreamingTranslationPipeline @Inject constructor(
    private val engineRegistry: EngineRegistry,
    private val numberUnitNormalizer: NumberUnitNormalizer,
    private val termAdapter: TermAdapter,
    private val thermalMonitor: ThermalMonitor
) {
    private val _segments = MutableSharedFlow<TranslationSegment>(replay = 1, extraBufferCapacity = 32)
    val segments: SharedFlow<TranslationSegment> = _segments.asSharedFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var lastSegmentId: Long = -1L
    private var cachedConfirmedSource: String = ""
    private var cachedConfirmedTranslation: String = ""
    private var lastHotwordsPairAndDomain: String = ""

    suspend fun prepareHotwordsForSession(pair: LanguagePair, domainId: String, profile: QualityProfile) {
        val key = "${pair.id}:$domainId"
        if (key == lastHotwordsPairAndDomain) return
        val bundle = engineRegistry.acquireActivePair(pair, thermalMonitor.effectiveProfile(profile))
        val hotwords = termAdapter.extractAsrHotwords(pair.source.code, domainId)
        if (hotwords.isNotEmpty()) {
            bundle.asrEngine.configureHotwords(hotwords)
        }
        lastHotwordsPairAndDomain = key
    }

    suspend fun processSpeechEvent(
        event: SpeechRecognitionEvent,
        pair: LanguagePair,
        domainId: String,
        requestedProfile: QualityProfile,
        ttsEnabled: Boolean
    ): TranslationSegment {
        val effectiveProfile = thermalMonitor.effectiveProfile(requestedProfile)
        val bundle = engineRegistry.acquireActivePair(pair, effectiveProfile)
        prepareHotwordsForSession(pair, domainId, effectiveProfile)

        if (event.segmentId != lastSegmentId) {
            lastSegmentId = event.segmentId
            cachedConfirmedSource = ""
            cachedConfirmedTranslation = ""
        }

        val rawFullText = event.fullText
        val normalizedFullText = numberUnitNormalizer.convertImperialToSiInline(
            numberUnitNormalizer.normalize(rawFullText, pair.source.code)
        )
        val normalizedConfirmed = numberUnitNormalizer.convertImperialToSiInline(
            numberUnitNormalizer.normalize(event.confirmedText, pair.source.code)
        )

        val mtStartMs = System.currentTimeMillis()
        val output: TranslationEngineOutput
        val confirmedTranslated: String
        val previewTailTranslated: String

        if (event.isFinal) {
            output = bundle.translationEngine.translate(
                text = normalizedFullText,
                pair = pair,
                domainId = domainId,
                profile = effectiveProfile,
                isPartialPreview = false
            )
            confirmedTranslated = output.translatedText
            previewTailTranslated = ""
        } else {
            // Incremental translation: reuse cached confirmed translation if unchanged
            if (normalizedConfirmed.isNotBlank() && normalizedConfirmed == cachedConfirmedSource) {
                confirmedTranslated = cachedConfirmedTranslation
            } else if (normalizedConfirmed.isNotBlank()) {
                val confirmedOut = bundle.translationEngine.translate(
                    text = normalizedConfirmed,
                    pair = pair,
                    domainId = domainId,
                    profile = QualityProfile.FAST,
                    isPartialPreview = true
                )
                cachedConfirmedSource = normalizedConfirmed
                cachedConfirmedTranslation = confirmedOut.translatedText
                confirmedTranslated = confirmedOut.translatedText
            } else {
                confirmedTranslated = ""
            }

            val normalizedTail = numberUnitNormalizer.convertImperialToSiInline(
                numberUnitNormalizer.normalize(event.unstableTailText, pair.source.code)
            )
            if (normalizedTail.isNotBlank()) {
                output = bundle.translationEngine.translate(
                    text = normalizedTail,
                    pair = pair,
                    domainId = domainId,
                    profile = QualityProfile.FAST,
                    isPartialPreview = true
                )
                previewTailTranslated = output.translatedText
            } else {
                output = TranslationEngineOutput(
                    translatedText = confirmedTranslated,
                    routingUsed = bundle.routingMode
                )
                previewTailTranslated = ""
            }
        }

        val mtLatency = (System.currentTimeMillis() - mtStartMs).coerceAtLeast(1L)
        val totalLatency = event.processingLatencyMs + mtLatency

        val segment = TranslationSegment(
            segmentId = event.segmentId,
            sourceLanguage = pair.source.code,
            targetLanguage = pair.target.code,
            domainId = domainId,
            rawSourceText = rawFullText,
            normalizedSourceText = normalizedFullText,
            translatedConfirmedText = confirmedTranslated,
            translatedPreviewTailText = previewTailTranslated,
            isFinal = event.isFinal,
            routingUsed = output.routingUsed,
            pivotText = output.pivotIntermediateText,
            appliedTerms = output.appliedTerms,
            preservedTokens = output.preservedTokens,
            asrLatencyMs = event.processingLatencyMs,
            mtLatencyMs = mtLatency,
            endToEndLatencyMs = totalLatency
        )

        _segments.emit(segment)

        if (event.isFinal && ttsEnabled && segment.fullTranslatedText.isNotBlank()) {
            bundle.ttsEngine.speak(segment.fullTranslatedText, pair.target.code)
        }

        return segment
    }

    fun setRecordingState(recording: Boolean) {
        _isRecording.value = recording
    }
}
