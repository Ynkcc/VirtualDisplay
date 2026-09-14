package com.ynk.virtualdisplay.data.repository

import android.content.Context
import com.ynk.virtualdisplay.data.local.RecentAppsPrefs

/**
 * 便捷门面：读取/记录用户在虚拟屏幕中最近启动的应用（当前节点），
 * 内部委托 [RecentAppsPrefs] 实现，并固定最大条数。
 */
object RecentAppHelper {
    private const val MAX_LIMIT = 10

    /** 读取当前节点最近启动的应用包名列表（新→旧）。 */
    suspend fun getRecentApps(context: Context): List<String> {
        return RecentAppsPrefs.recentApps(context)
    }

    /** 将应用加入当前节点最近启动列表，超出上限自动截断。 */
    suspend fun addRecentApp(context: Context, packageName: String) {
        RecentAppsPrefs.addRecentApp(context, packageName, MAX_LIMIT)
    }
}
