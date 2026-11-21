package com.offline.translate.feature.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offline.translate.core.designsystem.AudioLevelIndicator
import com.offline.translate.core.designsystem.LanguagePairSelectorBar
import com.offline.translate.core.designsystem.StreamingTextPanel
import com.offline.translate.core.designsystem.TechnicalDomainSelectorRow
import com.offline.translate.core.model.RoutingMode
import com.offline.translate.core.model.ThermalLevel
import java.util.Locale

@Composable
fun LiveTranslationRoute(
    viewModel: LiveTranslationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LiveTranslationScreen(
        state = state,
        onToggleRecord = viewModel::toggleRecording,
        onSelectSource = viewModel::selectSourceLanguage,
        onSelectTarget = viewModel::selectTargetLanguage,
        onSwapLanguages = viewModel::swapLanguages,
        onSelectDomain = viewModel::selectDomain
    )
}

@Composable
fun LiveTranslationScreen(
    state: LiveTranslationUiState,
    onToggleRecord: () -> Unit,
    onSelectSource: (com.offline.translate.core.model.Language) -> Unit,
    onSelectTarget: (com.offline.translate.core.model.Language) -> Unit,
    onSwapLanguages: () -> Unit,
    onSelectDomain: (com.offline.translate.core.model.TechnicalDomain) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiLocale = Locale.getDefault().language.takeIf { it in setOf("ru", "en", "tr") } ?: "ru"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status bar: Profile, RAM budget monitor (<1.5 GB), Routing mode, Thermal status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = {},
                label = { Text("${state.effectiveProfile.name} • RAM ${state.estimatedRamMb} MB / 1500 MB") }
            )
            AssistChip(
                onClick = {},
                label = {
                    val routeLabel = if (state.routingUsed == RoutingMode.PIVOT) "PIVOT (via EN)" else "DIRECT"
                    Text(routeLabel)
                }
            )
            if (state.thermalLevel != ThermalLevel.NORMAL) {
                AssistChip(
                    onClick = {},
                    label = { Text("Thermal: ${state.thermalLevel.name}") }
                )
            }
        }

        // Quick language pair selector with one-tap Swap
        LanguagePairSelectorBar(
            pair = state.pair,
            availableLanguages = state.availableLanguages,
            uiLocale = uiLocale,
            onSelectSource = onSelectSource,
            onSelectTarget = onSelectTarget,
            onSwap = onSwapLanguages,
            swapContentDescription = "Поменять языки местами / Swap languages"
        )

        // Technical domain chips
        TechnicalDomainSelectorRow(
            domains = state.availableDomains,
            selectedDomainId = state.selectedDomain.id,
            uiLocale = uiLocale,
            onSelectDomain = onSelectDomain
        )

        // Audio level indicator (RMS dBFS + VAD status)
        AudioLevelIndicator(
            rmsDb = state.audioRmsDb,
            isSpeechDetected = state.isSpeechDetected
        )

        // Block 1: Original recognized speech (partial visually distinct from final)
        StreamingTextPanel(
            title = "${state.pair.source.localizedName(uiLocale)} (ASR)",
            confirmedText = state.sourceConfirmedText,
            unstablePartialText = state.sourceUnstablePartialText,
            isFinal = state.isFinalSegment,
            placeholderText = "Нажмите кнопку микрофона для начала синхронного распознавания...",
            latencyBadgeText = if (state.preservedTokens.isNotEmpty()) {
                "ID/Units: ${state.preservedTokens.joinToString(", ")}"
            } else null,
            modifier = Modifier.weight(1f, fill = false)
        )

        // Block 2: Translated speech (partial visually distinct from final + glossary badges + latency)
        StreamingTextPanel(
            title = "${state.pair.target.localizedName(uiLocale)} (MT • ${state.selectedDomain.localizedName(uiLocale)})",
            confirmedText = state.translatedConfirmedText,
            unstablePartialText = state.translatedUnstablePartialText,
            isFinal = state.isFinalSegment,
            placeholderText = "Перевод появится здесь в реальном времени (partial каждые ~300 мс)...",
            appliedTerms = state.appliedTerms,
            latencyBadgeText = if (state.endToEndLatencyMs > 0) "${state.endToEndLatencyMs} ms" else null,
            modifier = Modifier.weight(1f, fill = false)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Large central Record button with TalkBack support
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val recordButtonDesc = if (state.isRecording) {
                "Остановить синхронный перевод / Stop live translation"
            } else {
                "Начать синхронный перевод / Start live translation"
            }
            FloatingActionButton(
                onClick = onToggleRecord,
                shape = CircleShape,
                containerColor = if (state.isRecording) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .size(76.dp)
                    .semantics { contentDescription = recordButtonDesc }
            ) {
                Icon(
                    imageVector = if (state.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = recordButtonDesc,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (state.isRecording) "Идёт потоковый перевод (VAD активен)..." else "Нажмите для записи",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
