package com.ynk.virtualdisplay.data.repository

import com.ynk.virtualdisplay.data.ServerNode

/**
 * 多节点连接编排接口。
 *
 * 与 [IDisplayRepository] 严格分离：显示器操作能力对所有连接槽有意义，
 * 而节点编排只对聚合层（[MultiConnectionRepository]）有意义，
 * 避免单节点槽被迫提供空实现。
 */
interface NodeConnectionManager {
    /** 切换活跃节点；槽尚不存在时先建立连接。 */
    fun setActiveNode(node: ServerNode)

    /** 建立到指定节点的连接。 */
    fun connectNode(node: ServerNode)

    /** 断开指定节点的连接；[killDaemon] 为 true 时同时销毁服务。 */
    fun disconnectNode(node: ServerNode, killDaemon: Boolean = false)

    /** 获取指定节点的连接槽。 */
    fun getSlot(nodeKey: String): IDisplayRepository?

    /** 获取所有活跃连接槽。 */
    fun allSlots(): Collection<IDisplayRepository>
}
