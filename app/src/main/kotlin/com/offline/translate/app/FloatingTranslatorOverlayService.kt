package com.offline.translate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.offline.translate.core.database.TranslationHistoryDao
import com.offline.translate.core.database.TranslationHistoryEntity
import com.offline.translate.core.datastore.AppSettingsDataStore
import com.offline.translate.core.model.Language
import com.offline.translate.core.model.LanguagePair
import com.offline.translate.core.model.SpeechRecognitionEvent
import com.offline.translate.engine.api.EngineRegistry
import com.offline.translate.engine.api.StreamingTranslationPipeline
import com.offline.translate.engine.asr.AndroidSystemAsrEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * System-wide Floating Translator Overlay (`SYSTEM_ALERT_WINDOW`) working on top of:
 * 1. Phone Calls (GSM / VoLTE / WhatsApp / Telegram calls) with Speakerphone toggle + live subtitles.
 * 2. Any Messenger (WhatsApp, Telegram, Viber, SMS) — reads chat text on screen via ScreenTextAccessibilityService
 *    and translates immediately in the floating window, plus one-tap clipboard translation.
 * 3. Any App on Screen (Browser, PDF, SCADA, Settings) — translates visible screen text in 1 tap.
 */
@AndroidEntryPoint
class FloatingTranslatorOverlayService : Service() {

    @Inject lateinit var settingsDataStore: AppSettingsDataStore
    @Inject lateinit var pipeline: StreamingTranslationPipeline
    @Inject lateinit var engineRegistry: EngineRegistry
    @Inject lateinit var realAsrEngine: AndroidSystemAsrEngine
    @Inject lateinit var historyDao: TranslationHistoryDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var isPanelExpanded = false
    private var isListeningCall = false
    private var speakerphoneEnabled = false

    private var statusTextView: TextView? = null
    private var sourceTextView: TextView? = null
    private var translatedTextView: TextView? = null
    private var listenButton: Button? = null
    private var speakerButton: Button? = null
    private var langButton: Button? = null

    private var asrCollectorJob: Job? = null
    private var lastTranslatedText: String = ""
    private var currentTargetLangCode: String = "ru"
    private var currentSpeechInputLang: String = "en"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannelIfNeeded()
        _isOverlayRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val preset = intent?.getStringExtra(EXTRA_PRESET) ?: PRESET_MESSENGER
        serviceScope.launch {
            val settings = settingsDataStore.settingsFlow.first()
            currentTargetLangCode = settings.targetLanguageCode
            currentSpeechInputLang = if (settings.sourceLanguageCode == "auto") "en" else settings.sourceLanguageCode
            if (bubbleView == null) {
                showFloatingBubble()
            }
            if (preset == PRESET_CALL) {
                expandOverlayPanel()
                setSpeakerphone(true)
            } else if (preset == PRESET_SCREEN) {
                expandOverlayPanel()
            }
        }

