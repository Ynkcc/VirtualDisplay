package com.ynk.virtualdisplay.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * 按节点隔离的虚拟显示器标志位（DisplayFlag）开关配置。
 */
internal object DisplayFlagPrefs {

    /**
     * 指定节点某个开关 flag 的配置流。
     * @param key flag 标识（参见 [com.ynk.virtualdisplay.data.model.DisplayFlag.key]）
     * @param defaultValue 未设置时的默认值
     */
    fun flagFlow(
        context: Context,
        key: String,
        defaultValue: Boolean,
        node: ServerNode
    ): Flow<Boolean> =
        AppDataStore.store(context).data.map { it[AppDataStore.boolKey(key, node)] ?: defaultValue }

    /** 当前节点某个开关 flag 的配置流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun flagFlow(context: Context, key: String, defaultValue: Boolean): Flow<Boolean> =
        AppDataStore.currentServerNodeCache.flatMapLatest { flagFlow(context, key, defaultValue, it) }

    /** 读取指定节点全部 flag 的当前值，key → 是否启用。 */
    suspend fun getFlags(context: Context, node: ServerNode): Map<String, Boolean> {
        val prefs = AppDataStore.store(context).data.first()
        return ALL_DISPLAY_FLAGS.associate { flag ->
            flag.key to (prefs[AppDataStore.boolKey(flag.key, node)] ?: flag.isDefaultEnabled)
        }
    }

    /** 读取当前节点全部 flag 的当前值。 */
    suspend fun getFlags(context: Context): Map<String, Boolean> =
        getFlags(context, AppDataStore.getCurrentServerNodeSync())

    /** 持久化指定节点某个开关 flag。 */
    suspend fun setFlag(context: Context, node: ServerNode, key: String, enabled: Boolean) {
        AppDataStore.store(context).edit { it[AppDataStore.boolKey(key, node)] = enabled }
    }

    /** 持久化当前节点某个开关 flag。 */
    suspend fun setFlag(context: Context, key: String, enabled: Boolean) =
        setFlag(context, AppDataStore.getCurrentServerNodeSync(), key, enabled)

    /** 将指定节点全部 flag 重置为默认值。 */
    suspend fun resetAllFlagsToDefault(context: Context, node: ServerNode) {
        AppDataStore.store(context).edit { prefs ->
            ALL_DISPLAY_FLAGS.forEach { flag ->
                prefs[AppDataStore.boolKey(flag.key, node)] = flag.isDefaultEnabled
            }
        }
    }

    /** 将当前节点全部 flag 重置为默认值。 */
    suspend fun resetAllFlagsToDefault(context: Context) =
        resetAllFlagsToDefault(context, AppDataStore.getCurrentServerNodeSync())
}
