package com.offline.translate.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.datastore.AppThemeMode
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
import java.util.Locale
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

    fun selectTheme(mode: AppThemeMode) {
        viewModelScope.launch { settingsDataStore.setThemeMode(mode) }
    }

    fun setAutoDetectSource(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoDetectSource(enabled) }
    }

    fun selectProfile(profile: QualityProfile) {
        viewModelScope.launch { settingsDataStore.setQualityProfile(profile) }
    }

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setTtsEnabled(enabled) }
    }

    fun setTtsSpeechRate(rate: Float) {
        viewModelScope.launch { settingsDataStore.setTtsSpeechRate(rate) }
    }

    fun selectTtsEngine(pref: TtsEnginePreference) {
        viewModelScope.launch { settingsDataStore.setTtsEnginePreference(pref) }
    }

    fun installPack(packId: String) {
        viewModelScope.launch { packManager.enqueueDownload(packId) }
    }

    fun uninstallPack(packId: String) {
        viewModelScope.launch { packManager.uninstallPack(packId) }
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
        Text("Настройки приложения", style = MaterialTheme.typography.headlineSmall)

        // Theme & Auto-Detect Language
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Оформление и Язык", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val themeLabels = mapOf(
                        AppThemeMode.SYSTEM to "Системная",
                        AppThemeMode.LIGHT to "Светлая",
                        AppThemeMode.DARK to "Тёмная"
                    )
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.settings.themeMode == mode,
                            onClick = { viewModel.selectTheme(mode) },
                            label = { Text(themeLabels[mode] ?: mode.name) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Автоопределение исходного языка (RU / EN / TR)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.settings.autoDetectSource,
                        onCheckedChange = viewModel::setAutoDetectSource
                    )
                }
            }
        }

        // TTS Voice & Speed Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Автоозвучка голосового перевода (TTS)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.settings.ttsEnabled,
                        onCheckedChange = viewModel::setTtsEnabled
                    )
                }
                Text(
                    text = "Скорость речи: ${String.format(Locale.US, "%.2fx", state.settings.ttsSpeechRate)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = state.settings.ttsSpeechRate,
                    onValueChange = viewModel::setTtsSpeechRate,
                    valueRange = 0.5f..2.0f,
                    steps = 5
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.settings.ttsEnginePreference == TtsEnginePreference.ANDROID_SYSTEM_OFFLINE,
                        onClick = { viewModel.selectTtsEngine(TtsEnginePreference.ANDROID_SYSTEM_OFFLINE) },
                        label = { Text("Системный офлайн TTS") }
                    )
                    FilterChip(
                        selected = state.settings.ttsEnginePreference == TtsEnginePreference.PIPER_NEURAL,
                        onClick = { viewModel.selectTtsEngine(TtsEnginePreference.PIPER_NEURAL) },
                        label = { Text("Piper VITS Neural") }
                    )
                }
            }
        }

        // Quality / Speed Profile
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Профиль производительности", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QualityProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = state.settings.qualityProfile == profile,
                            onClick = { viewModel.selectProfile(profile) },
                            label = { Text("${profile.name} (<${profile.targetEndToEndLatencyMs}ms)") }
                        )
                    }
                }
                Text(
                    text = "Потребление RAM активной парой: ${state.estimatedRamMb} МБ (бюджет < 1500 МБ)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        // Hardware Benchmark card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Диагностика устройства", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                val bench = state.settings.benchmarkResult
                if (bench != null) {
                    Text(
                        text = "Устройство: ${bench.deviceModel} (${bench.socName}, ${bench.totalRamMb} MB RAM)\n" +
                            "Бэкенд: ${bench.selectedMtBackend.name} (${bench.optimalCpuThreads} потоков)\n" +
                            "ASR RTF: ${bench.cpuAsrRtf} • MT скорость: ${bench.cpuMtTokensPerSec} tok/s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = viewModel::runHardwareBenchmark) {
                    Text("Обновить замер производительности")
                }
            }
        }

        // Language Packs section (ML Kit On-Device Offline Models)
        Text("Офлайн языковые модели (RU / EN / TR)", style = MaterialTheme.typography.headlineSmall)

        state.packs.filter { it.manifest.license.commercialUseAllowed }.forEach { packState ->
            val m = packState.manifest
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = m.displayName["ru"] ?: m.packId,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "v${m.version} • Офлайн ML Kit NMT + Тех. глоссарий • ${m.license.spdxId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (packState.status == PackInstallStatus.FAILED && !packState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = "Ошибка: ${packState.errorMessage}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (packState.status == PackInstallStatus.DOWNLOADING || packState.status == PackInstallStatus.VERIFYING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Загрузка нейросетевых моделей ML Kit на устройство (RU + EN + TR)...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (packState.status == PackInstallStatus.INSTALLED) {
                            Text(
                                text = "✓ Установлено (офлайн готов)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                            OutlinedButton(onClick = { viewModel.uninstallPack(m.packId) }) {
                                Text("Удалить")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.installPack(m.packId) },
                                enabled = packState.status != PackInstallStatus.DOWNLOADING
                            ) {
                                Text("Скачать офлайн-модели (RU/EN/TR)")
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
