package com.offline.translate.feature.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offline.translate.core.database.TranslationHistoryDao
import com.offline.translate.core.database.TranslationHistoryEntity
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.designsystem.StreamingTextPanel
import com.offline.translate.core.model.AppliedTermMatch
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.engine.api.StreamingTranslationPipeline
import com.offline.translate.engine.api.TechnicalOcrCleaner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TextTranslationUiState(
    val inputText: String = "",
    val normalizedSourceText: String = "",
    val translatedText: String = "",
    val appliedTerms: List<AppliedTermMatch> = emptyList(),
    val preservedTokens: List<String> = emptyList(),
    val latencyMs: Long = 0L
)

@HiltViewModel
class TextTranslationViewModel @Inject constructor(
    private val settingsDataStore: AppSettingsDataStore,
    private val pipeline: StreamingTranslationPipeline,
    private val ocrCleaner: TechnicalOcrCleaner,
    private val historyDao: TranslationHistoryDao
) : ViewModel() {

    private val _state = MutableStateFlow(TextTranslationUiState())
    val state: StateFlow<TextTranslationUiState> = _state.asStateFlow()

    fun updateInput(text: String) {
        _state.value = _state.value.copy(inputText = text)
    }

    fun cleanAndTranslateOcrSample(rawOcr: String? = null) {
        val sample = rawOcr ?: _state.value.inputText.ifBlank {
            "рабочее дав-\nление на клапане DN-5O составляет 150 psi при 38OV 5OHz IP6B M12x1,75"
        }
        val cleaned = ocrCleaner.cleanOcrText(sample)
        translateText(cleaned)
    }

    fun translateText(overrideText: String? = null) {
        val textToTranslate = (overrideText ?: _state.value.inputText).trim()
        if (textToTranslate.isBlank()) return
        if (overrideText != null) {
            _state.value = _state.value.copy(inputText = overrideText)
        }
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val seg = pipeline.processSpeechEvent(
                event = SpeechRecognitionEvent(
                    segmentId = System.currentTimeMillis(),
                    languageCode = s.sourceLanguageCode,
                    confirmedText = textToTranslate,
                    unstableTailText = "",
                    isFinal = true
                ),
                pair = s.activePair,
                domainId = s.defaultDomainId,
                requestedProfile = s.qualityProfile,
                ttsEnabled = false
            )
            _state.value = _state.value.copy(
                normalizedSourceText = seg.normalizedSourceText,
                translatedText = seg.fullTranslatedText,
                appliedTerms = seg.appliedTerms,
                preservedTokens = seg.preservedTokens,
                latencyMs = seg.endToEndLatencyMs
            )
            historyDao.insert(
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
    }
}

@Composable
fun TextTranslationRoute(
    viewModel: TextTranslationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = state.inputText,
            onValueChange = viewModel::updateInput,
            label = { Text("Технический текст / шильдик / спецификация") },
            placeholder = { Text("Например: рабочее давление на клапане DN-50 150 psi") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.translateText() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Перевести")
            }
            OutlinedButton(
                onClick = { viewModel.cleanAndTranslateOcrSample() }
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Text(" OCR Шильдик")
            }
            OutlinedButton(
                onClick = {
                    val clipText = clipboard.getText()?.text
                    if (!clipText.isNullOrBlank()) {
                        viewModel.translateText(clipText)
                    }
                }
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
            }
            OutlinedButton(
                onClick = {
                    if (state.translatedText.isNotBlank()) {
                        clipboard.setText(AnnotatedString(state.translatedText))
                    }
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
            }
        }

        if (state.normalizedSourceText.isNotBlank()) {
            StreamingTextPanel(
                title = "Нормализация СИ + Конвертер Imperial→Metric + Защита ID",
                confirmedText = state.normalizedSourceText,
                unstablePartialText = "",
                isFinal = true,
                placeholderText = "",
                latencyBadgeText = if (state.preservedTokens.isNotEmpty()) {
                    "Protected: ${state.preservedTokens.joinToString(", ")}"
                } else null
            )
        }

        StreamingTextPanel(
            title = "Технический перевод",
            confirmedText = state.translatedText,
            unstablePartialText = "",
            isFinal = true,
            placeholderText = "Результат перевода с принудительным применением глоссария...",
            appliedTerms = state.appliedTerms,
            latencyBadgeText = if (state.latencyMs > 0) "${state.latencyMs} ms" else null
        )
    }
}
