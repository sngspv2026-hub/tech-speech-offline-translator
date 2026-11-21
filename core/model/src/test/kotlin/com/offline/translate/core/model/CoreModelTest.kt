package com.offline.translate.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreModelTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `language pair swap reverses source and target`() {
        val pair = LanguagePair(Language.RUSSIAN, Language.TURKISH)
        assertEquals("ru-tr", pair.id)
        val swapped = pair.swapped()
        assertEquals("tr-ru", swapped.id)
        assertEquals(Language.TURKISH, swapped.source)
        assertEquals(Language.RUSSIAN, swapped.target)
    }

    @Test
    fun `quality profile thermal downgrade steps down from ACCURATE to BALANCED to FAST`() {
        assertEquals(QualityProfile.BALANCED, QualityProfile.ACCURATE.downgradeForThermal())
        assertEquals(QualityProfile.FAST, QualityProfile.BALANCED.downgradeForThermal())
        assertEquals(QualityProfile.FAST, QualityProfile.FAST.downgradeForThermal())
        assertFalse(QualityProfile.FAST.allowSmallWhisper)
        assertFalse(QualityProfile.BALANCED.allowSmallWhisper)
        assertTrue(QualityProfile.ACCURATE.allowSmallWhisper)
    }

    @Test
    fun `manifest serialization preserves direct and pivot directions`() {
        val manifest = LanguagePackManifest(
            schemaVersion = 1,
            packId = "test.pack.ru_tr",
            version = "1.0.0",
            displayName = mapOf("ru" to "Тест", "en" to "Test"),
            languages = listOf(
                PackLanguageEntry("ru", "Русский"),
                PackLanguageEntry("tr", "Türkçe")
            ),
            directions = listOf(
                PackDirectionEntry("ru", "tr", RoutingMode.PIVOT, pivotLanguage = "en"),
                PackDirectionEntry("tr", "ru", RoutingMode.DIRECT, mtModelDir = "mt/tr_ru")
            ),
            components = PackComponents(
                mt = PackEngineDescriptor("ctranslate2_opus_int8", "mt/")
            ),
            license = PackLicenseInfo("CC-BY-4.0", commercialUseAllowed = true),
            hardwareRequirements = PackHardwareRequirements(diskSizeBytes = 100_000_000L),
            sha256 = "abc12345"
        )

        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<LanguagePackManifest>(encoded)
        assertEquals(manifest.packId, decoded.packId)
        assertEquals(RoutingMode.PIVOT, decoded.directions[0].routing)
        assertEquals("en", decoded.directions[0].pivotLanguage)
        assertEquals(RoutingMode.DIRECT, decoded.directions[1].routing)
    }
}
