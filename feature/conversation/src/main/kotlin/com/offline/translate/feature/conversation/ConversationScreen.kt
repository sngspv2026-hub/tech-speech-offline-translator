package com.offline.translate.feature.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.engine.api.LanguageIdentifier
import com.offline.translate.engine.api.StreamingTranslationPipeline
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
    val activeSpeaker: Int = 0
)

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val settingsDataStore: AppSettingsDataStore,
    private val pipeline: StreamingTranslationPipeline,
    private val languageIdentifier: LanguageIdentifier
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.settingsFlow.collect { settings ->
                _state.value = _state.value.copy(
                    speakerALanguage = settings.activePair.source,
                    speakerBLanguage = settings.activePair.target
                )
            }
        }
    }

    fun speakAsParticipant(speakerIndex: Int, sampleUtterance: String) {
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val pair = if (speakerIndex == 1) {
                LanguagePair(_state.value.speakerALanguage, _state.value.speakerBLanguage)
            } else {
                LanguagePair(_state.value.speakerBLanguage, _state.value.speakerALanguage)
            }
            _state.value = _state.value.copy(activeSpeaker = speakerIndex, lastDetectedLanguageCode = pair.source.code)
            val result = pipeline.processSpeechEvent(
                event = SpeechRecognitionEvent(
                    segmentId = System.currentTimeMillis(),
                    languageCode = pair.source.code,
                    confirmedText = sampleUtterance,
                    unstableTailText = "",
                    isFinal = true
                ),
                pair = pair,
                domainId = s.defaultDomainId,
                requestedProfile = s.qualityProfile,
                ttsEnabled = s.ttsEnabled
            )
            if (speakerIndex == 1) {
                _state.value = _state.value.copy(
                    speakerAText = result.normalizedSourceText,
                    speakerBText = result.fullTranslatedText,
                    activeSpeaker = 0
                )
            } else {
                _state.value = _state.value.copy(
                    speakerBText = result.normalizedSourceText,
                    speakerAText = result.fullTranslatedText,
                    activeSpeaker = 0
                )
            }
        }
    }

    /**
     * Automatically detects which interlocutor is speaking using OfflineLanguageIdentifier (LID)
     * and routes translation in the appropriate direction.
     */
    fun speakWithAutoLanguageDetection(utterance: String) {
        val langA = _state.value.speakerALanguage.code
        val langB = _state.value.speakerBLanguage.code
        val detected = languageIdentifier.detectLanguageCode(
            utterance = utterance,
            candidateCodes = listOf(langA, langB)
        )
        val speakerIdx = if (detected == langB) 2 else 1
        speakAsParticipant(speakerIdx, utterance)
    }
}

@Composable
fun ConversationRoute(
    viewModel: ConversationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top half rotated 180 degrees for opposite interlocutor
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer(rotationZ = 180f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${state.speakerBLanguage.nativeName} (${state.speakerBLanguage.code.uppercase()})",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = state.speakerBText.ifBlank { "Нажмите кнопку своего языка для ответа / Tap to speak..." },
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            val phrase = when (state.speakerBLanguage.code) {
                                "tr" -> "DN-50 vanasındaki çalışma basıncı beş megapaskal"
                                "ru" -> "рабочее давление на клапане DN-50 составляет пять мегапаскалей"
                                else -> "operating pressure on valve DN-50 is five megapascals"
                            }
                            viewModel.speakAsParticipant(2, phrase)
                        }
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Text(" ${state.speakerBLanguage.code.uppercase()}")
                    }
                }
            }
        }

        // Center bar with Auto-LID button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.speakWithAutoLanguageDetection(
                        "рабочее давление на клапане DN-50 составляет 150 psi"
                    )
                }
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Text(" Авто-определение говорящего (LID)")
            }
            if (state.lastDetectedLanguageCode != null) {
                Text(
                    text = "LID: ${state.lastDetectedLanguageCode?.uppercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))

        // Bottom half normal orientation
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${state.speakerALanguage.nativeName} (${state.speakerALanguage.code.uppercase()})",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = state.speakerAText.ifBlank { "Нажмите кнопку своего языка для начала разговора..." },
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val phrase = when (state.speakerALanguage.code) {
                                "ru" -> "рабочее давление на клапане DN-50 составляет пять мегапаскалей"
                                "tr" -> "DN-50 vanasındaki çalışma basıncı beş megapaskal"
                                else -> "operating pressure on valve DN-50 is five megapascals"
                            }
                            viewModel.speakAsParticipant(1, phrase)
                        }
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Text(" ${state.speakerALanguage.code.uppercase()}")
                    }
                }
            }
        }
    }
}
