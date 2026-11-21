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
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.core.model.TechnicalDomain
import com.offline.translate.core.model.ThermalLevel
import com.offline.translate.engine.api.EngineRegistry
import com.offline.translate.engine.api.LanguagePackManager
import com.offline.translate.engine.api.StreamingTranslationPipeline
import com.offline.translate.engine.api.ThermalMonitor
import com.offline.translate.engine.api.VadEngine
import com.offline.translate.engine.asr.AndroidSystemAsrEngine
import com.offline.translate.engine.asr.MockAsrEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveTranslationUiState(
    val pair: LanguagePair = LanguagePair(Language.RUSSIAN, Language.ENGLISH),
    val availableLanguages: List<Language> = Language.DEFAULT_LANGUAGES,
    val detectedSourceCode: String? = null,
    val selectedDomain: TechnicalDomain = TechnicalDomain.GENERAL_TECH,
    val availableDomains: List<TechnicalDomain> = TechnicalDomain.BUILT_IN_DOMAINS,
    val qualityProfile: QualityProfile = QualityProfile.BALANCED,
    val effectiveProfile: QualityProfile = QualityProfile.BALANCED,
    val thermalLevel: ThermalLevel = ThermalLevel.NORMAL,
    val inputText: String = "",
    val isTranslating: Boolean = false,
    val isRecording: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val audioRmsDb: Float = -60f,
    val sourceConfirmedText: String = "",
    val sourceUnstablePartialText: String = "",
    val translatedConfirmedText: String = "",
    val translatedUnstablePartialText: String = "",
    val isFinalSegment: Boolean = false,
    val routingUsed: RoutingMode = RoutingMode.DIRECT,
    val engineBackendName: String = "",
    val errorMessage: String? = null,
    val lastHistoryId: Long? = null,
    val isCurrentFavorite: Boolean = false,
    val appliedTerms: List<AppliedTermMatch> = emptyList(),
    val preservedTokens: List<String> = emptyList(),
    val endToEndLatencyMs: Long = 0L,
    val estimatedRamMb: Int = 145,
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val devMockMode: Boolean = false
)

