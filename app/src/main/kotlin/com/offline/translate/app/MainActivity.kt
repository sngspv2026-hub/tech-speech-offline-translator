package com.offline.translate.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.datastore.AppThemeMode
import com.offline.translate.core.designsystem.TechTranslatorTheme
import com.offline.translate.engine.api.HardwareBenchmarkRunner
import com.offline.translate.engine.api.LanguagePackManager
import com.offline.translate.engine.api.StreamingTranslationPipeline
import com.offline.translate.engine.asr.MockAsrEngine
import com.offline.translate.feature.conversation.ConversationRoute
import com.offline.translate.feature.glossary.GlossaryRoute
import com.offline.translate.feature.history.HistoryRoute
import com.offline.translate.feature.live.LiveTranslationRoute
import com.offline.translate.feature.settings.SettingsRoute
import com.offline.translate.feature.text.TextTranslationRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TopLevelDestination(
    val route: String,
    val labelResId: Int,
    val icon: ImageVector
) {
    LIVE("live", R.string.nav_live, Icons.Default.Mic),
    CONVERSATION("conversation", R.string.nav_conversation, Icons.Default.People),
    TEXT("text", R.string.nav_text, Icons.Default.TextFields),
    GLOSSARY("glossary", R.string.nav_glossary, Icons.Default.Book),
    HISTORY("history", R.string.nav_history, Icons.Default.History),
    SETTINGS("settings", R.string.nav_settings, Icons.Default.Settings)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: AppSettingsDataStore

    @Inject
    lateinit var packManager: LanguagePackManager

    @Inject
    lateinit var benchmarkRunner: HardwareBenchmarkRunner

    @Inject
    lateinit var pipeline: StreamingTranslationPipeline

    @Inject
    lateinit var mockAsrEngine: MockAsrEngine

    private var isPttKeyHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(
                initialValue = com.offline.translate.core.datastore.UserSettings()
            )
            val scope = rememberCoroutineScope()

            LaunchedEffect(settings.benchmarkResult) {
                if (settings.benchmarkResult == null) {
                    val result = benchmarkRunner.runMicroBenchmark()
                    settingsDataStore.saveBenchmarkResult(result)
                }
            }

            val darkTheme = when (settings.themeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                AppThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            TechTranslatorTheme(darkTheme = darkTheme) {
                if (!settings.onboardingCompleted) {
                    FirstLaunchOnboardingDialog(
                        onInstallDefaultPacks = {
                            scope.launch {
                                packManager.enqueueDownload("org.offline.translate.pack.ru_en_core")
                                packManager.enqueueDownload("org.offline.translate.pack.tr_en_pivot_ru")
                                settingsDataStore.completeOnboarding()
                            }
                        },
                        onContinueWithDevMocks = {
                            scope.launch {
                                settingsDataStore.setDevMockMode(true)
                                settingsDataStore.completeOnboarding()
                            }
                        }
                    )
                }
                MainTranslatorScaffold()
            }
        }
    }

    /**
     * Hands-Free Push-to-Talk (PTT) via Bluetooth headset button (KEYCODE_HEADSETHOOK / MEDIA_PLAY_PAUSE)
     * or long-press Volume Down in noisy industrial environments / when wearing gloves.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (!isPttKeyHeld) {
                isPttKeyHeld = true
                triggerHandsFreePttStart()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (isPttKeyHeld) {
                isPttKeyHeld = false
                triggerHandsFreePttStop()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun triggerHandsFreePttStart() {
        lifecycleScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            pipeline.setRecordingState(true)
            mockAsrEngine.startStream(s.sourceLanguageCode)
        }
    }

    private fun triggerHandsFreePttStop() {
        lifecycleScope.launch {
            mockAsrEngine.finishStream()
            pipeline.setRecordingState(false)
        }
    }
}

@Composable
private fun FirstLaunchOnboardingDialog(
    onInstallDefaultPacks: () -> Unit,
    onContinueWithDevMocks: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onContinueWithDevMocks,
        title = { Text(stringResource(R.string.onboarding_title)) },
        text = { Text(stringResource(R.string.onboarding_body)) },
        confirmButton = {
            Button(onClick = onInstallDefaultPacks) {
                Text(stringResource(R.string.onboarding_install_packs))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onContinueWithDevMocks) {
                Text(stringResource(R.string.onboarding_use_dev_mocks))
            }
        }
    )
}

@Composable
private fun MainTranslatorScaffold() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: TopLevelDestination.LIVE.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelResId)) },
                        label = { Text(stringResource(dest.labelResId)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.LIVE.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopLevelDestination.LIVE.route) { LiveTranslationRoute() }
            composable(TopLevelDestination.CONVERSATION.route) { ConversationRoute() }
            composable(TopLevelDestination.TEXT.route) { TextTranslationRoute() }
            composable(TopLevelDestination.GLOSSARY.route) { GlossaryRoute() }
            composable(TopLevelDestination.HISTORY.route) { HistoryRoute() }
            composable(TopLevelDestination.SETTINGS.route) { SettingsRoute() }
        }
    }
}
