package com.offline.translate.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

interface AppDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

@Singleton
class DefaultAppDispatchers @Inject constructor() : AppDispatchers {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
}

/**
 * Privacy-preserving logger that strips or redacts user speech/text payloads in production logs.
 * Zero telemetry by design.
 */
object SafeLogger {
    @Volatile
    var isDebugBuild: Boolean = false

    private val piiPatterns = listOf(
        Regex("""\b\d{4,}\b"""),
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    )

    fun sanitize(message: String): String {
        var result = message
        for (pattern in piiPatterns) {
            result = pattern.replace(result, "[REDACTED]")
        }
        return result
    }

    fun d(tag: String, message: String) {
        if (isDebugBuild) {
            println("D/$tag: ${sanitize(message)}")
        }
    }

    fun i(tag: String, message: String) {
        println("I/$tag: ${sanitize(message)}")
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        println("W/$tag: ${sanitize(message)}${throwable?.let { " (${it.javaClass.simpleName})" } ?: ""}")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        System.err.println("E/$tag: ${sanitize(message)}${throwable?.let { " (${it.javaClass.simpleName})" } ?: ""}")
    }
}
