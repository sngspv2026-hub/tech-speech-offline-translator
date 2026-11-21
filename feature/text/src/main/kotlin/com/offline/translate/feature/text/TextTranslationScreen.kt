package com.offline.translate.feature.text

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.offline.translate.core.database.TranslationHistoryDao
import com.offline.translate.core.database.TranslationHistoryEntity
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.datastore.UserSettings
import com.offline.translate.core.designsystem.ErrorDiagnosticBanner
import com.offline.translate.core.designsystem.LanguagePairSelectorBar
import com.offline.translate.core.designsystem.StreamingTextPanel
import com.offline.translate.core.designsystem.TechnicalDomainSelectorRow
import com.offline.translate.core.model.AppliedTermMatch
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.core.model.TechnicalDomain
import com.offline.translate.engine.api.EngineRegistry
import com.offline.translate.engine.api.LanguagePackManager
import com.offline.translate.engine.api.StreamingTranslationPipeline
import com.offline.translate.engine.api.TechnicalOcrCleaner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject

/**
 * Mode #3 State: Engineering Document, Specification, Clipboard & Industrial Nameplate/Photo OCR Translator.
 */
data class TextTranslationUiState(
    val pair: LanguagePair = LanguagePair(Language.AUTO, Language.ENGLISH),
    val availableLanguages: List<Language> = Language.DEFAULT_LANGUAGES,
    val selectedDomain: TechnicalDomain = TechnicalDomain.GENERAL_TECH,
    val availableDomains: List<TechnicalDomain> = TechnicalDomain.BUILT_IN_DOMAINS,
    val detectedSourceCode: String? = null,
    val enableOcrCleanup: Boolean = true,
    val enableUnitNormalization: Boolean = true,
    val enableImperialToSi: Boolean = true,
    val inputText: String = "",
    val normalizedSourceText: String = "",
    val translatedText: String = "",
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val engineBackendName: String = "",
    val lastHistoryId: Long? = null,
    val isFavorite: Boolean = false,
    val appliedTerms: List<AppliedTermMatch> = emptyList(),
    val preservedTokens: List<String> = emptyList(),
    val latencyMs: Long = 0L
)

