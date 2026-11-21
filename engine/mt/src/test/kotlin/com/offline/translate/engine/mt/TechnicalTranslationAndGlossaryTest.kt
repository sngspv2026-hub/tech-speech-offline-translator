package com.offline.translate.engine.mt

import com.offline.translate.core.database.GlossaryDao
import com.offline.translate.core.database.GlossaryTermEntity
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.RoutingMode
import com.offline.translate.core.model.TechnicalDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TechnicalTranslationAndGlossaryTest {

    private lateinit var normalizer: DefaultNumberUnitNormalizer
    private lateinit var fakeDao: InMemoryGlossaryDao
    private lateinit var termAdapter: DefaultGlossaryTermAdapter
    private lateinit var directEngine: OfflineNeuralTranslationEngine
    private lateinit var pivotEngine: PivotTranslationEngine

    private class InMemoryGlossaryDao : GlossaryDao {
        private val items = mutableListOf<GlossaryTermEntity>()
        private val flow = MutableStateFlow<List<GlossaryTermEntity>>(emptyList())
        private var nextId = 1L

        override suspend fun getActiveTermsForPairAndDomain(
            sourceLang: String,
            targetLang: String,
            domainId: String
        ): List<GlossaryTermEntity> {
            return items.filter {
                it.sourceLang == sourceLang &&
                    it.targetLang == targetLang &&
                    (it.domainId == domainId || it.domainId == "general")
            }.sortedWith(compareByDescending<GlossaryTermEntity> { it.priority }.thenByDescending { it.sourceTerm.length })
        }

        override fun observeAllTerms(): Flow<List<GlossaryTermEntity>> = flow

        override suspend fun getTermsFiltered(domainId: String?, userOnly: Boolean): List<GlossaryTermEntity> {
            return items.filter {
                (domainId == null || it.domainId == domainId) && (!userOnly || it.isUserDefined)
            }
        }

        override suspend fun upsertAll(terms: List<GlossaryTermEntity>) {
            for (t in terms) upsert(t)
        }

        override suspend fun upsert(term: GlossaryTermEntity): Long {
            items.removeAll {
                it.sourceLang == term.sourceLang &&
                    it.targetLang == term.targetLang &&
                    it.domainId == term.domainId &&
                    it.sourceTerm.equals(term.sourceTerm, ignoreCase = true) &&
                    it.isUserDefined == term.isUserDefined
            }
            val assigned = if (term.id == 0L) term.copy(id = nextId++) else term
            items.add(assigned)
            flow.value = items.toList()
            return assigned.id
        }

        override suspend fun deleteById(id: Long) {
            items.removeAll { it.id == id }
            flow.value = items.toList()
        }

        override suspend fun count(): Int = items.size
    }

    @Before
    fun setUp() {
        normalizer = DefaultNumberUnitNormalizer()
        fakeDao = InMemoryGlossaryDao()
        termAdapter = DefaultGlossaryTermAdapter(fakeDao)
        directEngine = OfflineNeuralTranslationEngine(termAdapter).apply {
            allowNetworkFallback = false
        }
        pivotEngine = PivotTranslationEngine(directEngine, termAdapter)
    }

    @Test
    fun `NumberUnitNormalizer normalizes Russian, English, and Turkish spoken numbers and SI units`() {
        // Russian integer + unit
        assertEquals(
            "рабочее давление 5 MPa",
            normalizer.normalize("рабочее давление пять мегапаскалей", "ru")
        )
        // Russian decimal fraction + unit
        assertEquals(
            "давление 5.2 MPa на клапане DN-50",
            normalizer.normalize("давление пять целых две десятых мегапаскаля на клапане DN-50", "ru")
        )
        // Russian compound number + temperature
        assertEquals(
            "при температуре 120 °C и напряжении 24 V",
            normalizer.normalize("при температуре сто двадцать градусов Цельсия и напряжении двадцать четыре вольта", "ru")
        )
        // English spoken numbers + units
        assertEquals(
            "operating pressure is 5 MPa at 200 MHz",
            normalizer.normalize("operating pressure is five megapascals at two hundred megahertz", "en")
        )
        // Turkish spoken numbers + units
        assertEquals(
            "çalışma basıncı 5 MPa ve 24 V",
            normalizer.normalize("çalışma basıncı beş megapaskal ve yirmi dört volt", "tr")
        )
    }

    @Test
    fun `GlossaryTermAdapter preserves code identifiers, articles, and enforces domain terms across RU-EN-TR`() = runTest {
        val rawRu = "проверьте задержку вызова функции parseTelemetryPacket на клапане DN-50 при частоте двести мегагерц"
        val normalizedRu = normalizer.normalize(rawRu, "ru")
        assertEquals(
            "проверьте задержку вызова функции parseTelemetryPacket на клапане DN-50 при частоте 200 MHz",
            normalizedRu
        )

        val outputEn = directEngine.translate(
            text = normalizedRu,
            pair = LanguagePair(Language.RUSSIAN, Language.ENGLISH),
            domainId = TechnicalDomain.IT_PROGRAMMING.id,
            profile = QualityProfile.BALANCED
        )

        // Code identifier parseTelemetryPacket, article DN-50, and unit 200 MHz must be preserved verbatim
        assertTrue(outputEn.preservedTokens.contains("parseTelemetryPacket"))
        assertTrue(outputEn.preservedTokens.contains("DN-50"))
        assertTrue(outputEn.preservedTokens.contains("200 MHz"))
        assertTrue(outputEn.translatedText.contains("parseTelemetryPacket"))
        assertTrue(outputEn.translatedText.contains("DN-50"))
        assertTrue(outputEn.translatedText.contains("200 MHz"))
        // Domain term "задержка вызова" -> "call latency" must be enforced
        assertTrue(outputEn.translatedText.contains("call latency"))
    }

    @Test
    fun `User glossary overrides built-in glossary with higher priority`() = runTest {
        // Trigger built-in seeding first
        val initialOut = directEngine.translate(
            text = "клапан DN-50",
            pair = LanguagePair(Language.RUSSIAN, Language.ENGLISH),
            domainId = TechnicalDomain.MECHANICAL.id,
            profile = QualityProfile.BALANCED
        )
        assertTrue(initialOut.translatedText.contains("valve"))

        // Import user glossary overriding "клапан" -> "shutoff control valve" (Priority 100 > 50)
        val csv = """
            src_lang,tgt_lang,domain,src_term,tgt_term,case_sensitive,priority
            ru,en,mechanical,"клапан","shutoff control valve",false,100
        """.trimIndent()
        val importedCount = termAdapter.importCsv(csv, isUserDefined = true)
        assertEquals(1, importedCount)

        val overriddenOut = directEngine.translate(
            text = "клапан DN-50",
            pair = LanguagePair(Language.RUSSIAN, Language.ENGLISH),
            domainId = TechnicalDomain.MECHANICAL.id,
            profile = QualityProfile.BALANCED
        )
        assertTrue(overriddenOut.translatedText.contains("shutoff control valve"))
        assertTrue(overriddenOut.appliedTerms.any { it.isUserGlossary && it.targetTerm == "shutoff control valve" })
    }

    @Test
    fun `TBX ISO 30042 XML import and export round-trip works accurately`() = runTest {
        val sampleTbx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <martif type="TBX" xml:lang="en">
              <text><body>
                <termEntry id="te1">
                  <descrip type="subjectField">oil_gas_energy</descrip>
                  <langSet xml:lang="ru"><tig><term>превентор</term></tig></langSet>
                  <langSet xml:lang="en"><tig><term>blowout preventer</term></tig></langSet>
                  <langSet xml:lang="tr"><tig><term>patlama önleyici</term></tig></langSet>
                </termEntry>
              </body></text>
            </martif>
        """.trimIndent()

        val imported = termAdapter.importTbx(sampleTbx, isUserDefined = true)
        // 3 languages -> 6 directional pairs imported
        assertEquals(6, imported)

        val ruToTr = directEngine.translate(
            text = "превентор M12x1.75",
            pair = LanguagePair(Language.RUSSIAN, Language.TURKISH),
            domainId = TechnicalDomain.OIL_GAS_ENERGY.id,
            profile = QualityProfile.BALANCED
        )
        assertTrue(ruToTr.translatedText.contains("patlama önleyici"))
        assertTrue(ruToTr.translatedText.contains("M12x1.75"))

        val exportedTbx = termAdapter.exportTbx(domainId = TechnicalDomain.OIL_GAS_ENERGY.id, userOnly = true)
        assertTrue(exportedTbx.contains("blowout preventer"))
        assertTrue(exportedTbx.contains("patlama önleyici"))
    }

    @Test
    fun `PivotTranslationEngine translates RU to TR via EN and preserves technical terms`() = runTest {
        val normalized = normalizer.normalize("рабочее давление на клапане DN-50 составляет пять мегапаскалей", "ru")
        val pivotResult = pivotEngine.translate(
            text = normalized,
            pair = LanguagePair(Language.RUSSIAN, Language.TURKISH),
            domainId = TechnicalDomain.MECHANICAL.id,
            profile = QualityProfile.BALANCED
        )
        assertEquals(RoutingMode.PIVOT, pivotResult.routingUsed)
        assertTrue(pivotResult.translatedText.contains("çalışma basıncı"))
        assertTrue(pivotResult.translatedText.contains("vana"))
        assertTrue(pivotResult.translatedText.contains("DN-50"))
        assertTrue(pivotResult.translatedText.contains("5 MPa"))
    }

    @Test
    fun `Imperial to SI inline converter appends accurate metric equivalents in brackets`() {
        val raw = "давление 150 psi на трубе 2 in при температуре 212 °F"
        val converted = normalizer.convertImperialToSiInline(raw)
        assertTrue(converted.contains("150 psi [≈ 1.03 MPa]"))
        assertTrue(converted.contains("2 in [50.8 mm]"))
        assertTrue(converted.contains("212 °F [100 °C]"))
    }

    @Test
    fun `TechnicalOcrCleaner fixes industrial nameplate OCR noise`() {
        val cleaner = TechnicalOcrCleanerImpl()
        val noisyOcr = "рабочее дав-\nление на клапане DN-5O при 38OV 5OHz IP6B M12x1,75"
        val cleaned = cleaner.cleanOcrText(noisyOcr)
        assertEquals("рабочее давление на клапане DN-50 при 380 V 50 Hz IP68 M12x1.75", cleaned)
    }

    @Test
    fun `Compact QR ShareCode TPKGLOSS1 round-trip and ASR Hotwords extraction work`() = runTest {
        termAdapter.importCsv(
            """
            src_lang,tgt_lang,domain,src_term,tgt_term,case_sensitive,priority
            ru,en,oil_gas_energy,"шаровой кран","ball valve",false,100
            """.trimIndent(),
            isUserDefined = true
        )
        val shareCode = termAdapter.exportCompactShareCode(domainId = TechnicalDomain.OIL_GAS_ENERGY.id, userOnly = true)
        assertTrue(shareCode.startsWith("TPKGLOSS1:"))

        val count = termAdapter.importCompactShareCode(shareCode, isUserDefined = true)
        assertEquals(1, count)

        val hotwords = termAdapter.extractAsrHotwords("ru", TechnicalDomain.OIL_GAS_ENERGY.id)
        assertEquals(3.5f, hotwords["шаровой кран"])
        assertEquals(2.0f, hotwords["запорная арматура"])
    }
}
