package com.ynk.virtualdisplay.data.system

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * 系统级数据源：通过 Android 系统服务获取 Launcher 列表和 Display 信息。
 *
 * 对应重构计划方案二中的：
 * - LauncherDataSource：resolveHomeLauncherPackages()
 * - DisplayInfoDataSource：DisplayManager 查询
 *
 * 两者都依赖 Context.getSystemService()，故合并在一个数据源中便于管理。
 *
 * 属于 appModule（不依赖 Daemon，但在权限通过后才会使用）。
 */
class LauncherDataSource(private val context: Context) {

    // === Launcher 查询 ===

    /**
     * 解析设备上所有可用的桌面（Home）应用包名列表。
     * 顺序：默认桌面优先，之后按系统 queryIntentActivities 返回的顺序。
     * 用于 [IDisplayRepository.launchHome] 逐个尝试直到成功。
     */
    fun resolveHomeLauncherPackages(): List<String> {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val defaultLauncher = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        val candidates = mutableListOf<String>()
        if (defaultLauncher != null) candidates.add(defaultLauncher)
        val allHomeActivities = pm.queryIntentActivities(homeIntent, 0)
        for (info in allHomeActivities) {
            val pkg = info.activityInfo.packageName
            if (pkg !in candidates) candidates.add(pkg)
        }
        if (candidates.isEmpty()) {
            val fallbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val fallbackResolved = pm.queryIntentActivities(fallbackIntent, 0)
            for (info in fallbackResolved) {
                val pkg = info.activityInfo.packageName
                if (pkg !in candidates) candidates.add(pkg)
            }
        }
        return candidates
    }

    // === Display 查询 ===

    /** 获取当前 DisplayManager 管理的所有 Display 对象 */
    fun getAllDisplays(): Array<Display> {
        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays
    }

    /** 通过 displayId 查询具体的 Display 对象 */
    fun getDisplay(displayId: Int): Display? {
        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(displayId)
    }

    /** 注册 DisplayListener（带生命周期管理） */
    fun registerDisplayListener(listener: DisplayManager.DisplayListener) {
        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(listener, null)
    }

    fun unregisterDisplayListener(listener: DisplayManager.DisplayListener) {
        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.unregisterDisplayListener(listener)
    }
}