@HiltViewModel
class TextTranslationViewModel @Inject constructor(
    private val settingsDataStore: AppSettingsDataStore,
    private val packManager: LanguagePackManager,
    private val engineRegistry: EngineRegistry,
    private val pipeline: StreamingTranslationPipeline,
    private val ocrCleaner: TechnicalOcrCleaner,
    private val historyDao: TranslationHistoryDao
) : ViewModel() {

    private val _localState = MutableStateFlow(TextTranslationUiState())
    private var translateJob: Job? = null

    val state: StateFlow<TextTranslationUiState> = combine(
        settingsDataStore.settingsFlow,
        packManager.availableLanguages,
        _localState
    ) { settings: UserSettings, langs: List<Language>, local: TextTranslationUiState ->
        local.copy(
            pair = settings.activePair,
            availableLanguages = langs,
            selectedDomain = TechnicalDomain.byId(settings.defaultDomainId)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TextTranslationUiState())

    fun updateInput(text: String) {
        _localState.value = _localState.value.copy(
            inputText = text,
            errorMessage = null
        )
    }

    fun clearAll() {
        translateJob?.cancel()
        _localState.value = _localState.value.copy(
            inputText = "",
            normalizedSourceText = "",
            translatedText = "",
            errorMessage = null,
            appliedTerms = emptyList(),
            preservedTokens = emptyList(),
            lastHistoryId = null,
            isFavorite = false,
            isProcessing = false
        )
    }

    fun toggleOcrCleanup() {
        _localState.value = _localState.value.copy(enableOcrCleanup = !_localState.value.enableOcrCleanup)
    }

    fun toggleUnitNormalization() {
        _localState.value = _localState.value.copy(enableUnitNormalization = !_localState.value.enableUnitNormalization)
    }

    fun toggleImperialToSi() {
        _localState.value = _localState.value.copy(enableImperialToSi = !_localState.value.enableImperialToSi)
    }

    fun selectSourceLanguage(language: Language) {
        viewModelScope.launch {
            val currentTarget = state.value.pair.target
            if (!language.isAuto && language.code == currentTarget.code) {
                settingsDataStore.swapLanguages(_localState.value.detectedSourceCode)
            } else {
                settingsDataStore.setLanguagePair(language.code, currentTarget.code)
            }
        }
    }

    fun selectTargetLanguage(language: Language) {
        viewModelScope.launch {
            val currentSource = state.value.pair.source
            if (!currentSource.isAuto && language.code == currentSource.code) {
                settingsDataStore.swapLanguages(_localState.value.detectedSourceCode)
            } else {
                val srcCode = if (currentSource.isAuto) Language.AUTO_CODE else currentSource.code
                settingsDataStore.setLanguagePair(srcCode, language.code)
            }
        }
    }

    fun swapLanguages() {
        viewModelScope.launch {
            val prevTranslated = _localState.value.translatedText
            settingsDataStore.swapLanguages(_localState.value.detectedSourceCode)
            if (prevTranslated.isNotBlank()) {
                _localState.value = _localState.value.copy(inputText = prevTranslated)
            }
        }
    }

    fun selectDomain(domain: TechnicalDomain) {
        viewModelScope.launch {
            settingsDataStore.setDefaultDomain(domain.id)
        }
    }

    /**
     * Runs real on-device ML Kit Text Recognition on an image selected by the user (photo of a machine nameplate,
     * schematic, document, or PLC screen), cleans industrial OCR noise, and translates it.
     */
    fun recognizeImageAndTranslate(context: Context, imageUri: Uri) {
        if (_localState.value.isProcessing) return
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _localState.value = _localState.value.copy(isProcessing = true, errorMessage = null)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val inputImage = InputImage.fromFilePath(context, imageUri)
                val visionText = recognizer.process(inputImage).await()
                val extractedRaw = visionText.text.trim()
                if (extractedRaw.isEmpty()) {
                    _localState.value = _localState.value.copy(
                        isProcessing = false,
                        errorMessage = "На выбранном изображении не обнаружено читаемого текста."
                    )
                    return@launch
                }
                val prepared = if (_localState.value.enableOcrCleanup) {
                    ocrCleaner.cleanOcrText(extractedRaw)
                } else {
                    extractedRaw
                }
                _localState.value = _localState.value.copy(inputText = prepared, isProcessing = false)
                translateText(prepared)
            } catch (e: Exception) {
                _localState.value = _localState.value.copy(
                    isProcessing = false,
                    errorMessage = "Ошибка распознавания фото (OCR): ${e.localizedMessage ?: "не удалось прочитать файл"}"
                )
            } finally {
                runCatching { recognizer.close() }
            }
        }
    }

    fun translateText(overrideText: String? = null) {
        if (_localState.value.isProcessing) return
        val raw = (overrideText ?: _localState.value.inputText).trim()
        if (raw.isEmpty()) {
            _localState.value = _localState.value.copy(
                errorMessage = "Введите или вставьте технический текст либо отсканируйте фото шильдика."
            )
            return
        }
        if (overrideText != null) {
            _localState.value = _localState.value.copy(inputText = overrideText)
        }

        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _localState.value = _localState.value.copy(isProcessing = true, errorMessage = null)
            val s = settingsDataStore.settingsFlow.first()
            val localSnap = _localState.value

            val ocrProcessed = if (localSnap.enableOcrCleanup) {
                ocrCleaner.cleanOcrText(raw)
            } else {
                raw
            }

            val seg = pipeline.processSpeechEvent(
                event = SpeechRecognitionEvent(
                    segmentId = System.currentTimeMillis(),
                    languageCode = if (s.autoDetectSource) Language.AUTO_CODE else s.sourceLanguageCode,
                    confirmedText = ocrProcessed,
                    unstableTailText = "",
                    isFinal = true
                ),
                pair = s.activePair,
                domainId = s.defaultDomainId,
                requestedProfile = s.qualityProfile,
                ttsEnabled = false,
                speechRate = s.ttsSpeechRate,
                enableNormalization = localSnap.enableUnitNormalization,
                enableImperialConversion = localSnap.enableImperialToSi
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

            _localState.value = _localState.value.copy(
                isProcessing = false,
                detectedSourceCode = seg.detectedSourceLanguage,
                normalizedSourceText = seg.normalizedSourceText,
                translatedText = seg.fullTranslatedText,
                engineBackendName = seg.engineBackendName,
                errorMessage = seg.errorMessage,
                lastHistoryId = savedId,
                isFavorite = false,
                appliedTerms = seg.appliedTerms,
                preservedTokens = seg.preservedTokens,
                latencyMs = seg.endToEndLatencyMs
            )
        }
    }

    fun speakTranslation() {
        val text = _localState.value.translatedText.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val bundle = engineRegistry.acquireActivePair(s.activePair, s.qualityProfile)
            bundle.ttsEngine.speak(text, s.targetLanguageCode, s.ttsSpeechRate)
        }
    }

    fun speakSource() {
        val text = _localState.value.inputText.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val srcLang = _localState.value.detectedSourceCode ?: s.sourceLanguageCode
            val bundle = engineRegistry.acquireActivePair(s.activePair, s.qualityProfile)
            bundle.ttsEngine.speak(text, srcLang, s.ttsSpeechRate)
        }
    }

    fun toggleFavorite() {
        val id = _localState.value.lastHistoryId ?: return
        val nextFav = !_localState.value.isFavorite
        viewModelScope.launch {
            historyDao.setFavorite(id, nextFav)
            _localState.value = _localState.value.copy(isFavorite = nextFav)
        }
    }

    fun dismissError() {
        _localState.value = _localState.value.copy(errorMessage = null)
    }
}

