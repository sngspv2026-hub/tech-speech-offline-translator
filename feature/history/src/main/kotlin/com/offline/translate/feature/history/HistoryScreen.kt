package com.offline.translate.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyDao: TranslationHistoryDao
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val onlyFavoritesFlow = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyItems: StateFlow<List<TranslationHistoryEntity>> = combine(
        queryFlow,
        onlyFavoritesFlow
    ) { q, fav -> q to fav }
        .flatMapLatest { (q, fav) ->
            historyDao.searchHistory(q, fav)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) {
        queryFlow.value = query
    }

    fun setOnlyFavorites(onlyFavorites: Boolean) {
        onlyFavoritesFlow.value = onlyFavorites
    }

    fun toggleFavorite(item: TranslationHistoryEntity) {
        viewModelScope.launch {
            historyDao.setFavorite(item.id, !item.isFavorite)
        }
    }

    fun deleteItem(id: Long) {
        viewModelScope.launch {
            historyDao.deleteById(id)
        }
    }

    suspend fun exportHistoryCsv(): String {
        val all = historyDao.getAllSnapshot()
        val sb = StringBuilder()
        sb.appendLine("id,timestamp_ms,source_lang,target_lang,domain_id,routing,latency_ms,favorite,source_text,translated_text")
        for (row in all) {
            sb.appendLine(
                "${row.id},${row.timestampMs},${row.sourceLang},${row.targetLang},${row.domainId},${row.routingMode},${row.latencyMs},${row.isFavorite},\"${row.sourceText.replace("\"", "\"\"")}\",\"${row.translatedText.replace("\"", "\"\"")}\""
            )
        }
        return sb.toString()
    }

    /**
     * Generates a structured bilingual Technical Meeting / Inspection Protocol in Markdown format
     * with session entries and a consolidated table of enforced glossary terms.
     */
    suspend fun exportMeetingProtocolMarkdown(): String {
        val all = historyDao.getAllSnapshot()
        val sb = StringBuilder()
        sb.appendLine("# Технический протокол перевода (Offline TechSpeech Protocol)")
        sb.appendLine()
        sb.appendLine("- **Всего реплик:** ${all.size}")
        val domains = all.map { it.domainId }.distinct().joinToString(", ")
        sb.appendLine("- **Задействованные домены:** ${domains.ifBlank { "general" }}")
        sb.appendLine()
        sb.appendLine("## 1. Стенограмма и синхронный перевод")
        sb.appendLine()
        sb.appendLine("| # | Направление | Домен | Оригинал (нормализованный) | Перевод | Применённые термины | Латентность |")
        sb.appendLine("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
        all.forEachIndexed { idx, row ->
            val terms = row.appliedTermsSummary.ifBlank { "—" }
            sb.appendLine(
                "| ${idx + 1} | `${row.sourceLang.uppercase()}->${row.targetLang.uppercase()}` (${row.routingMode}) | `${row.domainId}` | ${row.sourceText} | ${row.translatedText} | $terms | ${row.latencyMs} ms |"
            )
        }
        return sb.toString()
    }
}

@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val items by viewModel.historyItems.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var onlyFav by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("История и Протоколы", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val md = viewModel.exportMeetingProtocolMarkdown()
                            clipboard.setText(AnnotatedString(md))
                        }
                    }
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Text(" Протокол .MD")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val csv = viewModel.exportHistoryCsv()
                            clipboard.setText(AnnotatedString(csv))
                        }
                    }
                ) {
                    Text("CSV")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.setSearchQuery(it)
                },
                label = { Text("Поиск по истории") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = onlyFav,
                onClick = {
                    onlyFav = !onlyFav
                    viewModel.setOnlyFavorites(onlyFav)
                },
                label = { Text("Избранное") }
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${row.sourceLang.uppercase()} → ${row.targetLang.uppercase()} • ${row.domainId} • ${row.latencyMs} ms",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(text = row.sourceText, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = row.translatedText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        IconButton(onClick = { viewModel.toggleFavorite(row) }) {
                            Icon(
                                imageVector = if (row.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "В избранное"
                            )
                        }
                        IconButton(onClick = { viewModel.deleteItem(row.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            }
        }
    }
}
