package com.offline.translate.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.engine.mt.DefaultNumberUnitNormalizer
import com.offline.translate.engine.packmanager.HardwareBenchmarkRunnerImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented performance & memory budget benchmark for target device:
 * Xiaomi Redmi Note Pro 5G (MediaTek Dimensity 1080/7200 or Snapdragon 7 series, 6-12 GB RAM).
 *
 * IMPORTANT NOTE:
 * Performance latency measurements on an Android Emulator (x86_64/QEMU) are NOT representative
 * of real ARMv8.2-A NEON/DotProd hardware and must be interpreted only as functional smoke checks.
 * Always run final latency benchmarks on a physical Redmi Note Pro 5G device.
 */
@RunWith(AndroidJUnit4::class)
class DevicePerformanceBenchmarkTest {

    private fun isRunningOnEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.lowercase().contains("emulator")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu"))
    }

    @Test
    fun benchmarkRuntimeBackendSelectionAndLatencyTargets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runner = HardwareBenchmarkRunnerImpl(context)
        val result = runner.runMicroBenchmark()

        if (isRunningOnEmulator()) {
            println("WARNING: Running on Android Emulator (${Build.MODEL}). Latency numbers are NOT representative of physical Redmi Note Pro 5G.")
        } else {
            println("BENCHMARK [Physical Device ${result.deviceModel} / ${result.socName}]: ASR RTF=${result.cpuAsrRtf}, MT tok/s=${result.cpuMtTokensPerSec}")
        }

        // Verify CPU thread pool is bounded to big cores (2..4 threads) and RTF is < 1.0
        assertTrue("Optimal CPU threads must be between 2 and 6", result.optimalCpuThreads in 2..6)
        assertTrue("ASR Real-Time Factor must be < 1.0", result.cpuAsrRtf < 1.0f)

        // Verify NumberUnitNormalizer latency is < 5 ms per technical utterance
        val normalizer = DefaultNumberUnitNormalizer()
        val startNs = System.nanoTime()
        val normalized = normalizer.normalize(
            "рабочее давление на клапане DN-50 составляет пять целых две десятых мегапаскаля при температуре сто двадцать градусов Цельсия",
            Language.RUSSIAN.code
        )
        val normMs = (System.nanoTime() - startNs) / 1_000_000.0
        assertTrue("Normalized output must contain 5.2 MPa", normalized.contains("5.2 MPa"))
        assertTrue("Normalization latency ($normMs ms) must be < 25 ms", normMs < 25.0)

        // Verify memory usage stays strictly below the 1,500 MB peak budget
        val runtime = Runtime.getRuntime()
        val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        assertTrue(
            "Heap usage ($usedHeapMb MB) must stay well within 1500 MB budget",
            usedHeapMb < 1500L
        )
    }
}
