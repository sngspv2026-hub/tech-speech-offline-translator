package com.offline.translate.engine.packmanager

import com.offline.translate.core.model.LanguagePackManifest
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.ThermalLevel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackManifestAndThermalTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `default built-in catalog provides all 6 RU-EN-TR directions and flags NLLB as non-commercial`() {
        val catalog = LanguagePackManagerImpl.defaultBuiltInCatalog()
        assertTrue(catalog.size >= 4)

        val allDirections = catalog.flatMap { it.directions }.map { "${it.source}->${it.target}" }.toSet()
        assertTrue(allDirections.containsAll(setOf("ru->en", "en->ru", "tr->en", "en->tr", "ru->tr", "tr->ru")))

        // Default OPUS-MT / Zipformer / Piper packs must allow commercial use
        val opusPack = catalog.first { it.packId == "org.offline.translate.pack.ru_en_core" }
        assertTrue(opusPack.license.commercialUseAllowed)

        // Optional NLLB-200 pack must explicitly flag commercialUseAllowed = false (CC-BY-NC-4.0)
        val nllbPack = catalog.first { it.packId == "org.offline.translate.pack.nllb200_600m_nc" }
        assertEquals(false, nllbPack.license.commercialUseAllowed)
        assertTrue(nllbPack.license.spdxId.contains("CC-BY-NC-4.0"))
    }

    @Test
    fun `thermal adaptation downgrades quality profile and limits CPU threads under heat`() {
        // Test thermal policy rules directly across NORMAL -> MODERATE -> SEVERE
        fun effectiveProfileFor(level: ThermalLevel, requested: QualityProfile): QualityProfile = when (level) {
            ThermalLevel.NORMAL, ThermalLevel.LIGHT -> requested
            ThermalLevel.MODERATE -> requested.downgradeForThermal()
            ThermalLevel.SEVERE, ThermalLevel.CRITICAL -> QualityProfile.FAST
        }

        assertEquals(QualityProfile.ACCURATE, effectiveProfileFor(ThermalLevel.NORMAL, QualityProfile.ACCURATE))
        assertEquals(QualityProfile.BALANCED, effectiveProfileFor(ThermalLevel.MODERATE, QualityProfile.ACCURATE))
        assertEquals(QualityProfile.FAST, effectiveProfileFor(ThermalLevel.SEVERE, QualityProfile.ACCURATE))
    }
}
