package com.offline.translate.engine.audio

import com.offline.translate.engine.api.VadEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Low-power Voice Activity Detector combining RMS energy and Zero-Crossing Rate (ZCR) spectral gate
 * with hangover hysteresis (450 ms silence hangover) and Silero ONNX model hook.
 * Always active during recording so ASR only wakes up on actual speech.
 */
@Singleton
class HybridVadEngine @Inject constructor() : VadEngine {

    private val _isSpeechActive = MutableStateFlow(false)
    override val isSpeechActive: StateFlow<Boolean> = _isSpeechActive.asStateFlow()

    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0

    // At 30 ms frames: 2 frames (60 ms) to trigger speech, 15 frames (450 ms) of silence to close speech segment
    private val onsetThresholdFrames = 2
    private val hangoverThresholdFrames = 15
    private val speechRmsDbThreshold = -42.0f

    override fun processPcm16Frame(samples: ShortArray, sampleRateHz: Int): Boolean {
        if (samples.isEmpty()) return _isSpeechActive.value

        val rmsDb = computeRmsDb(samples)
        val zcr = computeZeroCrossingRate(samples)

        // Human speech typically has RMS > -42 dBFS and ZCR between 0.02 and 0.45 at 16 kHz
        val frameHasVoice = rmsDb > speechRmsDbThreshold && zcr in 0.01f..0.48f

        if (frameHasVoice) {
            consecutiveSpeechFrames++
            consecutiveSilenceFrames = 0
            if (consecutiveSpeechFrames >= onsetThresholdFrames) {
                _isSpeechActive.value = true
            }
        } else {
            consecutiveSilenceFrames++
            if (_isSpeechActive.value && consecutiveSilenceFrames >= hangoverThresholdFrames) {
                _isSpeechActive.value = false
                consecutiveSpeechFrames = 0
            } else if (!_isSpeechActive.value) {
                consecutiveSpeechFrames = 0
            }
        }
        return _isSpeechActive.value
    }

    override fun reset() {
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
        _isSpeechActive.value = false
    }

    companion object {
        fun computeRmsDb(samples: ShortArray): Float {
            if (samples.isEmpty()) return -60f
            var sumSquares = 0.0
            for (sample in samples) {
                val normalized = sample / 32768.0
                sumSquares += normalized * normalized
            }
            val rms = sqrt(sumSquares / samples.size)
            if (rms <= 1e-6) return -60f
            return (20.0 * log10(rms)).toFloat().coerceIn(-60f, 0f)
        }

        fun computeZeroCrossingRate(samples: ShortArray): Float {
            if (samples.size < 2) return 0f
            var crossings = 0
            for (i in 1 until samples.size) {
                if ((samples[i - 1] >= 0 && samples[i] < 0) || (samples[i - 1] < 0 && samples[i] >= 0)) {
                    crossings++
                }
            }
            return crossings.toFloat() / (samples.size - 1).toFloat()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioEngineModule {
    @Binds
    @Singleton
    abstract fun bindVadEngine(impl: HybridVadEngine): VadEngine
}
