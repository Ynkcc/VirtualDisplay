package com.ynk.virtualdisplay.data.repository

import android.content.Context
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.protocol.DeviceMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * 状态枚举定义
 */
enum class ConnectionStatus {
    IDLE,
    BINDING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
    RECONNECTING
}

/**
 * 显示器管理核心仓库接口
 *
 * 职责：统一数据入口，屏蔽数据源差异。
 * 业务逻辑由 [com.ynk.virtualdisplay.domain.DisplayInteractor] 编排，
 * 系统服务由 [com.ynk.virtualdisplay.manager] 层管理。
 */
interface IDisplayRepository {
    companion object {
        const val REQUEST_CODE = 20260
    }

    /** 连接状态流 */
    val connectionStatus: StateFlow<ConnectionStatus>

    /** 连接错误消息流 (当 connectionStatus == ERROR 时携带错误详情) */
    val connectionError: StateFlow<String?>

    /** 当前管理中的显示器 ID 列表 */
    val managedDisplayIds: StateFlow<Set<Int>>

    /** 每个显示器（displayId -> owner）的持有者信息 */
    val displayOwners: StateFlow<Map<Int, DisplayOwner>>

    /** 当前守护进程的 PID */
    val daemonPid: StateFlow<Int>

    /** 绑定并初始化守护进程服务 */
    fun bindService()

    /** 解绑服务 */
    fun unbindService()

    /** 强制启动守护进程并连接 */
    suspend fun startDaemon(): Result<Unit>

    /** 强制停止守护进程并解绑 */
    suspend fun stopDaemon(): Result<Unit>

    /** 创建虚拟显示器 */
    suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int = 0, mirrorDisplayId: Int = -1): Result<Int>

    /** 释放指定显示器。moveTasksToDefaultDisplay=true 时销毁前将应用移回主屏，false 则完全交给系统处理 */
    suspend fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean = true): Result<Unit>

    /** 设置显示器 Surface — 驱动客户端 H264 解码器 */
    suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit>

    /**
     * 停止当前正在进行的视频/控制流（ROLE_VIDEO + ROLE_CONTROL 通道）。
     *
     * 仅销毁流通道，保留 negotiation 连接与显示器本身，因此下次
     * [setDisplaySurface] 会重建一套全新的通道。操控页面退出时调用，
     * 避免复用已失效（被服务端关闭）的 control socket。
     */
    suspend fun stopStreaming()

    /** 重新调整指定虚拟显示器的物理尺寸和 DPI */
    suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit>

    /** 在指定显示器启动应用 */
    suspend fun launchApp(packageName: String, displayId: Int): Result<Int>

    /** 在指定显示器启动主屏幕/Launcher */
    suspend fun launchHome(displayId: Int): Result<Int>

    /** 列出远程设备上已安装应用（用于 AppSelectionDialog） */
    suspend fun listApps(): Result<List<DeviceMessage.AppEntry>>

    /** 注入输入事件 */
    suspend fun injectInput(event: InputEvent): Result<Boolean>

    /** 注入带 DisplayId 的输入事件 */
    suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean>


    /** 获取当前 Daemon 中的活动显示器 ID 列表 */
    suspend fun getActiveDisplayIds(): Result<IntArray>

    /** 获取当前 Daemon 中活动显示器的详细信息（尺寸/DPI/rotation） */
    suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>>

    /** 销毁远程特权服务 */
    fun destroyService()

    /** 设置视频配置回调 */
    fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?)

    /** 设置性能统计回调 */
    fun setPerformanceStatsCallback(callback: ((String) -> Unit)?)

    /** 主动向守护进程拉取并同步当前管理的显示器列表 (缓存) */
    fun refreshDisplays()

    /** 切换活跃节点（仅 MultiConnectionRepository 有意义） */
    fun setActiveNode(node: com.ynk.virtualdisplay.data.ServerNode) {}
    /** 建立到指定节点的连接 */
    fun connectNode(node: com.ynk.virtualdisplay.data.ServerNode) {}
    /** 断开指定节点的连接 */
    fun disconnectNode(node: com.ynk.virtualdisplay.data.ServerNode, killDaemon: Boolean = false) {}
    /** 获取指定节点的 [IDisplayRepository] 槽位 */
    fun getSlot(nodeKey: String): IDisplayRepository? = null
    /** 获取所有活跃的连接槽 */
    fun allSlots(): Collection<IDisplayRepository> = emptyList()
}

/** 虚拟显示器持有者信息 */
data class DisplayOwner(
    val packageName: String? = null,
    val uid: Int = 0
)

/**
 * 辅助管理用户在虚拟屏幕中最近启动的 app 记录
 */
object RecentAppHelper {
    private const val MAX_LIMIT = 10

    suspend fun getRecentApps(context: Context): List<String> {
        return AppSettings.recentApps(context)
    }

    suspend fun addRecentApp(context: Context, packageName: String) {
        AppSettings.addRecentApp(context, packageName, MAX_LIMIT)
    }
}
