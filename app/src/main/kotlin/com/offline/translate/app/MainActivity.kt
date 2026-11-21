package com.offline.translate.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.offline.translate.engine.api.StreamingTranslationPipeline
import com.offline.translate.engine.asr.AndroidSystemAsrEngine
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

enum class BottomNavDestination(
    val route: String,
    val labelResId: Int,
    val icon: ImageVector
) {
    LIVE("live", R.string.nav_live, Icons.Default.Translate),
    CONVERSATION("conversation", R.string.nav_conversation, Icons.Default.People),
    TEXT_OCR("text", R.string.nav_text, Icons.Default.DocumentScanner),
    GLOSSARY("glossary", R.string.nav_glossary, Icons.Default.Book)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: AppSettingsDataStore

    @Inject
    lateinit var benchmarkRunner: HardwareBenchmarkRunner

    @Inject
    lateinit var pipeline: StreamingTranslationPipeline

    @Inject
    lateinit var realAsrEngine: AndroidSystemAsrEngine

    private var isPttKeyHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(
                initialValue = com.offline.translate.core.datastore.UserSettings()
            )

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
                MainTranslatorScaffold()
            }
        }
    }

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
            realAsrEngine.startStream(s.sourceLanguageCode)
        }
    }

    private fun triggerHandsFreePttStop() {
        lifecycleScope.launch {
            realAsrEngine.finishStream()
            pipeline.setRecordingState(false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTranslatorScaffold() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: BottomNavDestination.LIVE.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate("history") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = stringResource(R.string.nav_history),
                            tint = if (currentRoute == "history") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = {
                            navController.navigate("settings") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.nav_settings),
                            tint = if (currentRoute == "settings") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar {
                BottomNavDestination.entries.forEach { dest ->
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
                        label = {
                            Text(
                                text = stringResource(dest.labelResId),
                                maxLines = 1,
                                fontWeight = if (currentRoute == dest.route) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomNavDestination.LIVE.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomNavDestination.LIVE.route) { LiveTranslationRoute() }
            composable(BottomNavDestination.CONVERSATION.route) { ConversationRoute() }
            composable(BottomNavDestination.TEXT_OCR.route) { TextTranslationRoute() }
            composable(BottomNavDestination.GLOSSARY.route) { GlossaryRoute() }
            composable("history") { HistoryRoute() }
            composable("settings") { SettingsRoute() }
        }
    }
}
