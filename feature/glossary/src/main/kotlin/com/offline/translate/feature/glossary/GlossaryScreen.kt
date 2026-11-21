package com.offline.translate.feature.glossary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.offline.translate.core.database.GlossaryDao
import com.offline.translate.core.database.GlossaryTermEntity
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.designsystem.TechnicalDomainSelectorRow
import com.offline.translate.core.model.TechnicalDomain
import com.offline.translate.engine.api.TermAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GlossaryViewModel @Inject constructor(
    private val glossaryDao: GlossaryDao,
    private val termAdapter: TermAdapter,
    private val settingsDataStore: AppSettingsDataStore
) : ViewModel() {

    val terms: StateFlow<List<GlossaryTermEntity>> = glossaryDao.observeAllTerms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addUserTerm(sourceTerm: String, targetTerm: String, domainId: String) {
        if (sourceTerm.isBlank() || targetTerm.isBlank()) return
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            glossaryDao.upsert(
                GlossaryTermEntity(
                    sourceLang = s.sourceLanguageCode,
                    targetLang = s.targetLanguageCode,
                    domainId = domainId,
                    sourceTerm = sourceTerm.trim(),
                    targetTerm = targetTerm.trim(),
                    isUserDefined = true,
                    priority = 100
                )
            )
        }
    }

    fun deleteTerm(id: Long) {
        viewModelScope.launch {
            glossaryDao.deleteById(id)
        }
    }

    fun importFromCsvTbxOrShareCode(content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            when {
                trimmed.startsWith("TPKGLOSS1:") -> termAdapter.importCompactShareCode(trimmed, isUserDefined = true)
                trimmed.startsWith("<") -> termAdapter.importTbx(trimmed, isUserDefined = true)
                else -> termAdapter.importCsv(trimmed, isUserDefined = true)
            }
        }
    }

    suspend fun exportCompactQrPayload(domainId: String? = null): String =
        termAdapter.exportCompactShareCode(domainId = domainId, userOnly = false)
}

@Composable
fun GlossaryRoute(
    viewModel: GlossaryViewModel = hiltViewModel()
) {
    val terms by viewModel.terms.collectAsStateWithLifecycle()
    var selectedDomain by remember { mutableStateOf(TechnicalDomain.IT_PROGRAMMING) }
    var srcInput by remember { mutableStateOf("") }
    var tgtInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Технические глоссарии (CSV / TBX / QR-пакеты)",
            style = MaterialTheme.typography.headlineSmall
        )

        TechnicalDomainSelectorRow(
            domains = TechnicalDomain.BUILT_IN_DOMAINS,
            selectedDomainId = selectedDomain.id,
            uiLocale = "ru",
            onSelectDomain = { selectedDomain = it }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = srcInput,
                onValueChange = { srcInput = it },
                label = { Text("Исходный термин (+ASR Hotword)") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = tgtInput,
                onValueChange = { tgtInput = it },
                label = { Text("Перевод (принудит.)") },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    viewModel.addUserTerm(srcInput, tgtInput, selectedDomain.id)
                    srcInput = ""
                    tgtInput = ""
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Добавить")
            }
            OutlinedButton(
                onClick = {
                    val clip = clipboard.getText()?.text
                    if (!clip.isNullOrBlank()) {
                        viewModel.importFromCsvTbxOrShareCode(clip)
                        statusMessage = "Импортировано из буфера (CSV / TBX / QR-код)"
                    }
                }
            ) {
                Text("Импорт")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val code = viewModel.exportCompactQrPayload(selectedDomain.id)
                        clipboard.setText(AnnotatedString(code))
                        statusMessage = "QR-код глоссария (${code.take(22)}...) скопирован"
                    }
                }
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null)
                Text(" QR-код")
            }
        }

        if (statusMessage.isNotBlank()) {
            Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(terms, key = { it.id }) { item ->
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
                                text = "${item.sourceTerm} → ${item.targetTerm}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val sourceTag = if (item.isUserDefined) "Пользовательский (P=${item.priority}, ASR Boost 3.5)" else "Встроенный (P=${item.priority}, ASR Boost 2.0)"
                            Text(
                                text = "${item.sourceLang.uppercase()}→${item.targetLang.uppercase()} • ${item.domainId} • $sourceTag",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.deleteTerm(item.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить термин")
                        }
                    }
                }
            }
        }
    }
}
