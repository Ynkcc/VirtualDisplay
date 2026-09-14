package com.ynk.virtualdisplay.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.ynk.virtualdisplay.data.ServerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 服务器节点列表与当前选中节点的持久化。
 */
internal object ServerNodePrefs {

    /** 服务器节点列表流；列表为空时提供默认「本机」节点。 */
    fun serverNodesFlow(context: Context): Flow<List<ServerNode>> =
        AppDataStore.store(context).data.map { prefs ->
            decodeNodes(prefs[AppDataStore.serverNodesKey] ?: "")
        }

    /** 读取已保存的服务器节点列表，缺省时返回仅含「本机」的列表。 */
    suspend fun getServerNodes(context: Context): List<ServerNode> =
        decodeNodes(AppDataStore.store(context).data.first()[AppDataStore.serverNodesKey] ?: "")

    /** 新增节点；若已存在同 host+port 的节点则先移除旧记录再追加。 */
    suspend fun addServerNode(context: Context, node: ServerNode) {
        val list = getServerNodes(context).toMutableList()
        list.removeAll { it.host == node.host && it.port == node.port }
        list.add(node)
        writeNodes(context, list)
    }

    /** 按 host+port 移除节点。 */
    suspend fun removeServerNode(context: Context, node: ServerNode) {
        val list = getServerNodes(context).toMutableList()
        list.removeAll { it.host == node.host && it.port == node.port }
        writeNodes(context, list)
    }

    /** 用 [newNode] 替换 [oldNode]；若旧节点不存在则直接追加新节点。 */
    suspend fun updateServerNode(context: Context, oldNode: ServerNode, newNode: ServerNode) {
        val list = getServerNodes(context).toMutableList()
        val index = list.indexOfFirst { it.host == oldNode.host && it.port == oldNode.port }
        if (index >= 0) {
            list[index] = newNode
        } else {
            list.removeAll { it.host == newNode.host && it.port == newNode.port }
            list.add(newNode)
        }
        writeNodes(context, list)
    }

    /** 持久化当前选中节点身份（仅写入节点身份，不覆写用户配置的监听地址/端口/密码）。 */
    suspend fun setCurrentServerNode(context: Context, node: ServerNode) {
        AppDataStore.store(context).edit { prefs ->
            prefs[AppDataStore.currentServerNodeKey] = node.toSerializedString()
        }
    }

    private fun decodeNodes(raw: String): List<ServerNode> {
        val list = raw.split(",").filter { it.isNotBlank() }
            .mapNotNull { ServerNode.fromSerializedString(it) }
            .toMutableList()
        if (list.isEmpty()) {
            list.add(AppDataStore.defaultLocalNode())
        }
        return list
    }

    private suspend fun writeNodes(context: Context, nodes: List<ServerNode>) {
        val serialized = nodes.joinToString(",") { it.toSerializedString() }
        AppDataStore.store(context).edit { it[AppDataStore.serverNodesKey] = serialized }
    }
}
