package com.ynk.virtualdisplay.ui.display

import android.hardware.display.DisplayManager
import android.util.Log
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.manager.DisplayMetricsManager

/** 显示器物理规格（宽 / 高 / DPI）。 */
internal data class DisplaySpec(val width: Int, val height: Int, val dpi: Int)

/**
 * 显示器规格解析：按优先级依次尝试
 * 1. RPC —— 远程和本地节点统一从服务端获取权威尺寸；
 * 2. 本机 DisplayManager —— 仅本机节点；
 * 3. 本地缓存（saved displays）—— 所有节点通用兜底。
 */
internal class DisplayInfoResolver(
    private val repository: IDisplayRepository,
    private val displayMetricsManager: DisplayMetricsManager,
    private val settingsDataSource: AppSettingsDataSource,
    private val isLocalNode: () -> Boolean,
    private val localDisplayManager: () -> DisplayManager?
) {
    companion object {
        private const val TAG = "DisplayInfoResolver"
    }

    suspend fun resolve(displayId: Int): DisplaySpec? {
        repository.getActiveDisplayInfos().getOrNull()
            ?.firstOrNull { it.displayId == displayId }
            ?.let { info ->
                Log.i(TAG, "resolve: RPC display #$displayId size=${info.width}x${info.height}")
                return DisplaySpec(info.width, info.height, info.dpi)
            }

        if (isLocalNode()) {
            localDisplayManager()?.getDisplay(displayId)?.let { display ->
                val spec = displayMetricsManager.getVirtualDisplaySpec(display)
                return DisplaySpec(spec.width, spec.height, spec.dpi)
            }
        }

        val node = settingsDataSource.getCurrentServerNodeSync()
        settingsDataSource.getDisplaysForServer(node).find { it.id == displayId }?.let { saved ->
            Log.i(TAG, "resolve: found saved display #$displayId size=${saved.width}x${saved.height}")
            return DisplaySpec(saved.width, saved.height, saved.dpi)
        }

        Log.w(TAG, "resolve: Display #$displayId not found via RPC, local DM, or saved cache")
        return null
    }
}
