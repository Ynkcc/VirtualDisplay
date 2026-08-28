package com.ynk.virtualdisplay.data.remote

import android.view.InputEvent
import com.ynk.virtualdisplay.rpc.DaemonControlApi

/**
 * 远程（RPC）数据源：通过 TCP + RPC 协议调用 scrcpy daemon 的控制接口。
 *
 * 内部直接委托给 [DaemonControlApi]，主要作用是：
 * - 在 data 层与 rpc 层之间增加语义化的边界（Repository 不再直接感知 RPC 层）
 * - 将来如需替换为 IPC / AIDL / HTTP 等其他传输方式，只改此文件即可
 * - 可在此统一添加超时、重试、错误码转义等横切逻辑
 *
 * 属于 appModule，依赖 DaemonTransport 连接成功后才能正常调用。
 */
class DaemonRemoteDataSource(
    private val controlApi: DaemonControlApi
) {
    // === 显示器管理 ===

    /**
     * 在远程设备上创建虚拟显示器。
     * @return 成功返回新显示器的 id
     */
    suspend fun createDisplay(
        name: String, w: Int, h: Int, dpi: Int, flags: Int, mirrorDisplayId: Int = -1
    ): Result<Int> = controlApi.createDisplay(name, w, h, dpi, flags, mirrorDisplayId)

    /**
     * 销毁远程显示器。
     * @param displayId 目标显示器 id
     * @param moveTasksToDefaultDisplay 销毁时是否将其上的任务移回主屏，默认 true
     */
    suspend fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean = true): Result<Unit> =
        controlApi.releaseDisplay(displayId, moveTasksToDefaultDisplay)

    /** 调整远程显示器尺寸/DPI。 */
    suspend fun resizeDisplay(displayId: Int, w: Int, h: Int, dpi: Int): Result<Unit> =
        controlApi.resizeDisplay(displayId, w, h, dpi)

    /** 获取当前活跃的显示器 id 列表。 */
    suspend fun getActiveDisplayIds(): Result<IntArray> =
        controlApi.getActiveDisplayIds()

    /** 获取当前活跃显示器的详细信息列表。 */
    suspend fun getActiveDisplayInfos(): Result<List<com.ynk.virtualdisplay.protocol.DeviceMessage.DisplayInfoEntry>> =
        controlApi.getActiveDisplayInfos()

    // === 应用启动 ===

    /** 在指定显示器上启动应用。 */
    suspend fun startActivity(packageName: String, displayId: Int): Result<Int> =
        controlApi.startActivity(packageName, displayId)

    /** 在指定显示器上回到桌面（启动 Home）。 */
    suspend fun launchHome(displayId: Int): Result<Int> =
        controlApi.launchHome(displayId)

    /** 获取设备上已安装应用列表。 */
    suspend fun listApps(): Result<List<com.ynk.virtualdisplay.protocol.DeviceMessage.AppEntry>> =
        controlApi.listApps()

    // === 输入注入 (scrcpy-native protocol via ROLE_CONTROL socket) ===

    /**
     * 向指定显示器注入输入事件。
     * @param event 输入事件
     * @param screenWidth 源画面宽度（用于坐标归一化）
     * @param screenHeight 源画面高度
     */
    suspend fun injectInput(
        displayId: Int, event: InputEvent, screenWidth: Int, screenHeight: Int
    ): Result<Boolean> = controlApi.injectInput(displayId, event, screenWidth, screenHeight)

    // === 视频流 / 镜像切换 ===

    // === 守护进程生命周期 ===

    /** 请求远程 daemon 退出（关闭连接）。 */
    suspend fun exitDaemon(): Result<Unit> = controlApi.exitDaemon()
}
