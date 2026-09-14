package com.ynk.virtualdisplay.data.repository

import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.data.remote.DaemonRemoteDataSource
import com.ynk.virtualdisplay.domain.model.ActiveDisplayInfo
import com.ynk.virtualdisplay.domain.model.RemoteAppInfo
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.rpc.DaemonRpc
import com.ynk.virtualdisplay.util.ExceptionUtils
import com.ynk.virtualdisplay.video.VideoStreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 单条服务端连接的全部资源封装。
 *
 * 每个 [ServerNode] 对应一个独立的 ConnectionSlot，作为对外门面实现 [IDisplayRepository]，
 * 内部拆分为两个内聚单元：
 *
 * 1. [SlotResources]：资源容器，持有 transport / rpc / remoteDataSource / videoController /
 *    processDataSource / appsCache 等全部资源，以及连接状态流、视频流状态等共享可变状态。
 *
 * 2. [ConnectionLifecycleController]：连接生命周期控制器，编排连接 / 重连 / daemon 拉起 /
 *    清理的时序，包括 auto-reconnect 退避、[ConnectionLifecycleController] 的清理幂等去重。
 *
 * ### 并发模型（本类最重要的契约）
 * 槽内所有可变状态都收敛在 [slotDispatcher] 这一个**单线程串行域**上：
 * - [scope] 基于该调度器创建 → 所有 `scope.launch` 的块天然串行；
 * - 本类所有 suspend 方法都用 `withContext(slotDispatcher)` 进入该域；
 * - 因此 [SlotResources] 的状态字段无需任何锁或 `@Volatile`（历史上的
 *   `isBound` 竞态、双锁 + 主线程 `runBlocking` 的 ANR 隐患由此消除）。
 *
 * 阻塞式 IO 一律在 [SlotResources] 内部显式切到 `Dispatchers.IO`，不占住串行域。
 *
 * [scope] 是"谁创建谁负责关闭"的槽级 Scope，在 [destroyService] 完成清理后由本槽
 * cancel；它与模块级 processScope（进程级守护 Scope，任何组件不得 cancel）严格区分。
 * [videoController] 使用独立的进程级 Scope 驱动 ping 循环，绝不由本槽 cancel。
 *
 * 注意：本槽对外只暴露 [IDisplayRepository] 的显示器操作能力；`setActiveNode` /
 * `connectNode` / `disconnectNode` 等节点编排方法由 [MultiConnectionRepository] 承担。
 */
class ConnectionSlot(
    val node: ServerNode,
    internal val settingsDataSource: AppSettingsDataSource,
    val remoteDataSource: DaemonRemoteDataSource,
    val processDataSource: DaemonProcessDataSource?,
    val transport: DaemonTransport,
    val rpc: DaemonRpc,
    val videoController: VideoStreamController
) : IDisplayRepository {

    companion object {
        internal const val TAG = "ConnectionSlot"
    }

    /** 槽内串行域：唯一允许访问 [SlotResources] 可变状态的调度器。 */
    internal val slotDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vd-slot-${node.uniqueKey()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    internal val scope = CoroutineScope(
        slotDispatcher + SupervisorJob() +
            ExceptionUtils.coroutineExceptionHandler("ConnectionSlot[${node.uniqueKey()}]")
    )

    private val resources = SlotResources(this)
    private val lifecycle = ConnectionLifecycleController(this, resources)

    init {
        lifecycle.startObserving()
    }

    // ====================== 对外状态流 ======================

    override val connectionStatus: StateFlow<ConnectionStatus> get() = resources.connectionStatus
    override val connectionError: StateFlow<String?> get() = resources.connectionError
    override val managedDisplayIds: StateFlow<Set<Int>> get() = resources.managedDisplayIds
    override val displayOwners: StateFlow<Map<Int, DisplayOwner>> get() = resources.displayOwners
    override val daemonPid: StateFlow<Int> get() = resources.daemonPid

    // ====================== 生命周期入口 ======================

    override fun bindService() = lifecycle.bindService()
    override fun unbindService() = lifecycle.unbindService()
    override suspend fun startDaemon(): Result<Unit> = withContext(slotDispatcher) { lifecycle.startDaemon() }
    override suspend fun stopDaemon(): Result<Unit> = withContext(slotDispatcher) { lifecycle.stopDaemon() }
    override suspend fun connect(): Result<Unit> = withContext(slotDispatcher) { lifecycle.connect() }
    override suspend fun disconnect(): Result<Unit> = withContext(slotDispatcher) { lifecycle.disconnect() }
    override fun destroyService() = lifecycle.destroyService()

    // ====================== 显示器操作 ======================

    override fun refreshDisplays() = resources.refreshManagedDisplays()

    override suspend fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        mirrorDisplayId: Int
    ): Result<Int> = withContext(slotDispatcher) {
        resources.createDisplay(name, width, height, dpi, flags, mirrorDisplayId)
    }

    override suspend fun releaseDisplay(
        displayId: Int,
        moveTasksToDefaultDisplay: Boolean
    ): Result<Unit> = withContext(slotDispatcher) {
        resources.releaseDisplay(displayId, moveTasksToDefaultDisplay)
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> =
        withContext(slotDispatcher) { resources.applyDisplaySurface(displayId, surface) }

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> =
        withContext(slotDispatcher) { resources.resizeDisplay(displayId, width, height, dpi) }

    override suspend fun stopStreaming() = withContext(slotDispatcher) { resources.stopStreaming() }

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> =
        withContext(slotDispatcher) { resources.launchApp(packageName, displayId) }

    override suspend fun launchHome(displayId: Int): Result<Int> =
        remoteDataSource.launchHome(displayId)

    override suspend fun listApps(forceRefresh: Boolean): Result<List<RemoteAppInfo>> =
        withContext(slotDispatcher) { resources.listApps(forceRefresh) }

    override suspend fun injectInput(event: InputEvent): Result<Boolean> =
        injectInputWithDisplayId(event, 0)

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> =
        withContext(slotDispatcher) { resources.injectInputWithDisplayId(event, displayId) }

    override suspend fun getAllDisplayIds(): Result<IntArray> =
        remoteDataSource.getAllDisplayIds()

    override suspend fun getActiveDisplayInfos(): Result<List<ActiveDisplayInfo>> =
        remoteDataSource.getActiveDisplayInfos()

    override fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?) {
        videoController.onVideoConfig = { _, w, h ->
            resources.applyVideoConfig(w, h)
            callback?.invoke(w, h)
        }
    }

    override fun setPerformanceStatsCallback(callback: ((String) -> Unit)?) {
        videoController.onPerformanceStats = callback
    }
}
