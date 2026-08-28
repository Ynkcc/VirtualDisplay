package com.ynk.virtualdisplay.domain

import android.util.Log
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import com.ynk.virtualdisplay.data.model.SavedDisplay
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.data.repository.DisplayOwner
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.ui.main.DisplayInfoModel
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
    private val repository: IDisplayRepository,
    private val processDataSource: DaemonProcessDataSource,
    private val settingsDataSource: AppSettingsDataSource
) {
    companion object {
        private const val TAG = "DisplayInteractor"
        private const val MAX_ASPECT_RATIO = 2.5f
    }

    // === 状态流代理 ===

    val connectionStatus: StateFlow<ConnectionStatus> = repository.connectionStatus
    val connectionError: StateFlow<String?> = repository.connectionError
    val managedDisplayIds: StateFlow<Set<Int>> = repository.managedDisplayIds
    val displayOwners: StateFlow<Map<Int, DisplayOwner>> = repository.displayOwners
    val daemonPid: StateFlow<Int> = repository.daemonPid

    // === 服务生命周期 ===

    /**
     * 绑定并启动守护进程服务。
     *
     * 权限（SHIZUKU / ROOT）的统一预检收敛在数据层的
     * [com.ynk.virtualdisplay.data.repository.ConnectionSlot.connectInternal]，
     * 即真正拉起 daemon 的唯一汇聚点，这里不再重复检查。
     */
    fun bindService() {
        repository.bindService()
    }

    /** 解绑服务（透传 [IDisplayRepository.unbindService]）。 */
    fun unbindService() {
        repository.unbindService()
    }

    /**
     * 重连：先 unbind 清理旧连接，再 bind 建立新连接。
     * 当 connectionStatus == BINDING 时忽略，防止重复触发。
     */
    fun reconnect() {
        if (repository.connectionStatus.value == ConnectionStatus.BINDING) {
            Log.d(TAG, "reconnect: already binding, skip")
            return
        }
        repository.unbindService()
        repository.bindService()
    }

    /** 切换活跃节点（透传 [IDisplayRepository.setActiveNode]）。 */
    fun setActiveNode(node: com.ynk.virtualdisplay.data.ServerNode) {
        repository.setActiveNode(node)
    }

    /**
     * 获取指定节点的仓库槽位。
     *
     * 门面导航：透传 [IDisplayRepository.getSlot]，供上层（如 DisplayActivity）
     * 获取指定节点的真实 [IDisplayRepository] 后直连操作视频/输入等底层能力。
     */
    fun getSlot(nodeKey: String): IDisplayRepository? {
        return repository.getSlot(nodeKey)
    }

    /**
     * 重启守护进程：完整停止（杀进程+断连）→ 延迟 → 重新启动并连接。
     *
     * 重新启动阶段复用 [startDaemon]，权限预检统一由数据层汇聚点
     * [com.ynk.virtualdisplay.data.repository.ConnectionSlot.connectInternal] 负责。
     */
    suspend fun restartDaemon(): Result<Unit> {
        val stopped = repository.stopDaemon()
        if (stopped.isFailure) return stopped
        delay(500)
        return startDaemon()
    }

    /**
     * 启动服务端进程并建立连接（手动启动入口）。
     *
     * 权限（SHIZUKU / ROOT）预检统一收敛在数据层汇聚点
     * [com.ynk.virtualdisplay.data.repository.ConnectionSlot.connectInternal]，
     * 未授权时会抛出 [PrivilegeException] 并由其转为 [Result.failure]，供 ViewModel 提示用户。
     */
    suspend fun startDaemon(): Result<Unit> = repository.startDaemon()

    /**
     * 检测当前设备是否可用 Root（通过 [su] 探测）。
     * 委托给进程控制数据源，阻塞 IO 线程，仅在后台线程调用。
     */
    suspend fun isRootAvailable(): Boolean = processDataSource.isRootAvailable()

    /** 停止服务端进程并断开连接（透传 [IDisplayRepository.stopDaemon]）。 */
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

    /** 释放指定显示器（透传 [IDisplayRepository.releaseDisplay]）。
     *
     * @param moveTasksToDefaultDisplay true 销毁前将应用移回主屏；false 完全交给系统处理（应用可能被直接关闭）。
     */
    suspend fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean = true): Result<Unit> {
        return repository.releaseDisplay(displayId, moveTasksToDefaultDisplay)
    }

    /** 在指定显示器上启动应用，并记录到最近使用列表（透传 [IDisplayRepository.launchApp]）。 */
    suspend fun launchApp(packageName: String, displayId: Int): Result<Int> {
        return repository.launchApp(packageName, displayId)
    }

    /** 在指定显示器上启动 Launcher（主屏幕，透传 [IDisplayRepository.launchHome]）。 */
    suspend fun launchHome(displayId: Int): Result<Int> {
        return repository.launchHome(displayId)
    }

    /** 查询远程设备上已安装应用列表（透传 [IDisplayRepository.listApps]）。
     *
     * @param forceRefresh 为 true 时忽略缓存强制向 daemon 重新拉取（用于下拉刷新）
     */
    suspend fun listApps(forceRefresh: Boolean = false): Result<List<DeviceMessage.AppEntry>> {
        return repository.listApps(forceRefresh)
    }

    // === 工具方法 ===

    /** 刷新显示器列表缓存（透传 [IDisplayRepository.refreshDisplays]）。 */
    fun refreshDisplays() {
        repository.refreshDisplays()
    }

    /**
     * 将本地保存的显示器列表与运行时所有权/托管状态组装为 UI 模型。
     *
     * 下沉自 MainViewModel.refreshDisplaysInternal 的字段搬运逻辑：
     * - 服务端实时上报的 managedByService 是「本 app 创建/接管」的唯一权威来源，
     *   isOwned 以它为准（而非磁盘上可能过期的 SavedDisplay.isOwned）。
     * - 孤儿判定基于同样的权威集合。
     */
    fun buildDisplayModels(
        savedDisplays: List<SavedDisplay>,
        owners: Map<Int, DisplayOwner>,
        managedByService: Set<Int>,
        isConnected: Boolean
    ): DisplayModelsResult {
        val displaysList = savedDisplays.map { display ->
            val owner = owners[display.id]
            DisplayInfoModel(
                id = display.id,
                name = display.name,
                width = display.width,
                height = display.height,
                dpi = display.dpi,
                mirrorDisplayId = display.mirrorDisplayId,
                isOwned = display.id in managedByService,
                ownerPackage = owner?.packageName,
                ownerUid = owner?.uid ?: 0
            )
        }
        val orphans = if (isConnected) {
            displaysList.filter { it.id !in managedByService }.map { it.id }
        } else {
            emptyList()
        }
        return DisplayModelsResult(displays = displaysList, orphanDisplayIds = orphans)
    }

    /**
     * 生成默认显示器名称（简化后的展示名称）。
     */
    fun defaultDisplayName(mirrorDisplayId: Int): String =
        if (mirrorDisplayId >= 0) "Mirror_VD_$mirrorDisplayId" else "VD"

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
        val flagValues = settingsDataSource.getFlags()
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

/**
 * 显示器列表组装结果：UI 模型列表 + 孤儿显示器 id 列表。
 */
data class DisplayModelsResult(
    val displays: List<DisplayInfoModel>,
    val orphanDisplayIds: List<Int>
)
