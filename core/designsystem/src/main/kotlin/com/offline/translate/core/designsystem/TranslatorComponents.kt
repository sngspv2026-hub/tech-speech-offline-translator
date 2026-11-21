package com.offline.translate.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.offline.translate.core.model.AppliedTermMatch
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.TechnicalDomain

/**
 * Commercial Material 3 Language Pair Selector Bar supporting Auto-Detect ("auto"), RU, EN, TR,
 * detected language badge, and one-tap Swap.
 */
@Composable
fun LanguagePairSelectorBar(
    pair: LanguagePair,
    availableLanguages: List<Language>,
    uiLocale: String,
    onSelectSource: (Language) -> Unit,
    onSelectTarget: (Language) -> Unit,
    onSwap: () -> Unit,
    swapContentDescription: String,
    allowAutoDetect: Boolean = true,
    detectedSourceCode: String? = null,
    modifier: Modifier = Modifier
) {
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var targetMenuExpanded by remember { mutableStateOf(false) }

    val sourceOptions = remember(availableLanguages, allowAutoDetect) {
        if (allowAutoDetect) {
            listOf(Language.AUTO) + availableLanguages.filter { !it.isAuto }
        } else {
            availableLanguages.filter { !it.isAuto }
        }
    }
    val targetOptions = remember(availableLanguages) {
        availableLanguages.filter { !it.isAuto }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.weight(1f)) {
                FilledTonalButton(
                    onClick = { sourceMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    val label = if (pair.source.isAuto) {
                        val detSuffix = detectedSourceCode?.takeIf { it.isNotBlank() && it != "auto" }
                            ?.let { " • ${it.uppercase()}" }.orEmpty()
                        "${pair.source.localizedName(uiLocale)}$detSuffix"
                    } else {
                        "${pair.source.localizedName(uiLocale)} (${pair.source.code.uppercase()})"
                    }
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                DropdownMenu(
                    expanded = sourceMenuExpanded,
                    onDismissRequest = { sourceMenuExpanded = false }
                ) {
                    sourceOptions.forEach { lang ->
                        DropdownMenuItem(
                            text = {
                                val title = if (lang.isAuto) {
                                    "✨ ${lang.localizedName(uiLocale)}"
                                } else {
                                    "${lang.localizedName(uiLocale)} (${lang.nativeName})"
                                }
                                Text(title)
                            },
                            onClick = {
                                sourceMenuExpanded = false
                                onSelectSource(lang)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            FilledTonalIconButton(
                onClick = onSwap,
                shape = CircleShape,
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = swapContentDescription }
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = swapContentDescription,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(modifier = Modifier.weight(1f)) {
                FilledTonalButton(
                    onClick = { targetMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "${pair.target.localizedName(uiLocale)} (${pair.target.code.uppercase()})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                DropdownMenu(
                    expanded = targetMenuExpanded,
                    onDismissRequest = { targetMenuExpanded = false }
                ) {
                    targetOptions.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text("${lang.localizedName(uiLocale)} (${lang.nativeName})") },
                            onClick = {
                                targetMenuExpanded = false
                                onSelectTarget(lang)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays real error messages clearly without hiding failures behind fake data.
 */
@Composable
fun ErrorDiagnosticBanner(
    errorMessage: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            if (onRetry != null || onDismiss != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) {
                            Text("Закрыть", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Повторить", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Visualizes real-time microphone RMS level (-60 dBFS to 0 dBFS) and VAD speech detection status.
 */
@Composable
fun AudioLevelIndicator(
    rmsDb: Float,
    isSpeechDetected: Boolean,
    modifier: Modifier = Modifier
) {
    val normalized = ((rmsDb + 60f) / 60f).coerceIn(0.04f, 1f)
    val animatedFraction by animateFloatAsState(targetValue = normalized, label = "rmsLevel")
    val barColor = if (isSpeechDetected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(barColor)
        )
    }
}

/**
 * Displays streaming or final translation text with high readability, applied glossary badges,
 * and optional bottom action buttons (Copy, Speak, Favorite).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamingTextPanel(
    title: String,
    confirmedText: String,
    unstablePartialText: String,
    isFinal: Boolean,
    placeholderText: String,
    appliedTerms: List<AppliedTermMatch> = emptyList(),
    latencyBadgeText: String? = null,
    actionsRow: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.onSurface
    val partialColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (latencyBadgeText != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = latencyBadgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (confirmedText.isBlank() && unstablePartialText.isBlank()) {
                Text(
                    text = placeholderText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                val annotated = buildAnnotatedString {
                    if (confirmedText.isNotBlank()) {
                        withStyle(
                            SpanStyle(
                                color = primaryColor,
                                fontWeight = if (isFinal) FontWeight.Medium else FontWeight.Normal
                            )
                        ) {
                            append(confirmedText.trim())
                        }
                    }
                    if (unstablePartialText.isNotBlank()) {
                        if (confirmedText.isNotBlank()) append(" ")
                        withStyle(
                            SpanStyle(
                                color = partialColor,
                                fontStyle = FontStyle.Italic,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(unstablePartialText.trim())
                        }
                        withStyle(SpanStyle(color = partialColor, fontWeight = FontWeight.Bold)) {
                            append(" ▍")
                        }
                    }
                }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (appliedTerms.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    appliedTerms.forEach { term ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = "${term.sourceTerm} → ${term.targetTerm}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }
            }

            if (actionsRow != null) {
                Spacer(modifier = Modifier.height(10.dp))
                actionsRow()
            }
        }
    }
}

@Composable
fun TechnicalDomainSelectorRow(
    domains: List<TechnicalDomain>,
    selectedDomainId: String,
    uiLocale: String,
    onSelectDomain: (TechnicalDomain) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        domains.forEach { domain ->
            FilterChip(
                selected = domain.id == selectedDomainId,
                onClick = { onSelectDomain(domain) },
                label = { Text(domain.localizedName(uiLocale), maxLines = 1) }
            )
        }
    }
}