@Composable
fun TextTranslationRoute(
    viewModel: TextTranslationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uiLocale = Locale.getDefault().language.takeIf { it in setOf("ru", "en", "tr") } ?: "ru"
    var copiedBanner by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            copiedBanner = false
            viewModel.recognizeImageAndTranslate(context, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Language Pair Selector with Auto-detect & Swap
        LanguagePairSelectorBar(
            pair = state.pair,
            availableLanguages = state.availableLanguages,
            uiLocale = uiLocale,
            onSelectSource = viewModel::selectSourceLanguage,
            onSelectTarget = viewModel::selectTargetLanguage,
            onSwap = viewModel::swapLanguages,
            swapContentDescription = "Поменять языки местами",
            allowAutoDetect = true,
            detectedSourceCode = state.detectedSourceCode
        )

        // 2. Technical Domain Selector
        TechnicalDomainSelectorRow(
            domains = state.availableDomains,
            selectedDomainId = state.selectedDomain.id,
            uiLocale = uiLocale,
            onSelectDomain = viewModel::selectDomain
        )

        // 3. Engineering Pre-processor Toggles (Mode #3 specialized switches)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.enableOcrCleanup,
                onClick = viewModel::toggleOcrCleanup,
                label = { Text("Очистка OCR шильдиков") }
            )
            FilterChip(
                selected = state.enableUnitNormalization,
                onClick = viewModel::toggleUnitNormalization,
                label = { Text("Числа → СИ (5 MPa)") }
            )
            FilterChip(
                selected = state.enableImperialToSi,
                onClick = viewModel::toggleImperialToSi,
                label = { Text("Imperial → СИ (psi/in/°F)") }
            )
        }

        if (state.isProcessing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.errorMessage != null) {
            ErrorDiagnosticBanner(
                errorMessage = state.errorMessage!!,
                onRetry = if (state.inputText.isNotBlank()) ({ viewModel.translateText() }) else null,
                onDismiss = viewModel::dismissError
            )
        }

        // 4. Document / Specification / OCR Input Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = {
                        copiedBanner = false
                        viewModel.updateInput(it)
                    },
                    label = { Text("Технический документ / спецификация / текст с шильдика") },
                    placeholder = { Text("Введите/вставьте текст любой длины или отсканируйте фото документа/шильдика...") },
                    trailingIcon = {
                        if (state.inputText.isNotEmpty()) {
                            IconButton(onClick = {
                                copiedBanner = false
                                viewModel.clearAll()
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    },
                    minLines = 4,
                    maxLines = 10,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.inputText.length} симв.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.inputText.isNotBlank()) {
                        IconButton(onClick = viewModel::speakSource, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "Озвучить оригинал", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        enabled = !state.isProcessing,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Фото OCR")
                    }

                    OutlinedButton(
                        onClick = {
                            val clipText = clipboard.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                copiedBanner = false
                                viewModel.translateText(clipText)
                            } else {
                                viewModel.updateInput("")
                            }
                        },
                        enabled = !state.isProcessing,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Буфер")
                    }

                    Button(
                        onClick = {
                            copiedBanner = false
                            viewModel.translateText()
                        },
                        enabled = !state.isProcessing && state.inputText.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (state.isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Перевести", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // 5. Normalized & Protected Entities Preview (shown when pre-processors enriched the input)
        if (state.normalizedSourceText.isNotBlank() &&
            (state.normalizedSourceText != state.inputText.trim() || state.preservedTokens.isNotEmpty())
        ) {
            StreamingTextPanel(
                title = "Инженерная нормализация и защищённые коды/единицы",
                confirmedText = state.normalizedSourceText,
                unstablePartialText = "",
                isFinal = true,
                placeholderText = "",
                latencyBadgeText = if (state.preservedTokens.isNotEmpty()) {
                    "Защищено: ${state.preservedTokens.joinToString(", ")}"
                } else null
            )
        }

        // 6. Final Technical Translation Result Card
        val metaBadge = buildString {
            if (state.engineBackendName.isNotBlank()) append(state.engineBackendName)
            if (state.latencyMs > 0) {
                if (isNotEmpty()) append(" • ")
                append("${state.latencyMs} ms")
            }
        }.ifBlank { null }

        StreamingTextPanel(
            title = "Технический перевод (${state.pair.target.localizedName(uiLocale)})",
            confirmedText = state.translatedText,
            unstablePartialText = "",
            isFinal = true,
            placeholderText = "Здесь появится перевод документа с сохранением абзацев, единиц СИ, артикулов и терминов глоссария...",
            appliedTerms = state.appliedTerms,
            latencyBadgeText = metaBadge,
            actionsRow = if (state.translatedText.isNotBlank()) {
                {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(onClick = {
                                clipboard.setText(AnnotatedString(state.translatedText))
                                copiedBanner = true
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать перевод")
                            }
                            FilledTonalIconButton(onClick = viewModel::speakTranslation) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "Озвучить перевод")
                            }
                            if (state.lastHistoryId != null) {
                                FilledTonalIconButton(onClick = viewModel::toggleFavorite) {
                                    Icon(
                                        imageVector = if (state.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "В избранное"
                                    )
                                }
                            }
                        }
                        if (copiedBanner) {
                            Text(
                                text = "✓ Скопировано",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else null
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}
