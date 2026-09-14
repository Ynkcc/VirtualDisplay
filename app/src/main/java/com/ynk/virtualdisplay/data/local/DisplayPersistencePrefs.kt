package com.ynk.virtualdisplay.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.model.SavedDisplay
import kotlinx.coroutines.flow.first
import org.json.JSONArray

/**
 * 按节点隔离的虚拟显示器列表持久化（JSON 序列化）。
 */
internal object DisplayPersistencePrefs {

    private const val PREFIX = "displays"

    /**
     * 读取指定节点保存的虚拟显示器列表。
     * @return 已保存的显示器列表；无数据或 JSON 解析失败时返回空列表
     */
    suspend fun getDisplaysForServer(context: Context, node: ServerNode): List<SavedDisplay> {
        val raw = AppDataStore.store(context).data.first()[AppDataStore.stringKey(PREFIX, node)] ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<SavedDisplay>()
            for (i in 0 until array.length()) {
                list.add(SavedDisplay.fromJsonObject(array.getJSONObject(i)))
            }
            list
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    /** 保存/更新指定节点的一个虚拟显示器（同 id 覆盖旧记录）。 */
    suspend fun saveDisplayForServer(context: Context, node: ServerNode, display: SavedDisplay) {
        val current = getDisplaysForServer(context, node).toMutableList()
        current.removeAll { it.id == display.id }
        current.add(display)
        writeDisplays(context, node, current)
    }

    /** 按 id 移除指定节点保存的虚拟显示器。 */
    suspend fun removeDisplayForServer(context: Context, node: ServerNode, displayId: Int) {
        val current = getDisplaysForServer(context, node).toMutableList()
        current.removeAll { it.id == displayId }
        writeDisplays(context, node, current)
    }

    /** 整体覆写指定节点保存的虚拟显示器列表。 */
    suspend fun setDisplaysForServer(context: Context, node: ServerNode, displays: List<SavedDisplay>) {
        writeDisplays(context, node, displays)
    }

    private suspend fun writeDisplays(context: Context, node: ServerNode, displays: List<SavedDisplay>) {
        val array = JSONArray()
        displays.forEach { array.put(it.toJsonObject()) }
        AppDataStore.store(context).edit { prefs ->
            prefs[AppDataStore.stringKey(PREFIX, node)] = array.toString()
        }
    }
}
