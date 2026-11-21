package com.offline.translate.feature.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offline.translate.core.database.TranslationHistoryDao
import com.offline.translate.core.database.TranslationHistoryEntity
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.designsystem.ErrorDiagnosticBanner
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.engine.api.EngineRegistry
import com.offline.translate.engine.api.LanguageIdentifier
import com.offline.translate.engine.api.StreamingTranslationPipeline
import com.offline.translate.engine.asr.AndroidSystemAsrEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationUiState(
    val speakerALanguage: Language = Language.RUSSIAN,
    val speakerBLanguage: Language = Language.ENGLISH,
    val speakerAText: String = "",
    val speakerBText: String = "",
    val lastDetectedLanguageCode: String? = null,
    val activeRecordingSpeaker: Int = 0, // 0 = idle, 1 = Speaker A, 2 = Speaker B
    val isProcessing: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val settingsDataStore: AppSettingsDataStore,
    private val pipeline: StreamingTranslationPipeline,
    private val engineRegistry: EngineRegistry,
    private val realAsrEngine: AndroidSystemAsrEngine,
    private val languageIdentifier: LanguageIdentifier,
    private val historyDao: TranslationHistoryDao
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.settingsFlow.collect { settings ->
                val src = if (settings.autoDetectSource) {
                    Language.byCodeOrCreate(settings.sourceLanguageCode)
                } else {
                    settings.activePair.source
                }
                val tgt = settings.activePair.target
                _state.value = _state.value.copy(
                    speakerALanguage = src,
                    speakerBLanguage = if (tgt.code == src.code) Language.ENGLISH else tgt
                )
            }
        }

        viewModelScope.launch {
            realAsrEngine.events.collect { event ->
                val activeSpeaker = _state.value.activeRecordingSpeaker
                if (activeSpeaker == 0) return@collect

                if (event.errorMessage != null && event.fullText.isBlank()) {
                    _state.value = _state.value.copy(
                        activeRecordingSpeaker = 0,
                        isProcessing = false,
                        errorMessage = event.errorMessage
                    )
                    return@collect
                }

                if (event.fullText.isNotBlank()) {
                    if (activeSpeaker == 1) {
                        _state.value = _state.value.copy(speakerAText = event.fullText)
                    } else {
                        _state.value = _state.value.copy(speakerBText = event.fullText)
                    }
                }

                if (event.isFinal && event.fullText.isNotBlank()) {
                    _state.value = _state.value.copy(activeRecordingSpeaker = 0)
                    translateUtteranceForSpeaker(activeSpeaker, event.fullText)
                }
            }
        }
    }

    fun startOrStopVoiceForSpeaker(speakerIndex: Int) {
        val currentActive = _state.value.activeRecordingSpeaker
        if (currentActive == speakerIndex) {
            viewModelScope.launch {
                realAsrEngine.finishStream()
                _state.value = _state.value.copy(activeRecordingSpeaker = 0)
            }
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(activeRecordingSpeaker = speakerIndex, errorMessage = null)
            val langCode = if (speakerIndex == 1) {
                _state.value.speakerALanguage.code
            } else {
                _state.value.speakerBLanguage.code
            }
            realAsrEngine.startStream(langCode)
        }
    }

    fun submitTypedText(speakerIndex: Int, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.isProcessing) return
        val detected = languageIdentifier.detectLanguageCode(
            utterance = trimmed,
            candidateCodes = listOf(_state.value.speakerALanguage.code, _state.value.speakerBLanguage.code)
        )
        val effectiveSpeaker = if (speakerIndex == 0) {
            if (detected == _state.value.speakerBLanguage.code) 2 else 1
        } else {
            speakerIndex
        }
        translateUtteranceForSpeaker(effectiveSpeaker, trimmed)
    }

    private fun translateUtteranceForSpeaker(speakerIndex: Int, utterance: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessing = true, errorMessage = null)
            val s = settingsDataStore.settingsFlow.first()
            val pair = if (speakerIndex == 1) {
                LanguagePair(_state.value.speakerALanguage, _state.value.speakerBLanguage)
            } else {
                LanguagePair(_state.value.speakerBLanguage, _state.value.speakerALanguage)
            }
            val result = pipeline.processSpeechEvent(
                event = SpeechRecognitionEvent(
                    segmentId = System.currentTimeMillis(),
                    languageCode = pair.source.code,
                    confirmedText = utterance,
                    unstableTailText = "",
                    isFinal = true
                ),
                pair = pair,
                domainId = s.defaultDomainId,
                requestedProfile = s.qualityProfile,
                ttsEnabled = s.ttsEnabled,
                speechRate = s.ttsSpeechRate
            )

            if (result.errorMessage == null && result.fullTranslatedText.isNotBlank()) {
                historyDao.insert(
                    TranslationHistoryEntity(
                        sourceLang = result.sourceLanguage,
                        targetLang = result.targetLanguage,
                        domainId = result.domainId,
                        sourceText = result.normalizedSourceText,
                        translatedText = result.fullTranslatedText,
                        routingMode = result.routingUsed.name,
                        latencyMs = result.endToEndLatencyMs
                    )
                )
            }

            if (speakerIndex == 1) {
                _state.value = _state.value.copy(
                    speakerAText = result.normalizedSourceText,
                    speakerBText = result.fullTranslatedText,
                    lastDetectedLanguageCode = pair.source.code,
                    isProcessing = false,
                    errorMessage = result.errorMessage
                )
            } else {
                _state.value = _state.value.copy(
                    speakerBText = result.normalizedSourceText,
                    speakerAText = result.fullTranslatedText,
                    lastDetectedLanguageCode = pair.source.code,
                    isProcessing = false,
                    errorMessage = result.errorMessage
                )
            }
        }
    }

    fun speakText(text: String, languageCode: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val bundle = engineRegistry.acquireActivePair(s.activePair, s.qualityProfile)
            bundle.ttsEngine.speak(text, languageCode, s.ttsSpeechRate)
        }
    }

    fun swapParticipants() {
        viewModelScope.launch {
            settingsDataStore.swapLanguages(_state.value.lastDetectedLanguageCode)
            val prevA = _state.value.speakerAText
            val prevB = _state.value.speakerBText
            _state.value = _state.value.copy(speakerAText = prevB, speakerBText = prevA)
        }
    }

    fun clearConversation() {
        _state.value = _state.value.copy(speakerAText = "", speakerBText = "", errorMessage = null)
    }

    fun onMicPermissionDenied() {
        _state.value = _state.value.copy(
            activeRecordingSpeaker = 0,
            errorMessage = "Разрешите доступ к микрофону для голосового диалога."
        )
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }
}

