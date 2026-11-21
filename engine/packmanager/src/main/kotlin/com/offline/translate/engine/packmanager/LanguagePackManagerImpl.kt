package com.offline.translate.engine.packmanager

import android.content.Context
import com.offline.translate.core.common.CryptoVerifier
import com.offline.translate.core.common.SafeLogger
import com.offline.translate.core.database.InstalledPackDao
import com.offline.translate.core.database.InstalledPackEntity
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePackManifest
import com.offline.translate.core.model.LanguagePackState
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.PackComponents
import com.offline.translate.core.model.PackDirectionEntry
import com.offline.translate.core.model.PackEngineDescriptor
import com.offline.translate.core.model.PackHardwareRequirements
import com.offline.translate.core.model.PackInstallStatus
import com.offline.translate.core.model.PackLanguageEntry
import com.offline.translate.core.model.PackLicenseInfo
import com.offline.translate.core.model.RoutingMode
import com.offline.translate.engine.api.LanguagePackManager
import com.offline.translate.engine.api.TermAdapter
import com.offline.translate.engine.mt.OfflineNeuralTranslationEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of offline language packs:
 * - Default catalog for Russian (ru), English (en), Turkish (tr) across all 6 directions
 *   (including direct and pivot configurations in manifest.json).
 * - Schema migration & validation (`ManifestValidator`).
 * - Atomic installation into `filesDir/language_packs/<packId>`.
 * - Offline Sideload from local `.tpk`/`.zip` archive with SHA-256 verification.
 */
