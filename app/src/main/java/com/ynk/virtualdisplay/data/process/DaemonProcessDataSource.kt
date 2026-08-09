package com.ynk.virtualdisplay.data.process

import com.ynk.virtualdisplay.process.DaemonProcessController

/**
 * 进程控制数据源：封装对 [DaemonProcessController] 的调用。
 *
 * 在 data 层与 process 层之间增加语义化边界，屏蔽 Shizuku/AIDL/
 * app_process 等具体实现细节，Repository 只管"启动/停止/查PID"。
 *
 * 属于 appModule，在 Shizuku 授权通过后才可用。
 */
class DaemonProcessDataSource(
    private val processController: DaemonProcessController
) {
    /**
     * 同步启动 daemon 进程（阻塞 IO 线程）。
     * @return true 表示启动成功（或已在运行）
     */
    suspend fun startDaemon(port: Int, host: String): Boolean =
        processController.startDaemon(port, host)

    /**
     * 同步停止 daemon 进程（阻塞 IO 线程）。
     */
    suspend fun stopDaemon() = processController.stopDaemon()

    /**
     * 获取当前运行中的 daemon PID（-1 表示未运行）。
     */
    suspend fun getDaemonPid(): Int = processController.getDaemonPid()
}
