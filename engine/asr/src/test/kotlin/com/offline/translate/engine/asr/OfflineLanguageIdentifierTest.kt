package com.offline.translate.engine.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineLanguageIdentifierTest {

    private val lid = OfflineLanguageIdentifier()

    @Test
    fun `detects Russian, Turkish, and English technical utterances accurately`() {
        assertEquals(
            "ru",
            lid.detectLanguageCode("рабочее давление на клапане DN-50 составляет 5 MPa")
        )
        assertEquals(
            "tr",
            lid.detectLanguageCode("DN-50 vanasındaki çalışma basıncı beş megapaskal")
        )
        assertEquals(
            "en",
            lid.detectLanguageCode("operating pressure on valve DN-50 is 150 psi")
        )
    }

    @Test
    fun `MockAsrEngine stores configured domain hotwords with boost scores`() {
        val engine = MockAsrEngine()
        val hotwords = mapOf("превентор" to 3.5f, "клапан" to 2.0f)
        engine.configureHotwords(hotwords)
        assertEquals(3.5f, engine.activeHotwords.value["превентор"])
        assertEquals(2.0f, engine.activeHotwords.value["клапан"])
    }
}
