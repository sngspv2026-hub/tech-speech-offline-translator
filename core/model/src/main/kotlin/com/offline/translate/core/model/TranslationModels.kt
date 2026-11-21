package com.offline.translate.core.model

import kotlinx.serialization.Serializable

/**
 * Streaming ASR hypothesis with separation between confirmed stable prefix and unstable trailing words
 * (computed via LocalAgreement-2 policy).
 */
@Serializable
data class SpeechRecognitionEvent(
    val segmentId: Long,
    val languageCode: String,
    val confirmedText: String,
    val unstableTailText: String,
    val isFinal: Boolean,
    val audioRmsDb: Float = -60f,
    val processingLatencyMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val fullText: String
        get() = when {
            confirmedText.isBlank() -> unstableTailText.trim()
            unstableTailText.isBlank() -> confirmedText.trim()
            else -> "${confirmedText.trim()} ${unstableTailText.trim()}"
        }
}

@Serializable
data class AppliedTermMatch(
    val sourceTerm: String,
    val targetTerm: String,
    val domainId: String,
    val isUserGlossary: Boolean
)

/**
 * Incremental or final translation output with timing breakdown and glossary audit trail.
 */
@Serializable
data class TranslationSegment(
    val segmentId: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val domainId: String,
    val rawSourceText: String,
    val normalizedSourceText: String,
    val translatedConfirmedText: String,
    val translatedPreviewTailText: String = "",
    val isFinal: Boolean,
    val routingUsed: RoutingMode = RoutingMode.DIRECT,
    val pivotText: String? = null,
    val appliedTerms: List<AppliedTermMatch> = emptyList(),
    val preservedTokens: List<String> = emptyList(),
    val asrLatencyMs: Long = 0L,
    val mtLatencyMs: Long = 0L,
    val endToEndLatencyMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val fullTranslatedText: String
        get() = when {
            translatedConfirmedText.isBlank() -> translatedPreviewTailText.trim()
            translatedPreviewTailText.isBlank() -> translatedConfirmedText.trim()
            else -> "${translatedConfirmedText.trim()} ${translatedPreviewTailText.trim()}"
        }
}

@Serializable
data class GlossaryEntry(
    val id: Long = 0L,
    val sourceLang: String,
    val targetLang: String,
    val domainId: String,
    val sourceTerm: String,
    val targetTerm: String,
    val isUserDefined: Boolean = true,
    val caseSensitive: Boolean = false,
    val priority: Int = if (isUserDefined) 100 else 50,
    val note: String = ""
)