@Composable
fun ConversationRoute(
    viewModel: ConversationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingSpeakerForPermission by remember { mutableIntStateOf(1) }
    var quickInputText by remember { mutableStateOf("") }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startOrStopVoiceForSpeaker(pendingSpeakerForPermission)
        } else {
            viewModel.onMicPermissionDenied()
        }
    }

    fun requestOrToggleMic(speakerIndex: Int) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            viewModel.startOrStopVoiceForSpeaker(speakerIndex)
        } else {
            pendingSpeakerForPermission = speakerIndex
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top half rotated 180 degrees for opposite interlocutor (Speaker B)
        ParticipantCard(
            language = state.speakerBLanguage,
            text = state.speakerBText,
            isRecording = state.activeRecordingSpeaker == 2,
            onMicClick = { requestOrToggleMic(2) },
            onSpeakClick = { viewModel.speakText(state.speakerBText, state.speakerBLanguage.code) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer(rotationZ = 180f)
        )

        if (state.isProcessing || state.activeRecordingSpeaker != 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.errorMessage != null) {
            ErrorDiagnosticBanner(
                errorMessage = state.errorMessage!!,
                onDismiss = viewModel::dismissError
            )
        }

        // Center control bar: Quick text input (with Auto-LID routing), Swap, Clear
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = quickInputText,
                onValueChange = { quickInputText = it },
                placeholder = { Text("Быстрый ввод реплики (Авто-LID)...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    if (quickInputText.isNotBlank()) {
                        IconButton(onClick = {
                            viewModel.submitTypedText(0, quickInputText)
                            quickInputText = ""
                        }) {
                            Icon(Icons.Default.Send, contentDescription = "Отправить")
                        }
                    }
                }
            )
            FilledTonalIconButton(onClick = viewModel::swapParticipants) {
                Icon(Icons.Default.SwapVert, contentDescription = "Поменять языки собеседников")
            }
            FilledTonalIconButton(onClick = viewModel::clearConversation) {
                Icon(Icons.Default.Clear, contentDescription = "Очистить диалог")
            }
        }

        // Bottom half normal orientation (Speaker A)
        ParticipantCard(
            language = state.speakerALanguage,
            text = state.speakerAText,
            isRecording = state.activeRecordingSpeaker == 1,
            onMicClick = { requestOrToggleMic(1) },
            onSpeakClick = { viewModel.speakText(state.speakerAText, state.speakerALanguage.code) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
private fun ParticipantCard(
    language: Language,
    text: String,
    isRecording: Boolean,
    onMicClick: () -> Unit,
    onSpeakClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${language.nativeName} (${language.code.uppercase()})",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (text.isNotBlank()) {
                    FilledTonalIconButton(onClick = onSpeakClick) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Озвучить")
                    }
                }
            }

            Text(
                text = if (isRecording) {
                    "🎙 Слушаю (${language.nativeName})..."
                } else {
                    text.ifBlank { "Нажмите микрофон, чтобы говорить на языке: ${language.nativeName}" }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (text.isBlank() && !isRecording) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onMicClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = if (isRecording) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRecording) "Стоп" else "Говорить (${language.code.uppercase()})",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
