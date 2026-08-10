package com.ynk.virtualdisplay.data.repository

import android.content.Context
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.AppSettings
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

    /** 释放指定显示器 */
    suspend fun releaseDisplay(displayId: Int): Result<Unit>

    /** 设置显示器 Surface — 驱动客户端 H264 解码器 */
    suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit>

    /** 重新调整指定虚拟显示器的物理尺寸和 DPI */
    suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit>

    /** 在指定显示器启动应用 */
    suspend fun launchApp(packageName: String, displayId: Int): Result<Int>

    /** 在指定显示器启动主屏幕/Launcher */
    suspend fun launchHome(displayId: Int): Result<Int>

    /** 注入输入事件 */
    suspend fun injectInput(event: InputEvent): Result<Boolean>

    /** 注入带 DisplayId 的输入事件 */
    suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean>


    /** 获取当前 Daemon 中的活动显示器 ID 列表 */
    suspend fun getActiveDisplayIds(): Result<IntArray>

    /** 销毁远程特权服务 */
    fun destroyService()

    /** 设置视频配置回调 */
    fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?)

    /** 设置性能统计回调 */
    fun setPerformanceStatsCallback(callback: ((String) -> Unit)?)

    /** 主动向守护进程拉取并同步当前管理的显示器列表 (缓存) */
    fun refreshDisplays()
}

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
