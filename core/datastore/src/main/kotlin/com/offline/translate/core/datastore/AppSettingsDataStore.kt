package com.offline.translate.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offline.translate.core.model.HardwareBenchmarkResult
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.TechnicalDomain
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "translator_preferences")

enum class AppThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class TtsEnginePreference {
    PIPER_NEURAL, ANDROID_SYSTEM_OFFLINE
}

data class UserSettings(
    val sourceLanguageCode: String = Language.RUSSIAN.code,
    val targetLanguageCode: String = Language.ENGLISH.code,
    val defaultDomainId: String = TechnicalDomain.IT_PROGRAMMING.id,
    val qualityProfile: QualityProfile = QualityProfile.BALANCED,
    val ttsEnabled: Boolean = true,
    val ttsEnginePreference: TtsEnginePreference = TtsEnginePreference.PIPER_NEURAL,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val devMockMode: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val catalogUrl: String = "https://packs.offline-tech-translate.org/v1/index.json",
    val benchmarkResult: HardwareBenchmarkResult? = null
) {
    val activePair: LanguagePair
        get() = LanguagePair(
            source = Language.byCodeOrCreate(sourceLanguageCode),
            target = Language.byCodeOrCreate(targetLanguageCode)
        )
}

@Singleton
class AppSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val DEFAULT_DOMAIN = stringPreferencesKey("default_domain")
        val QUALITY_PROFILE = stringPreferencesKey("quality_profile")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEV_MOCK_MODE = booleanPreferencesKey("dev_mock_mode")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val CATALOG_URL = stringPreferencesKey("catalog_url")
        val BENCHMARK_JSON = stringPreferencesKey("benchmark_json")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val benchmark = prefs[Keys.BENCHMARK_JSON]?.let {
            runCatching { json.decodeFromString<HardwareBenchmarkResult>(it) }.getOrNull()
        }
        UserSettings(
            sourceLanguageCode = prefs[Keys.SOURCE_LANG] ?: Language.RUSSIAN.code,
            targetLanguageCode = prefs[Keys.TARGET_LANG] ?: Language.ENGLISH.code,
            defaultDomainId = prefs[Keys.DEFAULT_DOMAIN] ?: TechnicalDomain.IT_PROGRAMMING.id,
            qualityProfile = prefs[Keys.QUALITY_PROFILE]?.let {
                runCatching { QualityProfile.valueOf(it) }.getOrNull()
            } ?: QualityProfile.BALANCED,
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
            ttsEnginePreference = prefs[Keys.TTS_ENGINE]?.let {
                runCatching { TtsEnginePreference.valueOf(it) }.getOrNull()
            } ?: TtsEnginePreference.PIPER_NEURAL,
            themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { AppThemeMode.valueOf(it) }.getOrNull()
            } ?: AppThemeMode.SYSTEM,
            devMockMode = prefs[Keys.DEV_MOCK_MODE] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: false,
            catalogUrl = prefs[Keys.CATALOG_URL] ?: "https://packs.offline-tech-translate.org/v1/index.json",
            benchmarkResult = benchmark
        )
    }

    suspend fun setLanguagePair(sourceCode: String, targetCode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SOURCE_LANG] = sourceCode
            prefs[Keys.TARGET_LANG] = targetCode
        }
    }

    suspend fun swapLanguages() {
        context.dataStore.edit { prefs ->
            val currentSrc = prefs[Keys.SOURCE_LANG] ?: Language.RUSSIAN.code
            val currentTgt = prefs[Keys.TARGET_LANG] ?: Language.ENGLISH.code
            prefs[Keys.SOURCE_LANG] = currentTgt
            prefs[Keys.TARGET_LANG] = currentSrc
        }
    }

    suspend fun setDefaultDomain(domainId: String) {
        context.dataStore.edit { it[Keys.DEFAULT_DOMAIN] = domainId }
    }

    suspend fun setQualityProfile(profile: QualityProfile) {
        context.dataStore.edit { it[Keys.QUALITY_PROFILE] = profile.name }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    suspend fun setTtsEnginePreference(pref: TtsEnginePreference) {
        context.dataStore.edit { it[Keys.TTS_ENGINE] = pref.name }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDevMockMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_MOCK_MODE] = enabled }
    }

    suspend fun completeOnboarding() {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    suspend fun saveBenchmarkResult(result: HardwareBenchmarkResult) {
        context.dataStore.edit {
            it[Keys.BENCHMARK_JSON] = json.encodeToString(result)
        }
    }
}
