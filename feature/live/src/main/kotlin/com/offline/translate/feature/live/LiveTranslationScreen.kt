package com.offline.translate.feature.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offline.translate.core.designsystem.AudioLevelIndicator
import com.offline.translate.core.designsystem.ErrorDiagnosticBanner
import com.offline.translate.core.designsystem.LanguagePairSelectorBar
import com.offline.translate.core.designsystem.StreamingTextPanel
import com.offline.translate.core.designsystem.TechnicalDomainSelectorRow
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.TechnicalDomain
import java.util.Locale

@Composable
fun LiveTranslationRoute(
    viewModel: LiveTranslationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
    }

    LiveTranslationScreen(
        state = state,
        onInputChanged = viewModel::updateInputText,
        onClear = viewModel::clearAll,
        onTranslateClick = viewModel::translateCurrentText,
        onToggleRecord = {
            if (state.isRecording) {
                viewModel.stopRecording()
            } else {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    viewModel.startRecording()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        onSpeakTranslation = viewModel::speakTranslatedResult,
        onToggleFavorite = viewModel::toggleFavoriteCurrentResult,
        onDismissError = viewModel::dismissError,
        onSelectSource = viewModel::selectSourceLanguage,
        onSelectTarget = viewModel::selectTargetLanguage,
        onSwapLanguages = viewModel::swapLanguages,
        onSelectDomain = viewModel::selectDomain
    )
}

@Composable
fun LiveTranslationScreen(
    state: LiveTranslationUiState,
    onInputChanged: (String) -> Unit,
    onClear: () -> Unit,
    onTranslateClick: () -> Unit,
    onToggleRecord: () -> Unit,
    onSpeakTranslation: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismissError: () -> Unit,
    onSelectSource: (Language) -> Unit,
    onSelectTarget: (Language) -> Unit,
    onSwapLanguages: () -> Unit,
    onSelectDomain: (TechnicalDomain) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiLocale = Locale.getDefault().language.takeIf { it in setOf("ru", "en", "tr") } ?: "ru"
    val clipboard = LocalClipboardManager.current
    var copiedFeedback by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Language Pair Selector (Auto-detect, RU, EN, TR + Swap)
        LanguagePairSelectorBar(
            pair = state.pair,
            availableLanguages = state.availableLanguages,
            uiLocale = uiLocale,
            onSelectSource = onSelectSource,
            onSelectTarget = onSelectTarget,
            onSwap = onSwapLanguages,
            swapContentDescription = "Поменять языки местами",
            allowAutoDetect = true,
            detectedSourceCode = state.detectedSourceCode
        )

        // 2. Technical Domain Selector
        TechnicalDomainSelectorRow(
            domains = state.availableDomains,
            selectedDomainId = state.selectedDomain.id,
            uiLocale = uiLocale,
            onSelectDomain = onSelectDomain
        )

        // 3. Progress bar when translation or speech recognition is running
        if (state.isTranslating || state.isRecording) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 4. Real Diagnostic Error Banner (never hidden!)
        if (state.errorMessage != null) {
            ErrorDiagnosticBanner(
                errorMessage = state.errorMessage,
                onRetry = if (state.inputText.isNotBlank()) onTranslateClick else null,
                onDismiss = onDismissError
            )
        }

        // 5. Source Input Card (Text input + Clear + Paste + Translate + Voice Mic)
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
                        copiedFeedback = false
                        onInputChanged(it)
                    },
                    label = {
                        val srcLabel = if (state.pair.source.isAuto) {
                            "Исходный текст (Авто: RU / EN / TR)"
                        } else {
                            "Исходный текст (${state.pair.source.localizedName(uiLocale)})"
                        }
                        Text(srcLabel)
                    },
                    placeholder = {
                        Text("Введите текст или нажмите кнопку микрофона...")
                    },
                    trailingIcon = {
                        if (state.inputText.isNotEmpty()) {
                            IconButton(onClick = {
                                copiedFeedback = false
                                onClear()
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить поле")
                            }
                        }
                    },
                    minLines = 3,
                    maxLines = 7,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.isRecording) {
                    AudioLevelIndicator(
                        rmsDb = state.audioRmsDb,
                        isSpeechDetected = state.isSpeechDetected
                    )
                }

                // Ergonomic action row for one-handed smartphone operation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val clip = clipboard.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                onInputChanged(clip)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Вставить", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Вставить")
                    }

                    Button(
                        onClick = {
                            copiedFeedback = false
                            onTranslateClick()
                        },
                        enabled = !state.isTranslating && state.inputText.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (state.isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Перевод...")
                        } else {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Перевести", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    val micDesc = if (state.isRecording) "Остановить запись голоса" else "Голосовой ввод"
                    FloatingActionButton(
                        onClick = onToggleRecord,
                        shape = CircleShape,
                        containerColor = if (state.isRecording) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                        modifier = Modifier
                            .size(50.dp)
                            .semantics { contentDescription = micDesc }
                    ) {
                        Icon(
                            imageVector = if (state.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = micDesc,
                            tint = if (state.isRecording) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                }
            }
        }

        // 6. Large High-Contrast Translation Result Area with Copy, Speak (TTS), and Favorite buttons
        val backendBadge = buildString {
            if (state.engineBackendName.isNotBlank()) append(state.engineBackendName)
            if (state.endToEndLatencyMs > 0) {
                if (isNotEmpty()) append(" • ")
                append("${state.endToEndLatencyMs} ms")
            }
        }.ifBlank { null }

        StreamingTextPanel(
            title = "Перевод (${state.pair.target.localizedName(uiLocale)})",
            confirmedText = state.translatedConfirmedText,
            unstablePartialText = state.translatedUnstablePartialText,
            isFinal = state.isFinalSegment,
            placeholderText = "Здесь отобразится перевод с сохранением структуры текста, чисел СИ и технических терминов...",
            appliedTerms = state.appliedTerms,
            latencyBadgeText = backendBadge,
            actionsRow = if (state.translatedConfirmedText.isNotBlank()) {
                {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(onClick = {
                                clipboard.setText(AnnotatedString(state.translatedConfirmedText))
                                copiedFeedback = true
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать перевод")
                            }
                            FilledTonalIconButton(onClick = onSpeakTranslation) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "Озвучить перевод")
                            }
                            if (state.lastHistoryId != null) {
                                FilledTonalIconButton(onClick = onToggleFavorite) {
                                    Icon(
                                        imageVector = if (state.isCurrentFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "В избранное"
                                    )
                                }
                            }
                        }
                        if (copiedFeedback) {
                            Text(
                                text = "✓ Скопировано",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else if (state.preservedTokens.isNotEmpty()) {
                            Text(
                                text = "Сохранено: ${state.preservedTokens.joinToString(", ")}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else null
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}
