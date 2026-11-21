package com.offline.translate.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned manifest schema for downloadable or sideloaded offline language packs.
 */
@Serializable
data class LanguagePackManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val packId: String,
    val version: String,
    val minAppVersionCode: Int = 1,
    val displayName: Map<String, String>,
    val languages: List<PackLanguageEntry>,
    val directions: List<PackDirectionEntry>,
    val components: PackComponents,
    val license: PackLicenseInfo,
    val hardwareRequirements: PackHardwareRequirements,
    val sha256: String,
    val signatureEd25519: String = "",
    val downloadUrl: String = ""
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class PackLanguageEntry(
    val code: String,
    val name: String,
    val script: String = "Latn",
    val bcp47Tag: String = code
)

@Serializable
data class PackDirectionEntry(
    val source: String,
    val target: String,
    @SerialName("routing")
    val routing: RoutingMode = RoutingMode.DIRECT,
    val mtModelDir: String? = null,
    val pivotLanguage: String? = null
)

@Serializable
data class PackComponents(
    val asr: PackEngineDescriptor? = null,
    val mt: PackEngineDescriptor? = null,
    val tts: PackEngineDescriptor? = null,
    val glossaries: List<String> = emptyList()
)

@Serializable
data class PackEngineDescriptor(
    val engineType: String,
    val path: String,
    val quantization: String = "int8",
    val estimatedRamMb: Int = 150
)

@Serializable
data class PackLicenseInfo(
    val spdxId: String,
    val commercialUseAllowed: Boolean,
    val attributionUrl: String = "",
    val warningNote: String = ""
)

@Serializable
data class PackHardwareRequirements(
    val minRamMb: Int = 4096,
    val recommendedRamMb: Int = 6144,
    val diskSizeBytes: Long
)

@Serializable
enum class PackInstallStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    INSTALLED,
    UPDATE_AVAILABLE,
    FAILED
}

@Serializable
data class LanguagePackState(
    val manifest: LanguagePackManifest,
    val status: PackInstallStatus,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val localInstallPath: String? = null,
    val errorMessage: String? = null
)

@Serializable
enum class ThermalLevel {
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

@Serializable
enum class ComputeBackend {
    CPU_ARM_NEON_INT8,
    CPU_XNNPACK,
    NNAPI_DELEGATE,
    GPU_DELEGATE
}

@Serializable
data class HardwareBenchmarkResult(
    val deviceModel: String,
    val socName: String,
    val totalRamMb: Int,
    val selectedAsrBackend: ComputeBackend,
    val selectedMtBackend: ComputeBackend,
    val optimalCpuThreads: Int,
    val cpuAsrRtf: Float,
    val cpuMtTokensPerSec: Float,
    val nnapiAvailableAndFaster: Boolean = false,
    val benchmarkTimestampMs: Long = System.currentTimeMillis()
)
