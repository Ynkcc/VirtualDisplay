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

    suspend fun createDisplay(
        name: String, w: Int, h: Int, dpi: Int, flags: Int, mirrorDisplayId: Int = -1
    ): Result<Int> = controlApi.createDisplay(name, w, h, dpi, flags, mirrorDisplayId)

    suspend fun releaseDisplay(displayId: Int): Result<Unit> =
        controlApi.releaseDisplay(displayId)

    suspend fun resizeDisplay(displayId: Int, w: Int, h: Int, dpi: Int): Result<Unit> =
        controlApi.resizeDisplay(displayId, w, h, dpi)

    suspend fun getActiveDisplayIds(): Result<IntArray> =
        controlApi.getActiveDisplayIds()

    suspend fun getActiveDisplayInfos(): Result<List<com.ynk.virtualdisplay.protocol.DeviceMessage.DisplayInfoEntry>> =
        controlApi.getActiveDisplayInfos()

    // === 应用启动 ===

    suspend fun startActivity(packageName: String, displayId: Int): Result<Int> =
        controlApi.startActivity(packageName, displayId)

    suspend fun launchHome(displayId: Int): Result<Int> =
        controlApi.launchHome(displayId)

    suspend fun listApps(): Result<List<com.ynk.virtualdisplay.protocol.DeviceMessage.AppEntry>> =
        controlApi.listApps()

    // === 输入注入 (scrcpy-native protocol via ROLE_CONTROL socket) ===

    suspend fun injectInput(
        displayId: Int, event: InputEvent, screenWidth: Int, screenHeight: Int
    ): Result<Boolean> = controlApi.injectInput(displayId, event, screenWidth, screenHeight)

    // === 视频流 / 镜像切换 ===

    // === 守护进程生命周期 ===

    suspend fun exitDaemon(): Result<Unit> = controlApi.exitDaemon()
}
