package com.offline.translate.engine.asr

import com.offline.translate.engine.api.LanguageIdentifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fast on-device Language Identifier (LID) for Russian (ru), Turkish (tr), and English (en)
 * used in Conversation Mode to auto-detect which interlocutor is speaking without manual language switching.
 */
@Singleton
class OfflineLanguageIdentifier @Inject constructor() : LanguageIdentifier {

    private val cyrillicRegex = Regex("""[а-яА-ЯёЁ]""")
    private val turkishSpecificChars = setOf('ğ', 'ü', 'ş', 'ı', 'ö', 'ç', 'Ğ', 'Ü', 'Ş', 'İ', 'Ö', 'Ç')
    private val turkishCommonMorphemes = listOf(
        "lar", "ler", "deki", "daki", "dir", "dır", "tır", "tir", "ve", "ile", "için", "basıncı", "vana", "kontrol"
    )

    override fun detectLanguageCode(utterance: String, candidateCodes: List<String>): String {
        val text = utterance.trim()
        if (text.isEmpty()) return candidateCodes.firstOrNull() ?: "ru"

        if (cyrillicRegex.containsMatchIn(text) && "ru" in candidateCodes) {
            return "ru"
        }

        if ("tr" in candidateCodes) {
            if (text.any { it in turkishSpecificChars }) {
                return "tr"
            }
            val lower = text.lowercase()
            if (turkishCommonMorphemes.any { lower.contains(it) }) {
                return "tr"
            }
        }

        return if ("en" in candidateCodes) "en" else candidateCodes.first()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AsrModule {
    @Binds
    @Singleton
    abstract fun bindLanguageIdentifier(impl: OfflineLanguageIdentifier): LanguageIdentifier
}
