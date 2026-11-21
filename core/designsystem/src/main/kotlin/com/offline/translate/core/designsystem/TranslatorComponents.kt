package com.offline.translate.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.offline.translate.core.model.AppliedTermMatch
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.TechnicalDomain

/**
 * Quick language pair selector with one-tap Swap and full TalkBack support.
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
    modifier: Modifier = Modifier
) {
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var targetMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { sourceMenuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${pair.source.localizedName(uiLocale)} (${pair.source.code.uppercase()})",
                    maxLines = 1
                )
            }
            DropdownMenu(
                expanded = sourceMenuExpanded,
                onDismissRequest = { sourceMenuExpanded = false }
            ) {
                availableLanguages.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text("${lang.localizedName(uiLocale)} (${lang.nativeName})") },
                        onClick = {
                            sourceMenuExpanded = false
                            onSelectSource(lang)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSwap,
            modifier = Modifier.semantics { contentDescription = swapContentDescription }
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = swapContentDescription,
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { targetMenuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${pair.target.localizedName(uiLocale)} (${pair.target.code.uppercase()})",
                    maxLines = 1
                )
            }
            DropdownMenu(
                expanded = targetMenuExpanded,
                onDismissRequest = { targetMenuExpanded = false }
            ) {
                availableLanguages.forEach { lang ->
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

/**
 * Visualizes real-time microphone RMS level (-60 dBFS to 0 dBFS) and VAD speech detection status.
 */
@Composable
fun AudioLevelIndicator(
    rmsDb: Float,
    isSpeechDetected: Boolean,
    modifier: Modifier = Modifier
) {
    val normalized = ((rmsDb + 60f) / 60f).coerceIn(0.05f, 1f)
    val animatedFraction by animateFloatAsState(targetValue = normalized, label = "rmsLevel")
    val barColor = if (isSpeechDetected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}

/**
 * Displays streaming text where confirmed text is rendered in solid high-contrast style
 * and unstable partial text is visually distinct (italic, underlined, muted color).
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
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.onSurface
    val partialColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (latencyBadgeText != null) {
                    Text(
                        text = latencyBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                Spacer(modifier = Modifier.height(8.dp))
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
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TechnicalDomainSelectorRow(
    domains: List<TechnicalDomain>,
    selectedDomainId: String,
    uiLocale: String,
    onSelectDomain: (TechnicalDomain) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        domains.forEach { domain ->
            FilterChip(
                selected = domain.id == selectedDomainId,
                onClick = { onSelectDomain(domain) },
                label = { Text(domain.localizedName(uiLocale)) }
            )
        }
    }
}
