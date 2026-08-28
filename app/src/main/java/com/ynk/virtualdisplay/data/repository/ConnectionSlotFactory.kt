package com.ynk.virtualdisplay.data.repository

import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.data.remote.DaemonRemoteDataSource
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.rpc.DaemonControlApiImpl
import com.ynk.virtualdisplay.rpc.DaemonRpc
import com.ynk.virtualdisplay.util.ExceptionUtils
import com.ynk.virtualdisplay.video.VideoStreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 工厂：为每个 [ServerNode] 创建一套独立的连接资源（[ConnectionSlot]）。
 *
 * 本机节点会持有 [processDataSource] 以控制守护进程生命周期；
 * 远程节点的 [ConnectionSlot.processDataSource] 为 null。
 */
class ConnectionSlotFactory(
    private val settingsDataSource: AppSettingsDataSource,
    private val processDataSource: DaemonProcessDataSource,
) {
    /**
     * 为指定节点创建一套独立的连接资源（transport / rpc / remoteDataSource / videoController）。
     * 本机节点才持有进程控制权（[ConnectionSlot.processDataSource] 非空）。
     * @param node 目标服务器节点
     * @return 组装完毕的 [ConnectionSlot]
     */
    fun create(node: ServerNode): ConnectionSlot {
        // 每个槽独立的基础设施对象
        val transport = DaemonTransport()
        val rpc = DaemonRpc(transport)
        val controlApi = DaemonControlApiImpl(rpc, transport)
        val remoteDataSource = DaemonRemoteDataSource(controlApi)

        // 独立协程 Scope（VideoStreamController 用）
        val videoScope = CoroutineScope(
            Dispatchers.Main + SupervisorJob() +
                ExceptionUtils.coroutineExceptionHandler("VideoStream[${node.uniqueKey()}]")
        )
        val videoController = VideoStreamController(transport, controlApi, videoScope, settingsDataSource)

        // 本机节点才持有进程控制权
        val slotProcessDataSource = if (node.isLocal) processDataSource else null

        return ConnectionSlot(
            node = node,
            settingsDataSource = settingsDataSource,
            remoteDataSource = remoteDataSource,
            processDataSource = slotProcessDataSource,
            transport = transport,
            rpc = rpc,
            videoController = videoController,
        )
    }
}
