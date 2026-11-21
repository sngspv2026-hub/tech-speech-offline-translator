package com.offline.translate.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.translate.core.database.TranslationHistoryDao
import com.offline.translate.core.database.TranslationHistoryEntity
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.datastore.UserSettings
import com.offline.translate.core.model.AppliedTermMatch
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.core.model.RoutingMode
import com.offline.translate.core.model.TechnicalDomain
import com.offline.translate.core.model.ThermalLevel
import com.offline.translate.engine.api.EngineRegistry
import com.offline.translate.engine.api.LanguagePackManager
import com.offline.translate.engine.api.StreamingTranslationPipeline
import com.offline.translate.engine.api.ThermalMonitor
import com.offline.translate.engine.api.VadEngine
import com.offline.translate.engine.asr.MockAsrEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveTranslationUiState(
    val pair: LanguagePair = LanguagePair(Language.RUSSIAN, Language.ENGLISH),
    val availableLanguages: List<Language> = Language.DEFAULT_LANGUAGES,
    val selectedDomain: TechnicalDomain = TechnicalDomain.IT_PROGRAMMING,
    val availableDomains: List<TechnicalDomain> = TechnicalDomain.BUILT_IN_DOMAINS,
    val qualityProfile: QualityProfile = QualityProfile.BALANCED,
    val effectiveProfile: QualityProfile = QualityProfile.BALANCED,
    val thermalLevel: ThermalLevel = ThermalLevel.NORMAL,
    val isRecording: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val audioRmsDb: Float = -60f,
    val sourceConfirmedText: String = "",
    val sourceUnstablePartialText: String = "",
    val translatedConfirmedText: String = "",
    val translatedUnstablePartialText: String = "",
    val isFinalSegment: Boolean = false,
    val routingUsed: RoutingMode = RoutingMode.DIRECT,
    val pivotPreviewText: String? = null,
    val appliedTerms: List<AppliedTermMatch> = emptyList(),
    val preservedTokens: List<String> = emptyList(),
    val endToEndLatencyMs: Long = 0L,
    val estimatedRamMb: Int = 145,
    val ttsEnabled: Boolean = true,
    val devMockMode: Boolean = true
)

