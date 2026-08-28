package com.ynk.virtualdisplay.manager

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * 屏幕信息管理器：统一封装显示设备规格查询。
 *
 * 职责：
 * - 查询默认显示器规格（分辨率、DPI）
 * - 查询指定 DisplayId 的虚拟显示器规格
 * - 查询所有非默认显示器的列表
 *
 * 属于 coreModule，不依赖 Shizuku 或 Daemon 进程。
 * 封装 DisplayManager 的直接调用，使 ViewModel/Interactor 不再需要直接持有
 * Context 来查询显示信息。
 */
class DisplayMetricsManager(private val context: Context) {

    /** 显示器规格：宽、高（像素）与 DPI。 */
    data class DisplaySpec(val width: Int, val height: Int, val dpi: Int)

    private val displayManager: DisplayManager
        get() = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** 获取默认显示器的规格，不存在时返回 null。 */
    fun getDefaultDisplaySpec(): DisplaySpec? {
        return getDisplaySpec(Display.DEFAULT_DISPLAY)
    }

    /**
     * 获取指定 displayId 的显示器规格。
     * @return 对应显示器的规格；displayId 无效时返回 null
     */
    fun getDisplaySpec(displayId: Int): DisplaySpec? {
        val display = displayManager.getDisplay(displayId) ?: return null
        return resolveDisplaySpec(display)
    }

    /**
     * 获取虚拟显示器的规格（从 Display 对象直接读取）。
     */
    fun getVirtualDisplaySpec(display: Display): DisplaySpec {
        val size = Point()
        val metrics = DisplayMetrics()
        readDisplaySizeAndMetrics(display, size, metrics)
        return DisplaySpec(size.x, size.y, metrics.densityDpi)
    }

    /**
     * 获取所有非默认显示器（即虚拟显示器）的列表。
     */
    fun getVirtualDisplays(): List<Display> {
        return displayManager.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun resolveDisplaySpec(display: Display): DisplaySpec {
        val size = Point()
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            size.set(bounds.width(), bounds.height())
        } else {
            readDisplaySizeAndMetrics(display, size, metrics)
        }

        if (metrics.densityDpi == 0) {
            readDisplayMetricsOnly(display, metrics)
        }

        return DisplaySpec(size.x, size.y, metrics.densityDpi)
    }

    @Suppress("DEPRECATION")
    private fun readDisplaySizeAndMetrics(display: Display, size: Point, metrics: DisplayMetrics) {
        display.getRealSize(size)
        display.getRealMetrics(metrics)
    }

    @Suppress("DEPRECATION")
    private fun readDisplayMetricsOnly(display: Display, metrics: DisplayMetrics) {
        display.getRealMetrics(metrics)
    }
}
