package com.offline.translate.engine.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticEchoDuckingTest {

    @Test
    fun `loudspeaker TTS gates microphone during synthesis and 150ms reverb window while Bluetooth keeps full duplex`() {
        val controller = AcousticEchoDuckingController()

        // Idle: microphone is not gated
        assertFalse(controller.shouldGateMicrophoneFrame(isHeadsetActive = false, nowMs = 1000L))

        // TTS starts speaking at t=1000ms
        controller.onTtsSpeakingChanged(isSpeaking = true, nowMs = 1000L)
        // On loudspeaker: gated
        assertTrue(controller.shouldGateMicrophoneFrame(isHeadsetActive = false, nowMs = 1200L))
        // On Bluetooth headset: full duplex stays open
        assertFalse(controller.shouldGateMicrophoneFrame(isHeadsetActive = true, nowMs = 1200L))

        // TTS finishes speaking at t=2000ms
        controller.onTtsSpeakingChanged(isSpeaking = false, nowMs = 2000L)
        // Within 150ms reverberation guard window (t=2100ms): still gated on loudspeaker
        assertTrue(controller.shouldGateMicrophoneFrame(isHeadsetActive = false, nowMs = 2100L))
        // After 150ms window (t=2200ms): microphone re-opens
        assertFalse(controller.shouldGateMicrophoneFrame(isHeadsetActive = false, nowMs = 2200L))
    }
}
