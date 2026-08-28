package com.ynk.virtualdisplay.data.process

import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.domain.PrivilegeException
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.process.DaemonProcessController

/**
 * 进程控制数据源：封装对 [DaemonProcessController] 的调用。
 *
 * 在 data 层与 process 层之间增加语义化边界，屏蔽 Shizuku/AIDL/
 * app_process 等具体实现细节，Repository 只管"启动/停止/查PID"。
 *
 * ## 特权检查的唯一位置
 *
 * 特权（Shizuku / Root）的唯一用途就是"拉起本机 daemon"，因此全应用只有
 * [startDaemon] 一处会做特权预检（见 [ensurePrivilege]），其他任何路径
 * （连接远程节点、查询 PID、停止 daemon、UI 刷新等）都不做特权检查：
 * - 连接/复用已运行的 daemon、查询 PID、停止 daemon 均为纯操作，不检查特权；
 * - 远程节点不拉 daemon，自然不涉及特权。
 *
 * [DaemonProcessController] 只负责按当前特权模式选择执行策略（shizuku/root/普通），
 * 它不判定"是否有权"。
 */
class DaemonProcessDataSource(
    private val settingsDataSource: AppSettingsDataSource,
    private val shizukuManager: ShizukuManager,
    private val processController: DaemonProcessController
) {
    /**
     * 特权预检：仅当真正要拉起本机 daemon 时才调用。
     * 未授权时抛 [PrivilegeException]（含用户可读的中文提示）。
     */
    private fun ensurePrivilege() {
        when (settingsDataSource.getPrivilegeModeSync()) {
            PrivilegeMode.SHIZUKU ->
                if (!shizukuManager.isAvailable()) {
                    throw PrivilegeException("Shizuku 未就绪，请先启动 Shizuku 并授权")
                }
            PrivilegeMode.ROOT ->
                if (!processController.isRootAvailable()) {
                    throw PrivilegeException("Root 未授权，无法启动服务端")
                }
            PrivilegeMode.NONE -> {}
        }
    }

    /**
     * 同步启动 daemon 进程（阻塞 IO 线程）。
     * 启动前统一做特权预检（[ensurePrivilege]），未授权时抛 [PrivilegeException]。
     * @return true 表示启动成功（或已在运行）
     */
    suspend fun startDaemon(port: Int, host: String, password: String? = null): Boolean {
        ensurePrivilege()
        return processController.startDaemon(port, host, password)
    }

    /**
     * 同步停止 daemon 进程（阻塞 IO 线程）。纯操作，不做特权检查。
     */
    suspend fun stopDaemon() = processController.stopDaemon()

    /**
     * 获取当前运行中的 daemon PID（-1 表示未运行）。纯状态查询，不做特权检查。
     */
    suspend fun getDaemonPid(): Int = processController.getDaemonPid()

    /**
     * 探测当前设备是否可用 Root（通过 [su] 探测）。
     * 阻塞 IO 线程，由调用方确保在 IO 上下文执行。
     */
    suspend fun isRootAvailable(): Boolean = processController.isRootAvailable()
}
