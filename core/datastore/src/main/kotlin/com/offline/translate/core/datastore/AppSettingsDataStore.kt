package com.offline.translate.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val autoDetectSource: Boolean = false,
    val defaultDomainId: String = TechnicalDomain.GENERAL_TECH.id,
    val qualityProfile: QualityProfile = QualityProfile.BALANCED,
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsEnginePreference: TtsEnginePreference = TtsEnginePreference.ANDROID_SYSTEM_OFFLINE,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val devMockMode: Boolean = false,
    val onboardingCompleted: Boolean = true,
    val catalogUrl: String = "https://dl.google.com/mlkit/translate/",
    val benchmarkResult: HardwareBenchmarkResult? = null
) {
    val activePair: LanguagePair
        get() {
            val src = if (autoDetectSource) Language.AUTO else Language.byCodeOrCreate(sourceLanguageCode)
            val tgt = Language.byCodeOrCreate(targetLanguageCode)
            return LanguagePair(source = src, target = tgt)
        }
}

@Singleton
class AppSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val AUTO_DETECT_SOURCE = booleanPreferencesKey("auto_detect_source")
        val DEFAULT_DOMAIN = stringPreferencesKey("default_domain")
        val QUALITY_PROFILE = stringPreferencesKey("quality_profile")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
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
            autoDetectSource = prefs[Keys.AUTO_DETECT_SOURCE] ?: false,
            defaultDomainId = prefs[Keys.DEFAULT_DOMAIN] ?: TechnicalDomain.GENERAL_TECH.id,
            qualityProfile = prefs[Keys.QUALITY_PROFILE]?.let {
                runCatching { QualityProfile.valueOf(it) }.getOrNull()
            } ?: QualityProfile.BALANCED,
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
            ttsSpeechRate = prefs[Keys.TTS_SPEECH_RATE] ?: 1.0f,
            ttsEnginePreference = prefs[Keys.TTS_ENGINE]?.let {
                runCatching { TtsEnginePreference.valueOf(it) }.getOrNull()
            } ?: TtsEnginePreference.ANDROID_SYSTEM_OFFLINE,
            themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { AppThemeMode.valueOf(it) }.getOrNull()
            } ?: AppThemeMode.SYSTEM,
            devMockMode = prefs[Keys.DEV_MOCK_MODE] ?: false,
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: true,
            catalogUrl = prefs[Keys.CATALOG_URL] ?: "https://dl.google.com/mlkit/translate/",
            benchmarkResult = benchmark
        )
    }

    suspend fun setLanguagePair(sourceCode: String, targetCode: String) {
        context.dataStore.edit { prefs ->
            if (sourceCode == Language.AUTO_CODE) {
                prefs[Keys.AUTO_DETECT_SOURCE] = true
            } else {
                prefs[Keys.AUTO_DETECT_SOURCE] = false
                prefs[Keys.SOURCE_LANG] = sourceCode
            }
            if (targetCode != Language.AUTO_CODE) {
                prefs[Keys.TARGET_LANG] = targetCode
            }
        }
    }

    suspend fun setAutoDetectSource(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_DETECT_SOURCE] = enabled
        }
    }

    suspend fun swapLanguages(lastDetectedSourceCode: String? = null) {
        context.dataStore.edit { prefs ->
            val wasAuto = prefs[Keys.AUTO_DETECT_SOURCE] ?: false
            val currentSrc = if (wasAuto) {
                lastDetectedSourceCode ?: prefs[Keys.SOURCE_LANG] ?: Language.RUSSIAN.code
            } else {
                prefs[Keys.SOURCE_LANG] ?: Language.RUSSIAN.code
            }
            val currentTgt = prefs[Keys.TARGET_LANG] ?: Language.ENGLISH.code
            val newSrc = currentTgt
            val newTgt = if (currentSrc == currentTgt || currentSrc == Language.AUTO_CODE) {
                if (currentTgt == "ru") "en" else "ru"
            } else {
                currentSrc
            }
            prefs[Keys.AUTO_DETECT_SOURCE] = false
            prefs[Keys.SOURCE_LANG] = newSrc
            prefs[Keys.TARGET_LANG] = newTgt
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

    suspend fun setTtsSpeechRate(rate: Float) {
        context.dataStore.edit { it[Keys.TTS_SPEECH_RATE] = rate.coerceIn(0.5f, 2.0f) }
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
