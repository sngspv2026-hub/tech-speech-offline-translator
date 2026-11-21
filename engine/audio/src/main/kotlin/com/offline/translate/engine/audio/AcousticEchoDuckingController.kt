package com.offline.translate.engine.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prevents acoustic feedback loops when the phone sits on a table between interlocutors
 * with loudspeaker TTS enabled.
 *
 * - If a Bluetooth headset is connected (`isHeadsetActive == true`), full duplex stays ON.
 * - If using the built-in loudspeaker (`isHeadsetActive == false`), microphone frames are muted
 *   while TTS is speaking AND for a 150 ms reverberation guard window after TTS finishes.
 */
@Singleton
class AcousticEchoDuckingController @Inject constructor() {

    @Volatile
    private var ttsSpeaking: Boolean = false

    @Volatile
    private var ttsEndedTimestampMs: Long = 0L

    val reverbGuardWindowMs: Long = 150L

    fun onTtsSpeakingChanged(isSpeaking: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (ttsSpeaking && !isSpeaking) {
            ttsEndedTimestampMs = nowMs
        }
        ttsSpeaking = isSpeaking
    }

    fun shouldGateMicrophoneFrame(
        isHeadsetActive: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (isHeadsetActive) return false // Full duplex in headset
        if (ttsSpeaking) return true
        val elapsedSinceTtsEnd = nowMs - ttsEndedTimestampMs
        return ttsEndedTimestampMs > 0L && elapsedSinceTtsEnd in 0..reverbGuardWindowMs
    }
}