        startAsrCollector()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        asrCollectorJob?.cancel()
        setSpeakerphone(false)
        removeAllViews()
        serviceScope.cancel()
        _isOverlayRunning.value = false
        super.onDestroy()
    }

    private fun startAsrCollector() {
        asrCollectorJob?.cancel()
        asrCollectorJob = serviceScope.launch {
            realAsrEngine.events.collect { event ->
                if (!isListeningCall) return@collect
                if (event.errorMessage != null && event.fullText.isBlank()) {
                    statusTextView?.text = "⚠ ${event.errorMessage}"
                    isListeningCall = false
                    listenButton?.text = "🎙 Звонок/Голос"
                    return@collect
                }
                if (event.fullText.isNotBlank()) {
                    sourceTextView?.text = "Речь: ${event.fullText}"
                    translateAndDisplay(event.fullText, autoDetect = true, speakResult = false)
                }
                if (event.isFinal) {
                    isListeningCall = false
                    listenButton?.text = "🎙 Звонок/Голос"
                }
            }
        }
    }

    private fun showFloatingBubble() {
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()

        val bubble = TextView(this).apply {
            text = "🌐"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#006782"))
                setStroke((2 * density).toInt(), Color.parseColor("#B9EAFF"))
            }
            elevation = 10f
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (260 * density).toInt()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(bubble, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (isPanelExpanded) collapseOverlayPanel() else expandOverlayPanel()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager.addView(bubble, params)
    }

    private fun expandOverlayPanel() {
        if (panelView != null) return
        isPanelExpanded = true

        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            background = GradientDrawable().apply {
                cornerRadius = 18f * dp
                setColor(Color.parseColor("#F0151C22"))
                setStroke(1.dp(), Color.parseColor("#5AD2F4"))
            }
        }

        // Header Row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "TechSpeech • Экран и Звонки"
            setTextColor(Color.parseColor("#5AD2F4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val langBtn = Button(this).apply {
            text = "→ ${currentTargetLangCode.uppercase()}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                currentTargetLangCode = when (currentTargetLangCode) {
                    "ru" -> "en"
                    "en" -> "tr"
                    else -> "ru"
                }
                text = "→ ${currentTargetLangCode.uppercase()}"
                statusTextView?.text = "Целевой язык: ${currentTargetLangCode.uppercase()}"
            }
        }
        langButton = langBtn

        val closeBtn = Button(this).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { collapseOverlayPanel() }
        }
        header.addView(title)
        header.addView(langBtn)
        header.addView(closeBtn)
        root.addView(header)

        // Status line
        val statusTv = TextView(this).apply {
            text = "Нажмите «Экран/Чат» или «Звонок/Голос»"
            setTextColor(Color.parseColor("#BFC8CE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 4.dp(), 0, 6.dp())
        }
        statusTextView = statusTv
        root.addView(statusTv)

        // Primary Action Buttons Row 1: Screen/Messenger OCR-free read, Call/Voice Listen, Clipboard
        val actionsRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val screenBtn = Button(this).apply {
            text = "📱 Экран/Чат"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { translateVisibleScreen() }
        }
        val callBtn = Button(this).apply {
            text = "🎙 Звонок/Голос"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { toggleCallListening() }
        }
        listenButton = callBtn

        val clipBtn = Button(this).apply {
            text = "📋 Буфер"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f)
            setOnClickListener { translateClipboardText() }
        }
        actionsRow1.addView(screenBtn)
        actionsRow1.addView(callBtn)
        actionsRow1.addView(clipBtn)
        root.addView(actionsRow1)

        // Scrollable Output Area
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                160.dp()
            )
        }
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp(), 6.dp(), 4.dp(), 6.dp())
        }
        val srcTv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#A5B4BE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val tgtTv = TextView(this).apply {
            text = "Перевод экрана, чата мессенджера или телефонного разговора появится здесь..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 6.dp(), 0, 0)
        }
        sourceTextView = srcTv
        translatedTextView = tgtTv
        textBox.addView(srcTv)
        textBox.addView(tgtTv)
        scroll.addView(textBox)
        root.addView(scroll)

        // Bottom Utility Row: Speakerphone (for GSM calls), TTS Speak, Copy, Stop Overlay
        val actionsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val spkBtn = Button(this).apply {
            text = if (speakerphoneEnabled) "🔊 Громкая: ВКЛ" else "🔈 Громкая"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { setSpeakerphone(!speakerphoneEnabled) }
        }
        speakerButton = spkBtn

        val ttsBtn = Button(this).apply {
            text = "🗣 Озвучить"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
            setOnClickListener { speakLastResult() }
        }
        val copyBtn = Button(this).apply {
            text = "Копир."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f)
            setOnClickListener {
                if (lastTranslatedText.isNotBlank()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("TechSpeech", lastTranslatedText))
                    statusTextView?.text = "✓ Перевод скопирован в буфер"
                }
            }
        }
        actionsRow2.addView(spkBtn)
        actionsRow2.addView(ttsBtn)
        actionsRow2.addView(copyBtn)
        root.addView(actionsRow2)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 70.dp()
        }

        panelView = root
        windowManager.addView(root, params)
    }

    private fun collapseOverlayPanel() {
        isPanelExpanded = false
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
    }

    private fun translateVisibleScreen() {
        if (!ScreenTextAccessibilityService.isRunning) {
            statusTextView?.text = "⚠ Включите службу «TechSpeech» в Спец. возможностях Android (вкладка Звонки/Экран)"
            return
        }
        val screenText = ScreenTextAccessibilityService.captureVisibleScreenText().orEmpty().trim()
        if (screenText.isEmpty()) {
            statusTextView?.text = "Текст на текущем экране не найден. Откройте чат мессенджера или страницу."
            return
        }
        sourceTextView?.text = "Экран: ${screenText.take(220)}${if (screenText.length > 220) "..." else ""}"
        translateAndDisplay(screenText, autoDetect = true, speakResult = false)
    }

    private fun translateClipboardText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (clip.isEmpty()) {
            statusTextView?.text = "Буфер обмена пуст. Скопируйте сообщение в мессенджере."
            return
        }
        sourceTextView?.text = "Буфер: ${clip.take(220)}"
        translateAndDisplay(clip, autoDetect = true, speakResult = false)
    }

    private fun toggleCallListening() {
        if (isListeningCall) {
            isListeningCall = false
            listenButton?.text = "🎙 Звонок/Голос"
            statusTextView?.text = "Запись остановлена"
            serviceScope.launch { realAsrEngine.finishStream() }
        } else {
            isListeningCall = true
            listenButton?.text = "⏹ Стоп"
            statusTextView?.text = "🎙 Слушаю собеседника (${currentSpeechInputLang.uppercase()}) → ${currentTargetLangCode.uppercase()}..."
            serviceScope.launch {
                val inputLang = if (currentTargetLangCode == "ru") "en" else "ru"
                currentSpeechInputLang = inputLang
                realAsrEngine.startStream(inputLang)
            }
        }
    }

    private fun translateAndDisplay(text: String, autoDetect: Boolean, speakResult: Boolean) {
        statusTextView?.text = "Перевод..."
        serviceScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val pair = LanguagePair(
                source = if (autoDetect) Language.AUTO else Language.byCodeOrCreate(s.sourceLanguageCode),
                target = Language.byCodeOrCreate(currentTargetLangCode)
            )
            val seg = pipeline.processSpeechEvent(
                event = SpeechRecognitionEvent(
                    segmentId = System.currentTimeMillis(),
                    languageCode = if (autoDetect) Language.AUTO_CODE else s.sourceLanguageCode,
                    confirmedText = text,
                    unstableTailText = "",
                    isFinal = true
                ),
                pair = pair,
                domainId = s.defaultDomainId,
                requestedProfile = s.qualityProfile,
                ttsEnabled = speakResult,
                speechRate = s.ttsSpeechRate
            )

            if (seg.errorMessage != null) {
                statusTextView?.text = "⚠ Ошибка перевода"
                translatedTextView?.text = seg.errorMessage
            } else {
                lastTranslatedText = seg.fullTranslatedText
                val det = seg.detectedSourceLanguage?.uppercase() ?: "AUTO"
                statusTextView?.text = "✓ $det → ${currentTargetLangCode.uppercase()} • ${seg.engineBackendName} (${seg.endToEndLatencyMs} ms)"
                translatedTextView?.text = seg.fullTranslatedText
                if (seg.fullTranslatedText.isNotBlank()) {
                    historyDao.insert(
                        TranslationHistoryEntity(
                            sourceLang = seg.sourceLanguage,
                            targetLang = seg.targetLanguage,
                            domainId = seg.domainId,
                            sourceText = seg.normalizedSourceText.take(500),
                            translatedText = seg.fullTranslatedText.take(1000),
                            routingMode = seg.routingUsed.name,
                            latencyMs = seg.endToEndLatencyMs
                        )
                    )
                }
            }
        }
    }

    private fun speakLastResult() {
        if (lastTranslatedText.isBlank()) return
        serviceScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            val bundle = engineRegistry.acquireActivePair(s.activePair, s.qualityProfile)
            bundle.ttsEngine.speak(lastTranslatedText, currentTargetLangCode, s.ttsSpeechRate)
        }
    }

    private fun setSpeakerphone(enabled: Boolean) {
        speakerphoneEnabled = enabled
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = enabled
        }
        speakerButton?.text = if (enabled) "🔊 Громкая: ВКЛ" else "🔈 Громкая"
    }

    private fun removeAllViews() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        panelView = null
    }

    private fun createChannelIfNeeded() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floating Screen & Call Translator",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, FloatingTranslatorOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TechSpeech: Плавающий переводчик активен")
            .setContentText("Перевод звонков, мессенджеров и экрана поверх окон")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", stopPending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "floating_overlay_translator_channel"
        const val NOTIFICATION_ID = 2002
        const val ACTION_START = "com.offline.translate.app.START_OVERLAY"
        const val ACTION_STOP = "com.offline.translate.app.STOP_OVERLAY"
        const val EXTRA_PRESET = "preset"
        const val PRESET_CALL = "call"
        const val PRESET_MESSENGER = "messenger"
        const val PRESET_SCREEN = "screen"

        private val _isOverlayRunning = MutableStateFlow(false)
        val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()
    }
}
