package com.ynk.virtualdisplay.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.ynk.virtualdisplay.data.ServerNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * 按节点隔离的「最近启动应用」列表持久化。
 */
internal object RecentAppsPrefs {

    private const val PREFIX = "recent_apps"

    /** 读取指定节点最近使用的应用包名列表（新→旧，去重）。 */
    suspend fun recentApps(context: Context, node: ServerNode): List<String> {
        val raw = AppDataStore.store(context).data.first()[AppDataStore.stringKey(PREFIX, node)] ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(",")
    }

    /** 读取当前节点最近使用的应用包名列表。 */
    suspend fun recentApps(context: Context): List<String> =
        recentApps(context, AppDataStore.getCurrentServerNodeSync())

    /**
     * 将应用加入指定节点的最近使用列表：置于首位并去重，超出 [maxLimit] 则截断。
     * @param packageName 应用包名
     * @param maxLimit 列表最大长度，默认 10
     */
    suspend fun addRecentApp(
        context: Context,
        node: ServerNode,
        packageName: String,
        maxLimit: Int = 10
    ) {
        AppDataStore.store(context).edit { prefs ->
            val key = AppDataStore.stringKey(PREFIX, node)
            val current = prefs[key] ?: ""
            val list = if (current.isEmpty()) mutableListOf() else current.split(",").toMutableList()
            list.remove(packageName)
            list.add(0, packageName)
            val saved = if (list.size > maxLimit) list.take(maxLimit) else list
            prefs[key] = saved.joinToString(",")
        }
    }

    /** 将应用加入当前节点的最近使用列表，参见 [addRecentApp]。 */
    suspend fun addRecentApp(context: Context, packageName: String, maxLimit: Int = 10) =
        addRecentApp(context, AppDataStore.getCurrentServerNodeSync(), packageName, maxLimit)

    /** 指定节点最近使用应用列表流。 */
    fun recentAppsFlow(context: Context, node: ServerNode): Flow<List<String>> =
        AppDataStore.store(context).data.map { prefs ->
            val raw = prefs[AppDataStore.stringKey(PREFIX, node)] ?: ""
            if (raw.isEmpty()) emptyList() else raw.split(",")
        }

    /** 当前节点最近使用应用列表流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentAppsFlow(context: Context): Flow<List<String>> =
        AppDataStore.currentServerNodeCache.flatMapLatest { recentAppsFlow(context, it) }
}
