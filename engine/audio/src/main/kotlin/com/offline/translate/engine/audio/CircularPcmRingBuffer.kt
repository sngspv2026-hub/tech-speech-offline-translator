package com.offline.translate.engine.audio

/**
 * Allocation-free circular buffer for 16 kHz PCM-16 mono audio samples.
 * Used to retain a 300 ms pre-roll window (4800 samples at 16 kHz) prior to VAD speech onset
 * so plosives and initial consonants are never truncated.
 */
class CircularPcmRingBuffer(
    val capacitySamples: Int = 4800 // 300 ms at 16,000 Hz
) {
    init {
        require(capacitySamples > 0) { "capacitySamples must be positive" }
    }

    private val buffer = ShortArray(capacitySamples)
    private var writePos = 0
    private var availableCount = 0

    @Synchronized
    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        if (length <= 0) return
        val effectiveLen = length.coerceAtMost(samples.size - offset)
        for (i in 0 until effectiveLen) {
            buffer[writePos] = samples[offset + i]
            writePos = (writePos + 1) % capacitySamples
        }
        availableCount = (availableCount + effectiveLen).coerceAtMost(capacitySamples)
    }

    @Synchronized
    fun snapshot(): ShortArray {
        val result = ShortArray(availableCount)
        if (availableCount == 0) return result
        val startPos = (writePos - availableCount + capacitySamples) % capacitySamples
        for (i in 0 until availableCount) {
            result[i] = buffer[(startPos + i) % capacitySamples]
        }
        return result
    }

    @Synchronized
    fun drain(): ShortArray {
        val out = snapshot()
        clear()
        return out
    }

    @Synchronized
    fun clear() {
        writePos = 0
        availableCount = 0
    }

    @Synchronized
    fun size(): Int = availableCount
}
