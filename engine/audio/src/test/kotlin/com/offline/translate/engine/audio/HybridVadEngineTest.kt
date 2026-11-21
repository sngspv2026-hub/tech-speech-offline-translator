package com.offline.translate.engine.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HybridVadEngineTest {

    private fun generateSinePcm16(amplitude: Short, freqHz: Double = 300.0, count: Int = 480): ShortArray {
        return ShortArray(count) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / 16000.0)).toInt().toShort()
        }
    }

    @Test
    fun `VAD triggers after 2 voice frames and holds speech active during hangover`() {
        val vad = HybridVadEngine()
        val silenceFrame = ShortArray(480) { 0 }
        val speechFrame = generateSinePcm16(amplitude = 6000, freqHz = 350.0)

        // Initial silence -> speech inactive
        assertFalse(vad.processPcm16Frame(silenceFrame))

        // 1st speech frame -> onset requires 2 consecutive frames
        assertFalse(vad.processPcm16Frame(speechFrame))

        // 2nd speech frame -> speech becomes active
        assertTrue(vad.processPcm16Frame(speechFrame))

        // 14 frames of silence (< 15 hangover threshold) -> still active
        repeat(14) {
            assertTrue(vad.processPcm16Frame(silenceFrame))
        }

        // 15th frame of silence -> hangover expires, speech becomes inactive
        assertFalse(vad.processPcm16Frame(silenceFrame))
    }
}
