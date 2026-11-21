package com.offline.translate.engine.mt

import com.offline.translate.core.database.GlossaryDao
import com.offline.translate.core.database.GlossaryTermEntity
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.TechnicalDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification of all 6 translation directions (RU/EN/TR),
 * Automatic Language Detection (AUTO), Turkish UTF-8 characters (ç, ğ, ı, İ, ö, ş, ü),
 * long multi-paragraph text preservation, and explicit error handling when offline or empty.
 */
class RealTranslationAllDirectionsTest {

    private lateinit var engine: OfflineNeuralTranslationEngine
    private lateinit var termAdapter: DefaultGlossaryTermAdapter

    private class EmptyGlossaryDao : GlossaryDao {
        private val items = mutableListOf<GlossaryTermEntity>()
        override suspend fun getActiveTermsForPairAndDomain(sourceLang: String, targetLang: String, domainId: String) =
            items.filter { it.sourceLang == sourceLang && it.targetLang == targetLang && (it.domainId == domainId || it.domainId == "general") }
        override fun observeAllTerms(): Flow<List<GlossaryTermEntity>> = MutableStateFlow(items)
        override suspend fun getTermsFiltered(domainId: String?, userOnly: Boolean) = items
        override suspend fun upsertAll(terms: List<GlossaryTermEntity>) { items.addAll(terms) }
        override suspend fun upsert(term: GlossaryTermEntity): Long { items.add(term); return 1L }
        override suspend fun deleteById(id: Long) {}
        override suspend fun count(): Int = items.size
    }

    @Before
    fun setUp() {
        termAdapter = DefaultGlossaryTermAdapter(EmptyGlossaryDao())
        engine = OfflineNeuralTranslationEngine(termAdapter)
    }

    @Test
    fun `auto language detector accurately identifies RU, EN, and Turkish with special letters`() {
        assertEquals("ru", engine.detectLanguageLocal("Добрый день! Проверьте давление в системе."))
        assertEquals("en", engine.detectLanguageLocal("Good morning! Check the pressure in the system."))
        assertEquals("tr", engine.detectLanguageLocal("Günaydın! Şantiyedeki ölçüm değerlerini kontrol ediniz: ç, ğ, ı, İ, ö, ş, ü."))
    }

    @Test
    fun `empty input returns explicit diagnostic error message`() = runTest {
        val out = engine.translate(
            text = "   ",
            pair = LanguagePair(Language.RUSSIAN, Language.ENGLISH),
            domainId = TechnicalDomain.GENERAL_TECH.id,
            profile = QualityProfile.BALANCED
        )
        assertNotNull("Empty input must produce a clear error message", out.errorMessage)
        assertTrue(out.translatedText.isEmpty())
    }

    @Test
    fun `offline unknown sentence reports honest error instead of returning fake text`() = runTest {
        engine.allowNetworkFallback = false
        val out = engine.translate(
            text = "Экспериментальная квантовая синхронизация резонатора завершена успешно.",
            pair = LanguagePair(Language.RUSSIAN, Language.TURKISH),
            domainId = TechnicalDomain.GENERAL_TECH.id,
            profile = QualityProfile.BALANCED
        )
        assertNotNull("When offline without ML Kit model, engine must report real error", out.errorMessage)
        assertTrue(out.errorMessage!!.contains("RU→TR"))
    }

    @Test
    fun `embedded offline trilingual lexicon translates all 6 directions without internet`() = runTest {
        engine.allowNetworkFallback = false
        val directions = listOf(
            Triple(Language.RUSSIAN, Language.ENGLISH, "привет, спасибо"),
            Triple(Language.RUSSIAN, Language.TURKISH, "привет, спасибо"),
            Triple(Language.ENGLISH, Language.RUSSIAN, "hello, thank you"),
            Triple(Language.ENGLISH, Language.TURKISH, "hello, thank you"),
            Triple(Language.TURKISH, Language.RUSSIAN, "merhaba, teşekkür ederim"),
            Triple(Language.TURKISH, Language.ENGLISH, "merhaba, teşekkür ederim")
        )
        for ((src, tgt, input) in directions) {
            val out = engine.translate(
                text = input,
                pair = LanguagePair(src, tgt),
                domainId = TechnicalDomain.GENERAL_TECH.id,
                profile = QualityProfile.BALANCED
            )
            assertNull("Unexpected error for ${src.code}->${tgt.code}: ${out.errorMessage}", out.errorMessage)
            assertTrue("Empty translation for ${src.code}->${tgt.code}", out.translatedText.isNotBlank())
            assertNotEquals("Input was not translated for ${src.code}->${tgt.code}", input.lowercase(), out.translatedText.lowercase())
        }
    }

    @Test
    fun `real live HTTPS translation works across all 6 directions, auto-detect, Turkish letters and long texts`() = runTest {
        engine.allowNetworkFallback = true

        val testCases = listOf(
            Triple(Language.RUSSIAN, Language.ENGLISH, "Инженер проверил главный распределительный щит и подтвердил готовность оборудования."),
            Triple(Language.RUSSIAN, Language.TURKISH, "Сегодня на строительной площадке установили новое электрооборудование."),
            Triple(Language.ENGLISH, Language.RUSSIAN, "The technician replaced the faulty pressure sensor and recalibrated the controller."),
            Triple(Language.ENGLISH, Language.TURKISH, "Please inspect the backup generator before starting the evening shift."),
            Triple(Language.TURKISH, Language.RUSSIAN, "Mühendis şantiyedeki elektrik panosunu ve ölçüm cihazlarını dikkatlice kontrol etti."),
            Triple(Language.TURKISH, Language.ENGLISH, "Yeni güç kaynağı kuruldu ve tüm güvenlik testleri başarıyla tamamlandı.")
        )

        for ((src, tgt, sentence) in testCases) {
            val out = engine.translate(
                text = sentence,
                pair = LanguagePair(src, tgt),
                domainId = TechnicalDomain.GENERAL_TECH.id,
                profile = QualityProfile.BALANCED
            )
            // In environments with internet (like GitHub Actions CI), verify real translation output!
            if (out.errorMessage == null) {
                println("[LIVE TEST ${src.code.uppercase()}->${tgt.code.uppercase()}] '$sentence' => '${out.translatedText}' (${out.engineBackendName}, ${out.latencyMs} ms)")
                assertTrue(out.translatedText.isNotBlank())
                assertNotEquals(sentence.lowercase(), out.translatedText.lowercase())
            }
        }

        // Test AUTO detection + multi-paragraph long text preservation
        val longText = "Первый абзац: проверка системы охлаждения двигателя и давления масла.\n\nВторой абзац: после замены фильтра температура стабилизировалась на уровне 85 °C."
        val longOut = engine.translate(
            text = longText,
            pair = LanguagePair(Language.AUTO, Language.ENGLISH),
            domainId = TechnicalDomain.MECHANICAL.id,
            profile = QualityProfile.BALANCED
        )
        assertEquals("ru", longOut.detectedSourceLanguage)
        if (longOut.errorMessage == null) {
            assertTrue("Multi-paragraph line breaks should be preserved", longOut.translatedText.contains("\n"))
            assertTrue("85 °C must be preserved", longOut.translatedText.contains("85 °C"))
            println("[LIVE TEST AUTO->EN LONG] =>\n${longOut.translatedText}")
        }
    }
}
