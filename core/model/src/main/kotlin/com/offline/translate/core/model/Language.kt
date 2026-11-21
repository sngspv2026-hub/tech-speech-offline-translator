package com.offline.translate.core.model

import kotlinx.serialization.Serializable

/**
 * Represents a language supported by the application or dynamically added via a Language Pack.
 * Adding a new language requires only installing a language pack with its ISO 639-1/639-3 code.
 */
@Serializable
data class Language(
    val code: String,
    val displayName: Map<String, String>,
    val nativeName: String,
    val script: String = "Latn",
    val bcp47Tag: String = code
) {
    fun localizedName(uiLocaleCode: String): String =
        displayName[uiLocaleCode] ?: displayName["en"] ?: nativeName

    companion object {
        val RUSSIAN = Language(
            code = "ru",
            displayName = mapOf("ru" to "Русский", "en" to "Russian", "tr" to "Rusça"),
            nativeName = "Русский",
            script = "Cyrl",
            bcp47Tag = "ru-RU"
        )
        val ENGLISH = Language(
            code = "en",
            displayName = mapOf("ru" to "Английский", "en" to "English", "tr" to "İngilizce"),
            nativeName = "English",
            script = "Latn",
            bcp47Tag = "en-US"
        )
        val TURKISH = Language(
            code = "tr",
            displayName = mapOf("ru" to "Турецкий", "en" to "Turkish", "tr" to "Türkçe"),
            nativeName = "Türkçe",
            script = "Latn",
            bcp47Tag = "tr-TR"
        )

        val DEFAULT_LANGUAGES = listOf(RUSSIAN, ENGLISH, TURKISH)

        fun byCodeOrCreate(code: String): Language {
            val normalized = code.trim().lowercase()
            return DEFAULT_LANGUAGES.find { it.code == normalized }
                ?: Language(
                    code = normalized,
                    displayName = mapOf("en" to normalized.uppercase()),
                    nativeName = normalized.uppercase(),
                    bcp47Tag = normalized
                )
        }
    }
}

@Serializable
data class LanguagePair(
    val source: Language,
    val target: Language
) {
    val id: String get() = "${source.code}-${target.code}"

    fun swapped(): LanguagePair = LanguagePair(source = target, target = source)
}

@Serializable
enum class RoutingMode {
    /** Direct single-hop NMT model (e.g., ru -> en, en -> tr, or direct ru -> tr). */
    DIRECT,
    /** Two-hop translation via an intermediate pivot language (e.g., ru -> en -> tr). */
    PIVOT
}
