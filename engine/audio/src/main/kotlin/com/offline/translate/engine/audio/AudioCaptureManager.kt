package com.offline.translate.engine.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.offline.translate.core.common.SafeLogger
import com.offline.translate.engine.api.VadEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class CapturedAudioFrame(
    val samples: ShortArray,
    val rmsDb: Float,
    val isSpeech: Boolean,
    val isSpeechSegmentEnd: Boolean
)

/**
 * Manages 16 kHz mono PCM16 microphone capture with:
 * - Bluetooth SCO / BLE headset routing where connected
 * - Hardware NoiseSuppressor (NS) and AutomaticGainControl (AGC) attachment
 * - Continuous VAD gating with a 300 ms pre-roll ring buffer so CPU is never wasted on silence.
 */
@Singleton
class AudioCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vadEngine: VadEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _frames = MutableSharedFlow<CapturedAudioFrame>(extraBufferCapacity = 64)
    val frames: SharedFlow<CapturedAudioFrame> = _frames.asSharedFlow()

    private val _isBluetoothHeadsetActive = MutableStateFlow(false)
    val isBluetoothHeadsetActive: StateFlow<Boolean> = _isBluetoothHeadsetActive.asStateFlow()

    private val preRollBuffer = CircularPcmRingBuffer(capacitySamples = PRE_ROLL_SAMPLES)
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun startCapture(): Boolean {
        if (captureJob?.isActive == true) return true

        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES_30MS * 2 * 4)

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufBytes
            )
        }.getOrNull() ?: return false

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        attachHardwareAudioEffects(record.audioSessionId)
        routeBluetoothHeadsetIfConnected(record)

        audioRecord = record
        vadEngine.reset()
        preRollBuffer.clear()

        captureJob = scope.launch {
            val frameBuffer = ShortArray(FRAME_SAMPLES_30MS)
            var wasSpeechActive = false
            try {
                record.startRecording()
                while (isActive) {
                    val readCount = record.read(frameBuffer, 0, frameBuffer.size)
                    if (readCount <= 0) continue

                    val slice = if (readCount == frameBuffer.size) {
                        frameBuffer.copyOf()
                    } else {
                        frameBuffer.copyOfRange(0, readCount)
                    }

                    val rmsDb = HybridVadEngine.computeRmsDb(slice)
                    val isSpeechNow = vadEngine.processPcm16Frame(slice, SAMPLE_RATE_HZ)

                    if (!wasSpeechActive && isSpeechNow) {
                        // Speech onset: prepend 300 ms pre-roll + current frame
                        val preRoll = preRollBuffer.drain()
                        val combined = ShortArray(preRoll.size + slice.size)
                        System.arraycopy(preRoll, 0, combined, 0, preRoll.size)
                        System.arraycopy(slice, 0, combined, preRoll.size, slice.size)
                        _frames.emit(
                            CapturedAudioFrame(
                                samples = combined,
                                rmsDb = rmsDb,
                                isSpeech = true,
                                isSpeechSegmentEnd = false
                            )
                        )
                    } else if (isSpeechNow) {
                        _frames.emit(
                            CapturedAudioFrame(
                                samples = slice,
                                rmsDb = rmsDb,
                                isSpeech = true,
                                isSpeechSegmentEnd = false
                            )
                        )
                    } else if (wasSpeechActive && !isSpeechNow) {
                        // Speech segment ended (hangover expired)
                        _frames.emit(
                            CapturedAudioFrame(
                                samples = slice,
                                rmsDb = rmsDb,
                                isSpeech = false,
                                isSpeechSegmentEnd = true
                            )
                        )
                        preRollBuffer.clear()
                    } else {
                        // Silence: store in pre-roll ring buffer without waking ASR
                        preRollBuffer.write(slice)
                        _frames.emit(
                            CapturedAudioFrame(
                                samples = ShortArray(0),
                                rmsDb = rmsDb,
                                isSpeech = false,
                                isSpeechSegmentEnd = false
                            )
                        )
                    }
                    wasSpeechActive = isSpeechNow
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                releaseAudioEffects()
            }
        }
        return true
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        audioRecord = null
        vadEngine.reset()
        preRollBuffer.clear()
        clearBluetoothRouting()
    }

    private fun attachHardwareAudioEffects(audioSessionId: Int) {
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                SafeLogger.i("AudioCapture", "Hardware NoiseSuppressor enabled")
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                }
                SafeLogger.i("AudioCapture", "Hardware AutomaticGainControl enabled")
            }
        }
    }

    private fun releaseAudioEffects() {
        runCatching { noiseSuppressor?.release() }
        runCatching { agc?.release() }
        noiseSuppressor = null
        agc = null
    }

    private fun routeBluetoothHeadsetIfConnected(record: AudioRecord) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val btDevice = inputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (btDevice != null) {
                record.preferredDevice = btDevice
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.setCommunicationDevice(btDevice)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                }
                _isBluetoothHeadsetActive.value = true
                SafeLogger.i("AudioCapture", "Routed audio capture to Bluetooth headset")
            } else {
                _isBluetoothHeadsetActive.value = false
            }
        }
    }

    private fun clearBluetoothRouting() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
            _isBluetoothHeadsetActive.value = false
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val FRAME_SAMPLES_30MS = 480 // 30 ms at 16,000 Hz
        const val PRE_ROLL_SAMPLES = 4800  // 300 ms pre-roll at 16,000 Hz
    }
}
