package com.offline.translate.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.datastore.TtsEnginePreference
import com.offline.translate.core.datastore.UserSettings
import com.offline.translate.core.model.LanguagePackState
import com.offline.translate.core.model.PackInstallStatus
import com.offline.translate.core.model.QualityProfile
import com.offline.translate.engine.api.EngineRegistry
import com.offline.translate.engine.api.HardwareBenchmarkRunner
import com.offline.translate.engine.api.LanguagePackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val packs: List<LanguagePackState> = emptyList(),
    val estimatedRamMb: Int = 145
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: AppSettingsDataStore,
    private val packManager: LanguagePackManager,
    private val engineRegistry: EngineRegistry,
    private val benchmarkRunner: HardwareBenchmarkRunner
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settingsDataStore.settingsFlow,
        packManager.packsState,
        engineRegistry.estimatedMemoryUsageMb
    ) { settings, packs, ramMb ->
        SettingsUiState(settings = settings, packs = packs, estimatedRamMb = ramMb)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun selectProfile(profile: QualityProfile) {
        viewModelScope.launch {
            settingsDataStore.setQualityProfile(profile)
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setTtsEnabled(enabled)
        }
    }

    fun selectTtsEngine(pref: TtsEnginePreference) {
        viewModelScope.launch {
            settingsDataStore.setTtsEnginePreference(pref)
        }
    }

    fun setDevMockMode(enabled: Boolean) {
        viewModelScope.launch {
            engineRegistry.setDevMockMode(enabled)
            settingsDataStore.setDevMockMode(enabled)
        }
    }

    fun installPack(packId: String) {
        viewModelScope.launch {
            packManager.enqueueDownload(packId)
        }
    }

    fun uninstallPack(packId: String) {
        viewModelScope.launch {
            packManager.uninstallPack(packId)
        }
    }

    fun runHardwareBenchmark() {
        viewModelScope.launch {
            val res = benchmarkRunner.runMicroBenchmark()
            settingsDataStore.saveBenchmarkResult(res)
        }
    }
}

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Настройки и Языковые пакеты", style = MaterialTheme.typography.headlineSmall)

        // Quality / Speed Profile
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Профиль скорости и качества (Redmi Note Pro 5G)", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = state.settings.qualityProfile == profile,
                            onClick = { viewModel.selectProfile(profile) },
                            label = { Text("${profile.name} (<${profile.targetEndToEndLatencyMs}ms)") }
                        )
                    }
                }
                Text(
                    text = "Текущее потребление RAM активной парой: ${state.estimatedRamMb} МБ (бюджет < 1500 МБ)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        // TTS & Dev Mode
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Озвучка перевода (Офлайн TTS)", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = state.settings.ttsEnabled,
                        onCheckedChange = viewModel::setTtsEnabled
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TtsEnginePreference.entries.forEach { pref ->
                        FilterChip(
                            selected = state.settings.ttsEnginePreference == pref,
                            onClick = { viewModel.selectTtsEngine(pref) },
                            label = { Text(pref.name) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dev-режим с моками движков (тест UI без весов)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.settings.devMockMode,
                        onCheckedChange = viewModel::setDevMockMode
                    )
                }
            }
        }

        // Hardware Benchmark card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Аппаратный бенчмарк (CPU ARM NEON int8 vs NNAPI)", style = MaterialTheme.typography.bodyLarge)
                val bench = state.settings.benchmarkResult
                if (bench != null) {
                    Text(
                        text = "Устройство: ${bench.deviceModel} (${bench.socName}, ${bench.totalRamMb} MB RAM)\n" +
                            "Бэкенд: ${bench.selectedMtBackend.name} (${bench.optimalCpuThreads} потоков)\n" +
                            "ASR RTF: ${bench.cpuAsrRtf} • MT скорость: ${bench.cpuMtTokensPerSec} tok/s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text("Бенчмарк запускается автоматически при старте или вручную ниже.", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = viewModel::runHardwareBenchmark) {
                    Text("Запустить тест производительности")
                }
            }
        }

        // Language Packs section
        Text("Управление языковыми пакетами (Офлайн)", style = MaterialTheme.typography.headlineSmall)

        state.packs.forEach { packState ->
            val m = packState.manifest
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = m.displayName["ru"] ?: m.packId,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val sizeMb = m.hardwareRequirements.diskSizeBytes / (1024 * 1024)
                    Text(
                        text = "v${m.version} • $sizeMb МБ • Лицензия: ${m.license.spdxId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!m.license.commercialUseAllowed) {
                        Text(
                            text = "⚠ ${m.license.warningNote}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (packState.status == PackInstallStatus.DOWNLOADING || packState.status == PackInstallStatus.VERIFYING) {
                        LinearProgressIndicator(
                            progress = { packState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (packState.status == PackInstallStatus.INSTALLED) {
                            OutlinedButton(onClick = { viewModel.uninstallPack(m.packId) }) {
                                Text("Удалить пакет")
                            }
                        } else {
                            Button(onClick = { viewModel.installPack(m.packId) }) {
                                Text("Установить офлайн-пакет")
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
