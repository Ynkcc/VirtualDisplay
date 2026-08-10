package com.ynk.virtualdisplay.domain

import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.repository.RecentAppHelper
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.protocol.DeviceMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * 领域层核心：显示器操作编排器。
 *
 * 职责：
 * - 封装虚拟显示器相关的业务规则（比例校验、默认 Flags 计算等）
 * - 编排多步骤事务（重启守护进程、切换视频流目标等）
 * - 暴露 Repository 的状态流供 ViewModel 订阅
 * - 屏蔽数据层细节，使 ViewModel 不直接操作 Repository 的底层方法
 *
 * 参考 AccountSwitcher 的 [AccountInteractor] 设计模式。
 */
class DisplayInteractor(
    private val context: Context,
    private val repository: IDisplayRepository,
    private val shizukuManager: ShizukuManager
) {
    companion object {
        private const val TAG = "DisplayInteractor"
        private const val MAX_ASPECT_RATIO = 2.5f
    }

    // === 状态流代理 ===

    val connectionStatus: StateFlow<ConnectionStatus> = repository.connectionStatus
    val connectionError: StateFlow<String?> = repository.connectionError
    val managedDisplayIds: StateFlow<Set<Int>> = repository.managedDisplayIds
    val daemonPid: StateFlow<Int> = repository.daemonPid

    // === 服务生命周期 ===

    /**
     * 绑定并启动守护进程服务。
     * 内部会校验 Shizuku 可用性后再调用 Repository。
     */
    fun bindService() {
        val mode = AppSettings.getPrivilegeModeSync()
        if (mode == PrivilegeMode.SHIZUKU && !shizukuManager.isAvailable()) {
            Log.w(TAG, "Shizuku not available, cannot bind service")
            return
        }
        repository.bindService()
    }

    /**
     * 解绑服务。
     */
    fun unbindService() {
        repository.unbindService()
    }

    /**
     * 切换活跃节点。
     */
    fun setActiveNode(node: com.ynk.virtualdisplay.data.ServerNode) {
        repository.setActiveNode(node)
    }

    /**
     * 获取指定节点的仓库槽位。
     */
    fun getSlot(nodeKey: String): IDisplayRepository? {
        return repository.getSlot(nodeKey)
    }

    /**
     * 重启守护进程：完整停止（杀进程+断连）→ 延迟 → 重新启动并连接。
     */
    suspend fun restartDaemon(): Result<Unit> = runCatching {
        repository.stopDaemon().getOrThrow()
        delay(500)
        repository.startDaemon().getOrThrow()
    }

    suspend fun startDaemon(): Result<Unit> {
        return repository.startDaemon()
    }

    suspend fun stopDaemon(): Result<Unit> {
        return repository.stopDaemon()
    }

    // === 显示器操作 ===

    /**
     * 创建虚拟显示器，包含宽高比例校验和默认 Flags 计算。
     */
    suspend fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int = 0,
        mirrorDisplayId: Int = -1
    ): Result<Int> {
        // 业务规则校验（尺寸/比例）失败必须转为 Result.failure，不能抛出：
        // 调用方 MainViewModel.createVirtualDisplay 在 viewModelScope.launch 中
        // 用 .onFailure 接收，抛出的 IllegalArgumentException 会逃逸协程导致崩溃。
        // 仅捕获 IllegalArgumentException（require 抛出），CancellationException 等仍正常传播。
        val finalFlags = try {
            validateAspectRatio(width, height)
            if (flags != 0) flags else buildDefaultFlags()
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        return repository.createDisplay(name, width, height, dpi, finalFlags, mirrorDisplayId)
    }

    /**
     * 释放指定显示器。
     */
    suspend fun releaseDisplay(displayId: Int): Result<Unit> {
        return repository.releaseDisplay(displayId)
    }

    /**
     * 设置显示器 Surface（驱动视频解码器）。
     */
    suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> {
        return repository.setDisplaySurface(displayId, surface)
    }

    /**
     * 调整显示器尺寸。
     */
    suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> {
        return repository.resizeDisplay(displayId, width, height, dpi)
    }

    /**
     * 在指定显示器上启动应用，并记录到最近使用列表。
     */
    suspend fun launchApp(packageName: String, displayId: Int): Result<Int> {
        return repository.launchApp(packageName, displayId)
    }

    /**
     * 在指定显示器上启动 Launcher（主屏幕）。
     */
    suspend fun launchHome(displayId: Int): Result<Int> {
        return repository.launchHome(displayId)
    }

    /**
     * 查询远程设备上已安装应用列表。
     */
    suspend fun listApps(): Result<List<DeviceMessage.AppEntry>> {
        return repository.listApps()
    }

    /**
     * 注入输入事件（使用默认 displayId 0）。
     */
    suspend fun injectInput(event: InputEvent): Result<Boolean> {
        return repository.injectInput(event)
    }

    /**
     * 注入带 DisplayId 的输入事件。
     */
    suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> {
        return repository.injectInputWithDisplayId(event, displayId)
    }


    /**
     * 获取当前活动显示器 ID 列表。
     */
    suspend fun getActiveDisplayIds(): Result<IntArray> {
        return repository.getActiveDisplayIds()
    }

    // === 回调设置 ===

    fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?) {
        repository.setVideoConfigCallback(callback)
    }

    fun setPerformanceStatsCallback(callback: ((String) -> Unit)?) {
        repository.setPerformanceStatsCallback(callback)
    }

    // === 工具方法 ===

    /**
     * 刷新显示器列表缓存。
     */
    fun refreshDisplays() {
        repository.refreshDisplays()
    }

    /**
     * 判断 Shizuku 是否可用。
     */
    fun isShizukuAvailable(): Boolean = shizukuManager.isAvailable()

    /**
     * 销毁远程特权服务。
     */
    fun destroyService() {
        repository.destroyService()
    }

    // === 内部业务规则 ===

    /**
     * 校验宽高比不超过 [MAX_ASPECT_RATIO]。
     * @throws IllegalArgumentException 如果比例过大
     */
    private fun validateAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Invalid dimensions: ${width}x${height}" }
        val ratio = if (width > height) {
            width.toFloat() / height
        } else {
            height.toFloat() / width
        }
        require(ratio <= MAX_ASPECT_RATIO) {
            "Aspect ratio too extreme (max $MAX_ASPECT_RATIO, current ${String.format(java.util.Locale.US, "%.2f", ratio)})"
        }
    }

    /**
     * 从 AppSettings 读取 Flags 配置，计算默认的虚拟显示器标志位。
     */
    private suspend fun buildDefaultFlags(): Int {
        val flagValues = AppSettings.getFlags(context)
        var flagsSum = 0
        ALL_DISPLAY_FLAGS.forEach { flag ->
            if (flag.minSdk <= android.os.Build.VERSION.SDK_INT) {
                val isEnabled = flagValues[flag.key] ?: flag.isDefaultEnabled
                if (isEnabled) {
                    flagsSum = flagsSum or flag.bitValue
                }
            }
        }
        return flagsSum
    }
}