@HiltViewModel
class LiveTranslationViewModel @Inject constructor(
    private val settingsDataStore: AppSettingsDataStore,
    private val pipeline: StreamingTranslationPipeline,
    private val engineRegistry: EngineRegistry,
    private val packManager: LanguagePackManager,
    private val thermalMonitor: ThermalMonitor,
    private val vadEngine: VadEngine,
    private val realAsrEngine: AndroidSystemAsrEngine,
    private val mockAsrEngine: MockAsrEngine,
    private val historyDao: TranslationHistoryDao
) : ViewModel() {

    private val _sessionState = MutableStateFlow(LiveTranslationUiState())
    private var recordingJob: Job? = null
    private var translationJob: Job? = null

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
            ttsSpeechRate = settings.ttsSpeechRate,
            devMockMode = settings.devMockMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LiveTranslationUiState()
    )

    init {
        viewModelScope.launch {
            merge(realAsrEngine.events, mockAsrEngine.events).collect { speechEvent ->
                if (speechEvent.errorMessage != null && speechEvent.fullText.isBlank()) {
                    _sessionState.value = _sessionState.value.copy(
                        isRecording = false,
                        isSpeechDetected = false,
                        isTranslating = false,
                        audioRmsDb = -60f,
                        errorMessage = speechEvent.errorMessage
                    )
                    pipeline.setRecordingState(false)
                    return@collect
                }

                val current = uiState.value
                _sessionState.value = _sessionState.value.copy(
                    inputText = speechEvent.fullText,
                    sourceConfirmedText = speechEvent.confirmedText,
                    sourceUnstablePartialText = speechEvent.unstableTailText,
                    audioRmsDb = speechEvent.audioRmsDb,
                    isSpeechDetected = !speechEvent.isFinal,
                    isRecording = !speechEvent.isFinal,
                    isTranslating = true,
                    errorMessage = null
                )

                val translated = pipeline.processSpeechEvent(
                    event = speechEvent,
                    pair = current.pair,
                    domainId = current.selectedDomain.id,
                    requestedProfile = current.qualityProfile,
                    ttsEnabled = current.ttsEnabled,
                    speechRate = current.ttsSpeechRate
                )

                var savedId: Long? = null
                if (translated.isFinal && translated.errorMessage == null && translated.fullTranslatedText.isNotBlank()) {
                    savedId = historyDao.insert(
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

                _sessionState.value = _sessionState.value.copy(
                    isTranslating = false,
                    isRecording = !speechEvent.isFinal,
                    detectedSourceCode = translated.detectedSourceLanguage,
                    sourceConfirmedText = translated.normalizedSourceText.takeIf { speechEvent.isFinal } ?: speechEvent.confirmedText,
                    sourceUnstablePartialText = if (speechEvent.isFinal) "" else speechEvent.unstableTailText,
                    translatedConfirmedText = translated.translatedConfirmedText,
                    translatedUnstablePartialText = translated.translatedPreviewTailText,
                    isFinalSegment = translated.isFinal,
                    routingUsed = translated.routingUsed,
                    engineBackendName = translated.engineBackendName,
                    errorMessage = translated.errorMessage,
                    lastHistoryId = savedId ?: _sessionState.value.lastHistoryId,
                    isCurrentFavorite = if (savedId != null) false else _sessionState.value.isCurrentFavorite,
                    appliedTerms = translated.appliedTerms,
                    preservedTokens = translated.preservedTokens,
                    endToEndLatencyMs = translated.endToEndLatencyMs
                )

                if (speechEvent.isFinal) {
                    pipeline.setRecordingState(false)
                }
            }
        }
    }

    fun updateInputText(newText: String) {
        _sessionState.value = _sessionState.value.copy(
            inputText = newText,
            errorMessage = null
        )
    }

    fun clearAll() {
        translationJob?.cancel()
        _sessionState.value = _sessionState.value.copy(
            inputText = "",
            sourceConfirmedText = "",
            sourceUnstablePartialText = "",
            translatedConfirmedText = "",
            translatedUnstablePartialText = "",
            errorMessage = null,
            appliedTerms = emptyList(),
            preservedTokens = emptyList(),
            lastHistoryId = null,
            isCurrentFavorite = false,
            isTranslating = false
        )
    }

    /**
     * Translates typed or pasted text with duplicate-click protection (`isTranslating` guard).
     */
    fun translateCurrentText() {
        if (_sessionState.value.isTranslating) return
        val rawText = _sessionState.value.inputText.trim()
        if (rawText.isEmpty()) {
            _sessionState.value = _sessionState.value.copy(
                errorMessage = "Поле ввода пустое. Введите текст или нажмите на микрофон."
            )
            return
        }

        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            _sessionState.value = _sessionState.value.copy(
                isTranslating = true,
                errorMessage = null,
                isFinalSegment = false
            )
            val s = settingsDataStore.settingsFlow.first()
            val seg = pipeline.processSpeechEvent(
                event = SpeechRecognitionEvent(
                    segmentId = System.currentTimeMillis(),
                    languageCode = if (s.autoDetectSource) "auto" else s.sourceLanguageCode,
                    confirmedText = rawText,
                    unstableTailText = "",
                    isFinal = true
                ),
                pair = s.activePair,
                domainId = s.defaultDomainId,
                requestedProfile = s.qualityProfile,
                ttsEnabled = false,
                speechRate = s.ttsSpeechRate
            )

            var savedId: Long? = null
            if (seg.errorMessage == null && seg.fullTranslatedText.isNotBlank()) {
                savedId = historyDao.insert(
                    TranslationHistoryEntity(
                        sourceLang = seg.sourceLanguage,
                        targetLang = seg.targetLanguage,
                        domainId = seg.domainId,
                        sourceText = seg.normalizedSourceText,
                        translatedText = seg.fullTranslatedText,
                        routingMode = seg.routingUsed.name,
                        appliedTermsSummary = seg.appliedTerms.joinToString("; ") { "${it.sourceTerm}->${it.targetTerm}" },
                        latencyMs = seg.endToEndLatencyMs
                    )
                )
            }

            _sessionState.value = _sessionState.value.copy(
                isTranslating = false,
                detectedSourceCode = seg.detectedSourceLanguage,
                sourceConfirmedText = seg.normalizedSourceText,
                sourceUnstablePartialText = "",
                translatedConfirmedText = seg.translatedConfirmedText,
                translatedUnstablePartialText = "",
                isFinalSegment = true,
                routingUsed = seg.routingUsed,
                engineBackendName = seg.engineBackendName,
                errorMessage = seg.errorMessage,
                lastHistoryId = savedId,
                isCurrentFavorite = false,
                appliedTerms = seg.appliedTerms,
                preservedTokens = seg.preservedTokens,
                endToEndLatencyMs = seg.endToEndLatencyMs
            )
        }
    }

    fun speakTranslatedResult() {
        val text = _sessionState.value.translatedConfirmedText.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val bundle = engineRegistry.acquireActivePair(s.activePair, s.qualityProfile)
            bundle.ttsEngine.speak(text, s.targetLanguageCode, s.ttsSpeechRate)
        }
    }

    fun toggleFavoriteCurrentResult() {
        val id = _sessionState.value.lastHistoryId ?: return
        val newFav = !_sessionState.value.isCurrentFavorite
        viewModelScope.launch {
            historyDao.setFavorite(id, newFav)
            _sessionState.value = _sessionState.value.copy(isCurrentFavorite = newFav)
        }
    }

    fun onMicrophonePermissionDenied() {
        _sessionState.value = _sessionState.value.copy(
            isRecording = false,
            errorMessage = "Доступ к микрофону отклонён. Разрешите доступ к микрофону для голосового перевода."
        )
    }

    fun toggleRecording() {
        if (_sessionState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun startRecording() {
        if (_sessionState.value.isRecording) return
        _sessionState.value = _sessionState.value.copy(
            isRecording = true,
            isFinalSegment = false,
            errorMessage = null
        )
        pipeline.setRecordingState(true)
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            val settings = settingsDataStore.settingsFlow.first()
            engineRegistry.setDevMockMode(settings.devMockMode)
            val bundle = engineRegistry.acquireActivePair(settings.activePair, settings.qualityProfile)
            val langForSpeech = if (settings.autoDetectSource) settings.sourceLanguageCode else settings.activePair.source.code
            if (settings.devMockMode) {
                mockAsrEngine.simulateStreamingUtterance(languageCode = langForSpeech)
                _sessionState.value = _sessionState.value.copy(isRecording = false, isSpeechDetected = false, audioRmsDb = -60f)
                pipeline.setRecordingState(false)
            } else {
                bundle.asrEngine.startStream(langForSpeech)
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        viewModelScope.launch {
            val settings = settingsDataStore.settingsFlow.first()
            if (settings.devMockMode) {
                mockAsrEngine.finishStream()
            } else {
                realAsrEngine.finishStream()
            }
            vadEngine.reset()
            _sessionState.value = _sessionState.value.copy(
                isRecording = false,
                isSpeechDetected = false,
                audioRmsDb = -60f
            )
            pipeline.setRecordingState(false)
        }
    }

    fun dismissError() {
        _sessionState.value = _sessionState.value.copy(errorMessage = null)
    }

    fun selectSourceLanguage(language: Language) {
        viewModelScope.launch {
            val currentTarget = uiState.value.pair.target
            if (!language.isAuto && language.code == currentTarget.code) {
                settingsDataStore.swapLanguages(_sessionState.value.detectedSourceCode)
            } else {
                settingsDataStore.setLanguagePair(language.code, currentTarget.code)
            }
        }
    }

    fun selectTargetLanguage(language: Language) {
        viewModelScope.launch {
            val currentSource = uiState.value.pair.source
            if (!currentSource.isAuto && language.code == currentSource.code) {
                settingsDataStore.swapLanguages(_sessionState.value.detectedSourceCode)
            } else {
                val srcCode = if (currentSource.isAuto) Language.AUTO_CODE else currentSource.code
                settingsDataStore.setLanguagePair(srcCode, language.code)
            }
        }
    }

    fun swapLanguages() {
        viewModelScope.launch {
            val prevTranslated = _sessionState.value.translatedConfirmedText
            settingsDataStore.swapLanguages(_sessionState.value.detectedSourceCode)
            if (prevTranslated.isNotBlank()) {
                _sessionState.value = _sessionState.value.copy(inputText = prevTranslated)
            }
        }
    }

    fun selectDomain(domain: TechnicalDomain) {
        viewModelScope.launch {
            settingsDataStore.setDefaultDomain(domain.id)
        }
    }
}
