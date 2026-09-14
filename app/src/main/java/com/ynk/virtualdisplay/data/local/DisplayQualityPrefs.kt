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
 * 按节点隔离的默认画质参数（宽度 / 高度 / DPI）。
 */
internal object DisplayQualityPrefs {

    private const val WIDTH_PREFIX = "pref_default_width"
    private const val HEIGHT_PREFIX = "pref_default_height"
    private const val DPI_PREFIX = "pref_default_dpi"

    /** 指定节点默认宽度配置流（未设置时为空串）。 */
    fun prefDefaultWidthFlow(context: Context, node: ServerNode): Flow<String> =
        AppDataStore.store(context).data.map { it[AppDataStore.stringKey(WIDTH_PREFIX, node)] ?: "" }

    /** 当前节点默认宽度配置流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultWidthFlow(context: Context): Flow<String> =
        AppDataStore.currentServerNodeCache.flatMapLatest { prefDefaultWidthFlow(context, it) }

    /** 指定节点默认高度配置流（未设置时为空串）。 */
    fun prefDefaultHeightFlow(context: Context, node: ServerNode): Flow<String> =
        AppDataStore.store(context).data.map { it[AppDataStore.stringKey(HEIGHT_PREFIX, node)] ?: "" }

    /** 当前节点默认高度配置流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultHeightFlow(context: Context): Flow<String> =
        AppDataStore.currentServerNodeCache.flatMapLatest { prefDefaultHeightFlow(context, it) }

    /** 指定节点默认 DPI 配置流（未设置时为空串）。 */
    fun prefDefaultDpiFlow(context: Context, node: ServerNode): Flow<String> =
        AppDataStore.store(context).data.map { it[AppDataStore.stringKey(DPI_PREFIX, node)] ?: "" }

    /** 当前节点默认 DPI 配置流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultDpiFlow(context: Context): Flow<String> =
        AppDataStore.currentServerNodeCache.flatMapLatest { prefDefaultDpiFlow(context, it) }

    /**
     * 读取指定节点的默认画质参数。
     * @return Triple(width, height, dpi)，未设置时对应元素为空串
     */
    suspend fun getPrefDefaults(context: Context, node: ServerNode): Triple<String, String, String> {
        val prefs = AppDataStore.store(context).data.first()
        return Triple(
            prefs[AppDataStore.stringKey(WIDTH_PREFIX, node)] ?: "",
            prefs[AppDataStore.stringKey(HEIGHT_PREFIX, node)] ?: "",
            prefs[AppDataStore.stringKey(DPI_PREFIX, node)] ?: ""
        )
    }

    /** 读取当前节点的默认画质参数，参见 [getPrefDefaults]。 */
    suspend fun getPrefDefaults(context: Context): Triple<String, String, String> =
        getPrefDefaults(context, AppDataStore.getCurrentServerNodeSync())

    /** 持久化指定节点的默认宽度。 */
    suspend fun setPrefDefaultWidth(context: Context, node: ServerNode, value: String) {
        AppDataStore.store(context).edit { it[AppDataStore.stringKey(WIDTH_PREFIX, node)] = value }
    }

    /** 持久化当前节点的默认宽度。 */
    suspend fun setPrefDefaultWidth(context: Context, value: String) =
        setPrefDefaultWidth(context, AppDataStore.getCurrentServerNodeSync(), value)

    /** 持久化指定节点的默认高度。 */
    suspend fun setPrefDefaultHeight(context: Context, node: ServerNode, value: String) {
        AppDataStore.store(context).edit { it[AppDataStore.stringKey(HEIGHT_PREFIX, node)] = value }
    }

    /** 持久化当前节点的默认高度。 */
    suspend fun setPrefDefaultHeight(context: Context, value: String) =
        setPrefDefaultHeight(context, AppDataStore.getCurrentServerNodeSync(), value)

    /** 持久化指定节点的默认 DPI。 */
    suspend fun setPrefDefaultDpi(context: Context, node: ServerNode, value: String) {
        AppDataStore.store(context).edit { it[AppDataStore.stringKey(DPI_PREFIX, node)] = value }
    }

    /** 持久化当前节点的默认 DPI。 */
    suspend fun setPrefDefaultDpi(context: Context, value: String) =
        setPrefDefaultDpi(context, AppDataStore.getCurrentServerNodeSync(), value)
}
