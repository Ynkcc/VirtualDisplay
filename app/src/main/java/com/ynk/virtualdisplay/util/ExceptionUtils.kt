package com.ynk.virtualdisplay.util

import android.util.Log
import com.ynk.virtualdisplay.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 异常处理统一入口，承载两类策略：
 *
 * 1. 资源关闭/清理：始终吞掉并记录日志（close() 失败几乎不可恢复，不应影响主流程）。
 * 2. 预期外异常：依据 [STRICT] 区分 debug/release ——
 *    - Debug 构建重新抛出，让崩溃尽早暴露以便定位修复；
 *    - Release 构建仅记录日志后吞掉，保证线上韧性。
 *
 * 不在此处捕获的异常（如窄类型的 IOException、CancellationException）应交给调用方按语义处理。
 */
object ExceptionUtils {

    internal const val TAG = "ExceptionUtils"

    /**
     * 严格模式开关：Debug 构建为 true，Release 构建为 false。
     * 由 BuildConfig.DEBUG 决定，无需手动维护。
     */
    val STRICT: Boolean = BuildConfig.DEBUG

    private val crashHandlerInstalled = AtomicBoolean(false)

    /**
     * 安装全局未捕获异常处理器：记录崩溃摘要后透传给系统默认处理器。
     * 幂等，重复调用只生效一次。
     */
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

    /**
     * 构造协程异常处理器，统一记录未被捕获的协程异常。
     *
     * @param tag 日志标签，默认使用类默认标签。
     */
    fun coroutineExceptionHandler(tag: String = TAG): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            Log.e(tag, "Uncaught coroutine exception: ${throwable.message}", throwable)
        }
    }

    /**
     * 在 Debug 构建中重新抛出 [throwable]，使其经由全局崩溃处理器尽早暴露；
     * Release 构建中直接返回（调用方应在此之前已记录日志），保证运行韧性。
     *
     * 仅用于 catch 到"预期外"异常、但线上又必须继续运行的兜底分支。
     * 不要用于 close()/release() 等清理路径——清理失败只应记录日志。
     */
    fun rethrowInDebug(throwable: Throwable) {
        if (STRICT) throw throwable
    }

    /**
     * 安全关闭可关闭资源，关闭失败时记录 warning 日志后吞掉。
     *
     * @param tag       日志标签。
     * @param closeable 待关闭资源，为 null 时静默忽略。
     */
    fun safeClose(tag: String, closeable: Closeable?) {
        closeable?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(tag, "Failed to close resource", e)
            }
        }
    }

    /**
     * 静默关闭可关闭资源，关闭失败仅记录 debug 日志，不干扰主流程。
     *
     * @param tag       日志标签。
     * @param closeable 待关闭资源，为 null 时静默忽略。
     */
    fun safeCloseSilently(tag: String, closeable: Closeable?) {
        closeable?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.d(tag, "safeCloseSilently: close failed", e)
            }
        }
    }

    /**
     * 若异常为协程取消 [CancellationException]，以 debug 级别记录日志。
     * 用于在 catch 块中区分"主动取消"与真正的错误。
     */
    fun logIfCancellation(tag: String, throwable: Throwable) {
        if (throwable is kotlinx.coroutines.CancellationException) {
            Log.d(tag, "Coroutine cancelled: ${throwable.message}")
        }
    }
}

/** 扩展函数：静默关闭 [Closeable]，为 null 时安全忽略。 */
fun Closeable?.closeQuietly() {
    ExceptionUtils.safeCloseSilently(ExceptionUtils.TAG, this)
}

/** 扩展函数：静默关闭 [LocalSocket]，关闭失败仅记录 debug 日志。 */
fun android.net.LocalSocket?.closeQuietly() {
    try {
        this?.close()
    } catch (e: Exception) {
        Log.d(ExceptionUtils.TAG, "closeQuietly: LocalSocket close failed", e)
    }
}

/** 扩展函数：静默关闭 [InputStream]，为 null 时安全忽略。 */
fun java.io.InputStream?.closeQuietly() {
    ExceptionUtils.safeCloseSilently(ExceptionUtils.TAG, this)
}

/** 扩展函数：静默关闭 [OutputStream]，为 null 时安全忽略。 */
fun java.io.OutputStream?.closeQuietly() {
    ExceptionUtils.safeCloseSilently(ExceptionUtils.TAG, this)
}
