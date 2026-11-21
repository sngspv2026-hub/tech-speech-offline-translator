package com.offline.translate.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.offline.translate.core.common.SafeLogger

/**
 * Local on-demand AccessibilityService that reads visible text from the active app
 * (WhatsApp, Telegram, Viber, SMS, Browser, PDF, SCADA apps, etc.) ONLY when the user
 * taps "Перевести экран / чат" on the floating overlay.
 *
 * Security:
 * - Never reads password fields (`isPassword == true`).
 * - Skips our own translator overlay package so it only captures the target app underneath.
 */
class ScreenTextAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SafeLogger.i("ScreenAccessibility", "ScreenTextAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive mode: text is only read on explicit user tap on the floating overlay
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun readCurrentScreenText(): String {
        val ownPackage = applicationContext.packageName
        val collected = LinkedHashSet<String>()

        // Prefer interactive windows to find the app beneath our floating overlay
        val allWindows = runCatching { windows }.getOrNull().orEmpty()
        var foundTargetRoot = false
        for (w in allWindows) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != ownPackage && pkg != "com.android.systemui") {
                extractNodeTextRecursive(root, collected)
                foundTargetRoot = true
            }
            root.recycle()
        }

        if (!foundTargetRoot) {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && activeRoot.packageName?.toString() != ownPackage) {
                extractNodeTextRecursive(activeRoot, collected)
                activeRoot.recycle()
            }
        }

        return collected.joinToString("\n").take(3500)
    }

    private fun extractNodeTextRecursive(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return
        if (node.isPassword) return // Never read password inputs

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.length >= 2 && !isNoiseUiString(text)) {
            out.add(text)
        } else {
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.length >= 4 && !isNoiseUiString(desc) && node.childCount == 0) {
                out.add(desc)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractNodeTextRecursive(child, out)
                child.recycle()
            }
        }
    }

    private fun isNoiseUiString(s: String): Boolean {
        // Skip timestamps like 14:25 or pure single symbols
        return s.matches(Regex("""^\d{1,2}:\d{2}$""")) || s.matches(Regex("""^[\p{Punct}\s]+$"""))
    }

    companion object {
        @Volatile
        var instance: ScreenTextAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun captureVisibleScreenText(): String? = instance?.readCurrentScreenText()
    }
}