@HiltViewModel
class LiveTranslationViewModel @Inject constructor(
    private val settingsDataStore: AppSettingsDataStore,
    private val pipeline: StreamingTranslationPipeline,
    private val engineRegistry: EngineRegistry,
    private val packManager: LanguagePackManager,
    private val thermalMonitor: ThermalMonitor,
    private val vadEngine: VadEngine,
    private val mockAsrEngine: MockAsrEngine,
    private val historyDao: TranslationHistoryDao
) : ViewModel() {

    private val _sessionState = MutableStateFlow(LiveTranslationUiState())
    private var recordingJob: Job? = null

    val uiState: StateFlow<LiveTranslationUiState> = combine(
        settingsDataStore.settingsFlow,
        _sessionState,
        packManager.availableLanguages,
        thermalMonitor.thermalLevel,
        engineRegistry.estimatedMemoryUsageMb
    ) { settings: UserSettings, session: LiveTranslationUiState, langs: List<Language>, thermal: ThermalLevel, ramMb: Int ->
        val pair = settings.activePair
        val domain = TechnicalDomain.byId(settings.defaultDomainId)
        session.copy(
            pair = pair,
            availableLanguages = langs,
            selectedDomain = domain,
            qualityProfile = settings.qualityProfile,
            effectiveProfile = thermalMonitor.effectiveProfile(settings.qualityProfile),
            thermalLevel = thermal,
            estimatedRamMb = ramMb,
            ttsEnabled = settings.ttsEnabled,
            devMockMode = settings.devMockMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LiveTranslationUiState()
    )

    init {
        viewModelScope.launch {
            mockAsrEngine.events.collect { speechEvent ->
                val current = uiState.value
                _sessionState.value = _sessionState.value.copy(
                    sourceConfirmedText = speechEvent.confirmedText,
                    sourceUnstablePartialText = speechEvent.unstableTailText,
                    audioRmsDb = speechEvent.audioRmsDb,
                    isSpeechDetected = !speechEvent.isFinal
                )
                val translated = pipeline.processSpeechEvent(
                    event = speechEvent,
                    pair = current.pair,
                    domainId = current.selectedDomain.id,
                    requestedProfile = current.qualityProfile,
                    ttsEnabled = current.ttsEnabled
                )
                _sessionState.value = _sessionState.value.copy(
                    sourceConfirmedText = translated.normalizedSourceText.takeIf { speechEvent.isFinal } ?: speechEvent.confirmedText,
                    sourceUnstablePartialText = if (speechEvent.isFinal) "" else speechEvent.unstableTailText,
                    translatedConfirmedText = translated.translatedConfirmedText,
                    translatedUnstablePartialText = translated.translatedPreviewTailText,
                    isFinalSegment = translated.isFinal,
                    routingUsed = translated.routingUsed,
                    pivotPreviewText = translated.pivotText,
                    appliedTerms = translated.appliedTerms,
                    preservedTokens = translated.preservedTokens,
                    endToEndLatencyMs = translated.endToEndLatencyMs
                )
                if (translated.isFinal && translated.fullTranslatedText.isNotBlank()) {
                    historyDao.insert(
                        TranslationHistoryEntity(
                            sourceLang = translated.sourceLanguage,
                            targetLang = translated.targetLanguage,
                            domainId = translated.domainId,
                            sourceText = translated.normalizedSourceText,
                            translatedText = translated.fullTranslatedText,
                            routingMode = translated.routingUsed.name,
                            appliedTermsSummary = translated.appliedTerms.joinToString("; ") { "${it.sourceTerm}->${it.targetTerm}" },
                            latencyMs = translated.endToEndLatencyMs
                        )
                    )
                }
            }
        }
    }

    fun toggleRecording() {
        val currentlyRecording = _sessionState.value.isRecording
        if (currentlyRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun startRecording() {
        if (_sessionState.value.isRecording) return
        _sessionState.value = _sessionState.value.copy(isRecording = true, isFinalSegment = false)
        pipeline.setRecordingState(true)
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            val settings = settingsDataStore.settingsFlow.first()
            engineRegistry.acquireActivePair(settings.activePair, settings.qualityProfile)
            mockAsrEngine.simulateStreamingUtterance(languageCode = settings.sourceLanguageCode)
            _sessionState.value = _sessionState.value.copy(isRecording = false, isSpeechDetected = false, audioRmsDb = -60f)
            pipeline.setRecordingState(false)
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        viewModelScope.launch {
            mockAsrEngine.finishStream()
            vadEngine.reset()
            _sessionState.value = _sessionState.value.copy(isRecording = false, isSpeechDetected = false, audioRmsDb = -60f)
            pipeline.setRecordingState(false)
        }
    }

    fun selectSourceLanguage(language: Language) {
        viewModelScope.launch {
            val currentTarget = uiState.value.pair.target
            if (language.code == currentTarget.code) {
                settingsDataStore.swapLanguages()
            } else {
                settingsDataStore.setLanguagePair(language.code, currentTarget.code)
            }
        }
    }

    fun selectTargetLanguage(language: Language) {
        viewModelScope.launch {
            val currentSource = uiState.value.pair.source
            if (language.code == currentSource.code) {
                settingsDataStore.swapLanguages()
            } else {
                settingsDataStore.setLanguagePair(currentSource.code, language.code)
            }
        }
    }

    fun swapLanguages() {
        viewModelScope.launch {
            settingsDataStore.swapLanguages()
        }
    }

    fun selectDomain(domain: TechnicalDomain) {
        viewModelScope.launch {
            settingsDataStore.setDefaultDomain(domain.id)
        }
    }
}