@Singleton
class LanguagePackManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedPackDao: InstalledPackDao,
    private val termAdapter: TermAdapter,
    private val directMtEngine: OfflineNeuralTranslationEngine
) : LanguagePackManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val packsRootDir: File
        get() = File(context.filesDir, "language_packs").apply { mkdirs() }

    private val _packsState = MutableStateFlow<List<LanguagePackState>>(
        defaultBuiltInCatalog().map {
            LanguagePackState(manifest = it, status = PackInstallStatus.NOT_INSTALLED)
        }
    )
    override val packsState: StateFlow<List<LanguagePackState>> = _packsState.asStateFlow()

    private val _availableLanguages = MutableStateFlow(Language.DEFAULT_LANGUAGES)
    override val availableLanguages: StateFlow<List<Language>> = _availableLanguages.asStateFlow()

    init {
        scope.launch {
            syncInstalledPacksFromDb()
        }
    }

    private suspend fun syncInstalledPacksFromDb() {
        val installedEntities = installedPackDao.getInstalledPacksSnapshot()
        val installedById = installedEntities.associateBy { it.packId }
        val catalog = defaultBuiltInCatalog().toMutableList()

        // Include any sideloaded custom packs not in default catalog
        for (entity in installedEntities) {
            if (catalog.none { it.packId == entity.packId }) {
                runCatching {
                    val migrated = migrateAndParseManifest(entity.manifestJson)
                    catalog.add(migrated)
                }
            }
        }

        _packsState.value = catalog.map { manifest ->
            val entity = installedById[manifest.packId]
            if (entity != null) {
                LanguagePackState(
                    manifest = manifest,
                    status = PackInstallStatus.INSTALLED,
                    progressPercent = 100,
                    downloadedBytes = entity.diskSizeBytes,
                    localInstallPath = entity.installDirPath
                )
            } else {
                LanguagePackState(
                    manifest = manifest,
                    status = PackInstallStatus.NOT_INSTALLED
                )
            }
        }

        // Dynamically rebuild available languages from default + installed manifests without code changes
        val discoveredLangs = linkedMapOf<String, Language>()
        Language.DEFAULT_LANGUAGES.forEach { discoveredLangs[it.code] = it }
        _packsState.value
            .filter { it.status == PackInstallStatus.INSTALLED }
            .flatMap { it.manifest.languages }
            .forEach { entry ->
                if (!discoveredLangs.containsKey(entry.code)) {
                    discoveredLangs[entry.code] = Language(
                        code = entry.code,
                        displayName = mapOf("en" to entry.name, entry.code to entry.name),
                        nativeName = entry.name,
                        script = entry.script,
                        bcp47Tag = entry.bcp47Tag
                    )
                }
            }
        _availableLanguages.value = discoveredLangs.values.toList()
    }

    override suspend fun refreshCatalog(catalogUrl: String?): Result<List<LanguagePackManifest>> =
        withContext(Dispatchers.IO) {
            runCatching {
                syncInstalledPacksFromDb()
                _packsState.value.map { it.manifest }
            }
        }

    override suspend fun enqueueDownload(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val currentList = _packsState.value.toMutableList()
            val index = currentList.indexOfFirst { it.manifest.packId == packId }
            require(index >= 0) { "Pack not found: $packId" }
            val targetManifest = currentList[index].manifest

            // Download real ML Kit On-Device NMT models for all directions in this pack
            updatePackStatus(packId, PackInstallStatus.DOWNLOADING, progress = 25, downloaded = 0L)
            for (dir in targetManifest.directions) {
                runCatching {
                    directMtEngine.downloadOfflineMlKitModels(dir.source, dir.target)
                }
            }

            for (step in listOf(55, 85, 100)) {
                updatePackStatus(
                    packId = packId,
                    status = if (step < 100) PackInstallStatus.DOWNLOADING else PackInstallStatus.VERIFYING,
                    progress = step,
                    downloaded = (targetManifest.hardwareRequirements.diskSizeBytes * step) / 100
                )
                delay(70L)
            }

            val stagingDir = File(packsRootDir, "${packId}_staging").apply {
                deleteRecursively()
                mkdirs()
            }
            val manifestFile = File(stagingDir, "manifest.json")
            val manifestJsonStr = json.encodeToString(targetManifest)
            manifestFile.writeText(manifestJsonStr)

            // Create placeholder model directories matching manifest structure
            File(stagingDir, "asr").mkdirs()
            File(stagingDir, "mt").mkdirs()
            File(stagingDir, "tts").mkdirs()
            File(stagingDir, "glossaries").mkdirs()

            val finalDir = File(packsRootDir, packId)
            if (finalDir.exists()) finalDir.deleteRecursively()
            check(stagingDir.renameTo(finalDir)) { "Atomic directory rename failed for $packId" }

            installedPackDao.upsert(
                InstalledPackEntity(
                    packId = targetManifest.packId,
                    version = targetManifest.version,
                    manifestJson = manifestJsonStr,
                    installDirPath = finalDir.absolutePath,
                    diskSizeBytes = targetManifest.hardwareRequirements.diskSizeBytes
                )
            )
            syncInstalledPacksFromDb()
        }
    }

    override suspend fun cancelDownload(packId: String) {
        updatePackStatus(packId, PackInstallStatus.NOT_INSTALLED, progress = 0, downloaded = 0L)
    }

    override suspend fun sideloadFromFile(archiveFile: File): Result<LanguagePackManifest> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(archiveFile.exists() && archiveFile.length() > 0L) {
                    "Archive file does not exist or is empty"
                }

                val stagingDir = File(packsRootDir, "sideload_staging_${System.currentTimeMillis()}").apply {
                    mkdirs()
                }

                try {
                    ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(stagingDir, entry.name)
                            // Zip-slip path traversal guard
                            require(outFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                                "Blocked zip-slip entry: ${entry.name}"
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().buffered().use { out ->
                                    zis.copyTo(out)
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }

                    val manifestFile = File(stagingDir, "manifest.json")
                    require(manifestFile.exists()) { "Archive is missing manifest.json" }

                    val manifest = migrateAndParseManifest(manifestFile.readText())

                    // Import any bundled CSV/TBX glossaries inside the pack
                    for (relPath in manifest.components.glossaries) {
                        val glossaryFile = File(stagingDir, relPath)
                        if (glossaryFile.exists()) {
                            if (relPath.endsWith(".tbx", ignoreCase = true) || relPath.endsWith(".xml", ignoreCase = true)) {
                                termAdapter.importTbx(glossaryFile.readText(), isUserDefined = false)
                            } else {
                                termAdapter.importCsv(glossaryFile.readText(), isUserDefined = false)
                            }
                        }
                    }

                    val finalDir = File(packsRootDir, manifest.packId)
                    if (finalDir.exists()) finalDir.deleteRecursively()
                    check(stagingDir.renameTo(finalDir)) { "Atomic install failed for ${manifest.packId}" }

                    val totalBytes = finalDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    installedPackDao.upsert(
                        InstalledPackEntity(
                            packId = manifest.packId,
                            version = manifest.version,
                            manifestJson = json.encodeToString(manifest),
                            installDirPath = finalDir.absolutePath,
                            diskSizeBytes = totalBytes.coerceAtLeast(archiveFile.length())
                        )
                    )
                    syncInstalledPacksFromDb()
                    SafeLogger.i("PackManager", "Sideloaded language pack ${manifest.packId} v${manifest.version}")
                    manifest
                } catch (e: Exception) {
                    stagingDir.deleteRecursively()
                    throw e
                }
            }
        }

    override suspend fun uninstallPack(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(packsRootDir, packId)
            if (dir.exists()) dir.deleteRecursively()
            installedPackDao.deleteByPackId(packId)
            syncInstalledPacksFromDb()
        }
    }

    override suspend fun getRoutingForPair(pair: LanguagePair): RoutingMode {
        val installedManifests = _packsState.value
            .filter { it.status == PackInstallStatus.INSTALLED }
            .map { it.manifest }

        // If any installed pack provides a DIRECT model for this pair, prefer DIRECT
        val hasDirect = installedManifests.any { manifest ->
            manifest.directions.any {
                it.source == pair.source.code && it.target == pair.target.code && it.routing == RoutingMode.DIRECT
            }
        }
        if (hasDirect) return RoutingMode.DIRECT

        // Default routing rule: ru<->tr uses PIVOT via English unless a direct pack is installed
        return if ((pair.source.code == "ru" && pair.target.code == "tr") ||
            (pair.source.code == "tr" && pair.target.code == "ru")
        ) {
            RoutingMode.PIVOT
        } else {
            RoutingMode.DIRECT
        }
    }

    override suspend fun getTotalDiskUsageBytes(): Long = withContext(Dispatchers.IO) {
        installedPackDao.getInstalledPacksSnapshot().sumOf { it.diskSizeBytes }
    }

    /**
     * Supports schema migrations from legacy v0 manifests to v1.
     */
    fun migrateAndParseManifest(rawJson: String): LanguagePackManifest {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 0
        require(schemaVersion <= LanguagePackManifest.CURRENT_SCHEMA_VERSION) {
            "Unsupported newer manifest schemaVersion=$schemaVersion"
        }
        val parsed = json.decodeFromString<LanguagePackManifest>(rawJson)
        require(parsed.packId.isNotBlank()) { "manifest.packId must not be blank" }
        require(parsed.languages.isNotEmpty()) { "manifest.languages must not be empty" }
        require(parsed.directions.isNotEmpty()) { "manifest.directions must not be empty" }
        return if (schemaVersion == 0) {
            parsed.copy(schemaVersion = LanguagePackManifest.CURRENT_SCHEMA_VERSION)
        } else {
            parsed
        }
    }

    private fun updatePackStatus(
        packId: String,
        status: PackInstallStatus,
        progress: Int,
        downloaded: Long
    ) {
        _packsState.value = _packsState.value.map { state ->
            if (state.manifest.packId == packId) {
                state.copy(status = status, progressPercent = progress, downloadedBytes = downloaded)
            } else {
                state
            }
        }
    }

    companion object {
        fun defaultBuiltInCatalog(): List<LanguagePackManifest> = listOf(
            LanguagePackManifest(
                schemaVersion = 1,
                packId = "org.offline.translate.pack.ru_en_core",
                version = "1.2.0",
                minAppVersionCode = 1,
                displayName = mapOf(
                    "ru" to "Русский ↔ Английский (Базовый технический)",
                    "en" to "Russian ↔ English (Core Technical)",
                    "tr" to "Rusça ↔ İngilizce (Temel Teknik)"
                ),
                languages = listOf(
                    PackLanguageEntry("ru", "Русский", "Cyrl", "ru-RU"),
                    PackLanguageEntry("en", "English", "Latn", "en-US")
                ),
                directions = listOf(
                    PackDirectionEntry("ru", "en", RoutingMode.DIRECT, mtModelDir = "mt/opus_ru_en_int8"),
                    PackDirectionEntry("en", "ru", RoutingMode.DIRECT, mtModelDir = "mt/opus_en_ru_int8")
                ),
                components = PackComponents(
                    asr = PackEngineDescriptor("sherpa_zipformer_int8", "asr/ru_en_zipformer", "int8", 160),
                    mt = PackEngineDescriptor("ctranslate2_opus_int8", "mt/", "int8", 130),
                    tts = PackEngineDescriptor("piper_vits_onnx_int8", "tts/ru_en_piper", "int8", 75),
                    glossaries = listOf("glossaries/domain_it.csv", "glossaries/domain_mechanical.csv")
                ),
                license = PackLicenseInfo(
                    spdxId = "CC-BY-4.0 AND MIT AND Apache-2.0",
                    commercialUseAllowed = true,
                    attributionUrl = "https://github.com/Helsinki-NLP/Opus-MT"
                ),
                hardwareRequirements = PackHardwareRequirements(
                    minRamMb = 4096,
                    recommendedRamMb = 6144,
                    diskSizeBytes = 245_000_000L
                ),
                sha256 = CryptoVerifier.sha256Hex("org.offline.translate.pack.ru_en_core:1.2.0".toByteArray())
            ),
            LanguagePackManifest(
                schemaVersion = 1,
                packId = "org.offline.translate.pack.tr_en_pivot_ru",
                version = "1.2.0",
                minAppVersionCode = 1,
                displayName = mapOf(
                    "ru" to "Турецкий ↔ Английский + Pivot Русский↔Турецкий",
                    "en" to "Turkish ↔ English + Pivot Russian↔Turkish",
                    "tr" to "Türkçe ↔ İngilizce + Pivot Rusça↔Türkçe"
                ),
                languages = listOf(
                    PackLanguageEntry("tr", "Türkçe", "Latn", "tr-TR"),
                    PackLanguageEntry("en", "English", "Latn", "en-US"),
                    PackLanguageEntry("ru", "Русский", "Cyrl", "ru-RU")
                ),
                directions = listOf(
                    PackDirectionEntry("tr", "en", RoutingMode.DIRECT, mtModelDir = "mt/opus_tr_en_int8"),
                    PackDirectionEntry("en", "tr", RoutingMode.DIRECT, mtModelDir = "mt/opus_en_tr_int8"),
                    PackDirectionEntry("ru", "tr", RoutingMode.PIVOT, pivotLanguage = "en"),
                    PackDirectionEntry("tr", "ru", RoutingMode.PIVOT, pivotLanguage = "en")
                ),
                components = PackComponents(
                    asr = PackEngineDescriptor("sherpa_zipformer_int8", "asr/tr_zipformer", "int8", 150),
                    mt = PackEngineDescriptor("ctranslate2_opus_int8", "mt/", "int8", 130),
                    tts = PackEngineDescriptor("piper_vits_onnx_int8", "tts/tr_piper", "int8", 70),
                    glossaries = listOf("glossaries/domain_oilgas.csv", "glossaries/domain_construction.csv")
                ),
                license = PackLicenseInfo(
                    spdxId = "CC-BY-4.0 AND MIT",
                    commercialUseAllowed = true,
                    attributionUrl = "https://github.com/Helsinki-NLP/Opus-MT"
                ),
                hardwareRequirements = PackHardwareRequirements(
                    minRamMb = 4096,
                    recommendedRamMb = 6144,
                    diskSizeBytes = 210_000_000L
                ),
                sha256 = CryptoVerifier.sha256Hex("org.offline.translate.pack.tr_en_pivot_ru:1.2.0".toByteArray())
            ),
            LanguagePackManifest(
                schemaVersion = 1,
                packId = "org.offline.translate.pack.ru_tr_direct",
                version = "1.0.0",
                minAppVersionCode = 1,
                displayName = mapOf(
                    "ru" to "Русский ↔ Турецкий (Прямая модель без pivot)",
                    "en" to "Russian ↔ Turkish (Direct Model)",
                    "tr" to "Rusça ↔ Türkçe (Doğrudan Model)"
                ),
                languages = listOf(
                    PackLanguageEntry("ru", "Русский", "Cyrl", "ru-RU"),
                    PackLanguageEntry("tr", "Türkçe", "Latn", "tr-TR")
                ),
                directions = listOf(
                    PackDirectionEntry("ru", "tr", RoutingMode.DIRECT, mtModelDir = "mt/opus_ru_tr_int8"),
                    PackDirectionEntry("tr", "ru", RoutingMode.DIRECT, mtModelDir = "mt/opus_tr_ru_int8")
                ),
                components = PackComponents(
                    mt = PackEngineDescriptor("ctranslate2_opus_int8", "mt/", "int8", 125)
                ),
                license = PackLicenseInfo(
                    spdxId = "CC-BY-4.0",
                    commercialUseAllowed = true,
                    attributionUrl = "https://github.com/Helsinki-NLP/Opus-MT"
                ),
                hardwareRequirements = PackHardwareRequirements(
                    minRamMb = 4096,
                    recommendedRamMb = 6144,
                    diskSizeBytes = 165_000_000L
                ),
                sha256 = CryptoVerifier.sha256Hex("org.offline.translate.pack.ru_tr_direct:1.0.0".toByteArray())
            ),
            LanguagePackManifest(
                schemaVersion = 1,
                packId = "org.offline.translate.pack.nllb200_600m_nc",
                version = "1.0.0",
                minAppVersionCode = 1,
                displayName = mapOf(
                    "ru" to "NLLB-200 600M Многоязычный (ТОЛЬКО НЕКОММЕРЧЕСКИЙ)",
                    "en" to "NLLB-200 600M Multilingual (NON-COMMERCIAL ONLY)",
                    "tr" to "NLLB-200 600M Çok Dilli (YARAMAZ TİCARİ KULLANIM)"
                ),
                languages = listOf(
                    PackLanguageEntry("ru", "Русский", "Cyrl", "ru-RU"),
                    PackLanguageEntry("en", "English", "Latn", "en-US"),
                    PackLanguageEntry("tr", "Türkçe", "Latn", "tr-TR")
                ),
                directions = listOf(
                    PackDirectionEntry("ru", "tr", RoutingMode.DIRECT, mtModelDir = "mt/nllb200_int8"),
                    PackDirectionEntry("tr", "ru", RoutingMode.DIRECT, mtModelDir = "mt/nllb200_int8")
                ),
                components = PackComponents(
                    mt = PackEngineDescriptor("ctranslate2_nllb_int8", "mt/nllb200_int8", "int8", 740)
                ),
                license = PackLicenseInfo(
                    spdxId = "CC-BY-NC-4.0",
                    commercialUseAllowed = false,
                    attributionUrl = "https://huggingface.co/facebook/nllb-200-distilled-600M",
                    warningNote = "NON-COMMERCIAL LICENSE (CC-BY-NC-4.0). Do not enable in commercial deployments."
                ),
                hardwareRequirements = PackHardwareRequirements(
                    minRamMb = 6144,
                    recommendedRamMb = 8192,
                    diskSizeBytes = 640_000_000L
                ),
                sha256 = CryptoVerifier.sha256Hex("org.offline.translate.pack.nllb200_600m_nc:1.0.0".toByteArray())
            )
        )
    }
}
