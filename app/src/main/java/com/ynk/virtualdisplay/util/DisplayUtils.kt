package com.ynk.virtualdisplay.util

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

object DisplayUtils {

    data class DisplaySpec(val width: Int, val height: Int, val dpi: Int)

    fun getDisplaySpec(context: Context, displayId: Int): DisplaySpec? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return null
        return resolveDisplaySpec(context, display)
    }

    fun getDefaultDisplaySpec(context: Context): DisplaySpec? {
        return getDisplaySpec(context, Display.DEFAULT_DISPLAY)
    }

    fun getVirtualDisplaySpec(display: Display): DisplaySpec {
        val size = Point()
        val metrics = DisplayMetrics()
        readDisplaySizeAndMetrics(display, size, metrics)
        return DisplaySpec(size.x, size.y, metrics.densityDpi)
    }

    private fun resolveDisplaySpec(context: Context, display: Display): DisplaySpec {
        val size = Point()
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
