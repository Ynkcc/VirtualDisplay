package com.ynk.virtualdisplay.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

object ExceptionUtils {

    internal const val TAG = "ExceptionUtils"

    private val crashHandlerInstalled = AtomicBoolean(false)

    fun setupGlobalCrashHandler() {
        if (!crashHandlerInstalled.compareAndSet(false, true)) {
            return
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread: ${thread.name}", throwable)
            Log.e(TAG, buildCrashSummary(throwable))
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Global crash handler installed")
    }

    private fun buildCrashSummary(throwable: Throwable): String {
        return buildString {
            appendLine("========== CRASH SUMMARY ==========")
            appendLine("Type: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message}")
            appendLine("Stack trace:")
            throwable.stackTrace.take(15).forEach { frame ->
                appendLine("  at $frame")
            }
            throwable.cause?.let { cause ->
                appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.take(10).forEach { frame ->
                    appendLine("  at $frame")
                }
            }
            appendLine("==================================")
        }
    }

    fun coroutineExceptionHandler(tag: String = TAG): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            Log.e(tag, "Uncaught coroutine exception: ${throwable.message}", throwable)
        }
    }

    fun safeClose(tag: String, closeable: Closeable?) {
        closeable?.let {
            try {
                it.close()
            } catch (e: IOException) {
                Log.w(tag, "Failed to close resource", e)
            } catch (e: Exception) {
                Log.w(tag, "Failed to close resource", e)
            }
        }
    }

    fun safeCloseSilently(tag: String, closeable: Closeable?) {
        closeable?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.d(tag, "safeCloseSilently: close failed", e)
            }
        }
    }

    fun logIfCancellation(tag: String, throwable: Throwable) {
        if (throwable is kotlinx.coroutines.CancellationException) {
            Log.d(tag, "Coroutine cancelled: ${throwable.message}")
        }
    }
}

fun Closeable?.closeQuietly() {
    ExceptionUtils.safeCloseSilently(ExceptionUtils.TAG, this)
}

fun android.net.LocalSocket?.closeQuietly() {
    try {
        this?.close()
    } catch (e: Exception) {
        Log.d(ExceptionUtils.TAG, "closeQuietly: LocalSocket close failed", e)
    }
}

fun java.io.InputStream?.closeQuietly() {
    ExceptionUtils.safeCloseSilently(ExceptionUtils.TAG, this)
}

fun java.io.OutputStream?.closeQuietly() {
    ExceptionUtils.safeCloseSilently(ExceptionUtils.TAG, this)
}
