package com.ynk.virtualdisplay.data.repository

import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.model.SavedDisplay
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.data.remote.DaemonRemoteDataSource
import com.ynk.virtualdisplay.domain.PrivilegeException
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.rpc.DaemonRpc
import com.ynk.virtualdisplay.rpc.DaemonRpcState
import com.ynk.virtualdisplay.util.ExceptionUtils
import com.ynk.virtualdisplay.util.NetUtils
import com.ynk.virtualdisplay.video.VideoDefaults
import com.ynk.virtualdisplay.video.VideoStreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单条服务端连接的全部资源封装。
 *
 * 每个 [ServerNode] 对应一个独立的 ConnectionSlot，作为对外门面实现 [IDisplayRepository]，
 * 内部将职责拆分为两个内聚单元：
 *
 * 1. [SlotResources]（资源容器）：持有 transport / rpc / remoteDataSource / videoController /
 *    processDataSource / appsCache 等全部资源，以及连接状态流、视频流状态等共享可变状态，
 *    并提供低层原语操作（拉起 daemon、建 transport、启动/停止 RPC 循环、启停视频流等）。
 *
 * 2. [ConnectionLifecycleController]（连接生命周期控制器）：编排连接 / 重连 / daemon 拉起 /
 *    清理的时序，包括 auto-reconnect 退避、[performCleanup] 停止顺序、[launchCleanupOnce]
 *    幂等去重，以及 RPC 异常退出触发重连的监听。所有时序决策都在此收敛。
 *
 * 资源容器与生命周期控制器均通过本类持有，对外部只暴露 [IDisplayRepository] 接口，签名不变。
 *
 * ### 协程 Scope 归属
 * 本槽持有 [scope]：由 ConnectionSlot（自身）创建的可 cancel **槽级 Scope**，遵循
 * "谁创建资源谁负责关闭"原则，在 [destroyService] 中由本槽自行 cancel()。
 * 它与模块级 [com.ynk.virtualdisplay.di.AppModule.processScope]（进程级守护 Scope，任何
 * 组件不得 cancel）严格区分：槽级 Scope 随槽销毁而 cancel，进程级 Scope 跟随应用进程存活。
 * [videoController] 使用进程级 Scope 驱动 ping 循环，绝不由本槽 cancel。
 */
class ConnectionSlot(
    val node: ServerNode,
    private val settingsDataSource: AppSettingsDataSource,
    val remoteDataSource: DaemonRemoteDataSource,
    val processDataSource: DaemonProcessDataSource?,
    val transport: DaemonTransport,
    val rpc: DaemonRpc,
    val videoController: VideoStreamController,
    val scope: CoroutineScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            ExceptionUtils.coroutineExceptionHandler("ConnectionSlot[${node.uniqueKey()}]")
    )
) : IDisplayRepository {

    companion object {
        private const val TAG = "ConnectionSlot"
    }

    // ====================== 职责拆分：资源容器 + 生命周期控制器 ======================

    /** 资源容器：持有全部连接资源与共享可变状态。 */
    private val resources = SlotResources()

    /** 连接生命周期控制器：负责连接 / 重连 / 清理时序。 */
    private val lifecycle = ConnectionLifecycleController(resources)

    init {
        lifecycle.startObserving()
    }

    // ====================== 对外状态流（来自资源容器） ======================

    override val connectionStatus: StateFlow<ConnectionStatus> get() = resources.connectionStatus
    override val connectionError: StateFlow<String?> get() = resources.connectionError
    override val managedDisplayIds: StateFlow<Set<Int>> get() = resources.managedDisplayIds
    override val displayOwners: StateFlow<Map<Int, DisplayOwner>> get() = resources.displayOwners
    override val daemonPid: StateFlow<Int> get() = resources.daemonPid

    // ====================== 生命周期入口（委托给生命周期控制器） ======================

    override fun bindService() = lifecycle.bindService()
    override fun unbindService() = lifecycle.unbindService()
    override suspend fun startDaemon(): Result<Unit> = lifecycle.startDaemon()
    override suspend fun stopDaemon(): Result<Unit> = lifecycle.stopDaemon()
    override fun destroyService() = lifecycle.destroyService()

    // ====================== 显示器操作（基于资源容器的原语） ======================

    override fun refreshDisplays() = resources.refreshManagedDisplays()

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int, mirrorDisplayId: Int): Result<Int> {
        val result = remoteDataSource.createDisplay(name, width, height, dpi, flags, mirrorDisplayId)
        result.onSuccess { displayId ->
            settingsDataSource.saveDisplayForServer(node, SavedDisplay(displayId, name, width, height, dpi, mirrorDisplayId))
            resources.refreshManagedDisplays()
        }
        return result
    }

    override suspend fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean): Result<Unit> {
        val result = remoteDataSource.releaseDisplay(displayId, moveTasksToDefaultDisplay)
        result.onSuccess {
            settingsDataSource.removeDisplayForServer(node, displayId)
            if (resources.currentStreamingDisplayId == displayId) {
                resources.stopVideo()
                resources.resetStreaming()
            }
            resources.refreshManagedDisplays()
        }
        return result
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> =
        resources.applyDisplaySurface(displayId, surface)

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> {
        val result = remoteDataSource.resizeDisplay(displayId, width, height, dpi)
        result.onSuccess {
            if (resources.currentStreamingDisplayId == displayId) {
                resources.updateStreamingResolution(width, height)
            }
        }
        return result
    }

    override suspend fun stopStreaming() = resources.stopStreaming()

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> {
        val result = remoteDataSource.startActivity(packageName, displayId)
        result.onSuccess { settingsDataSource.addRecentApp(packageName) }
        return result
    }

    override suspend fun launchHome(displayId: Int): Result<Int> =
        remoteDataSource.launchHome(displayId)

    override suspend fun listApps(forceRefresh: Boolean): Result<List<DeviceMessage.AppEntry>> {
        resources.appsCache.get()?.let { cached ->
            if (!forceRefresh) return Result.success(cached)
        }
        val result = remoteDataSource.listApps()
        result.onSuccess { resources.appsCache.set(it) }
        return result
    }

    override suspend fun injectInput(event: InputEvent): Result<Boolean> =
        injectInputWithDisplayId(event, 0)

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> =
        remoteDataSource.injectInput(displayId, event, resources.currentVideoWidth, resources.currentVideoHeight)

    override suspend fun getActiveDisplayIds(): Result<IntArray> =
        remoteDataSource.getActiveDisplayIds()

    override suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>> =
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

    override fun setActiveNode(node: ServerNode) {}
    override fun connectNode(node: ServerNode) {}
    override fun disconnectNode(node: ServerNode, killDaemon: Boolean) {}
    override fun getSlot(nodeKey: String): IDisplayRepository? = if (node.uniqueKey() == nodeKey) this else null
    override fun allSlots(): Collection<IDisplayRepository> = listOf(this)

    // ======================================================================================
    // 资源容器：持有全部连接资源与共享可变状态，提供低层原语操作。
    // ======================================================================================
    private inner class SlotResources {

        // ----- 资源引用 -----
        val remoteDataSource: DaemonRemoteDataSource get() = this@ConnectionSlot.remoteDataSource
        val processDataSource: DaemonProcessDataSource? get() = this@ConnectionSlot.processDataSource
        val transport: DaemonTransport get() = this@ConnectionSlot.transport
        val rpc: DaemonRpc get() = this@ConnectionSlot.rpc
        val videoController: VideoStreamController get() = this@ConnectionSlot.videoController

        // ----- 共享连接状态 -----
        val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
        val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
        val _connectionError = MutableStateFlow<String?>(null)
        val connectionError: StateFlow<String?> = _connectionError.asStateFlow()
        val _managedDisplayIds = MutableStateFlow<Set<Int>>(emptySet())
        val managedDisplayIds: StateFlow<Set<Int>> = _managedDisplayIds.asStateFlow()
        val _displayOwners = MutableStateFlow<Map<Int, DisplayOwner>>(emptyMap())
        val displayOwners: StateFlow<Map<Int, DisplayOwner>> = _displayOwners.asStateFlow()
        val _daemonPid = MutableStateFlow(-1)
        val daemonPid: StateFlow<Int> = _daemonPid.asStateFlow()

        @Volatile var isBound = false
        var currentStreamingDisplayId: Int = -1
        var decoderSurface: Surface? = null
        var currentVideoWidth: Int = VideoDefaults.DEFAULT_WIDTH
        var currentVideoHeight: Int = VideoDefaults.DEFAULT_HEIGHT

        /** 已拉取的应用列表缓存（按连接槽隔离，切换/断开节点时随槽销毁而失效） */
        val appsCache = java.util.concurrent.atomic.AtomicReference<List<DeviceMessage.AppEntry>?>(null)

        // ----- 状态流写原语（供生命周期控制器调用） -----
        fun setStatus(status: ConnectionStatus) { _connectionStatus.value = status }
        fun setError(error: String?) { _connectionError.value = error }
        fun updateBound(bound: Boolean) { isBound = bound }
        fun updateDaemonPid(pid: Int) { _daemonPid.value = pid }
        fun resetDaemonPid() { _daemonPid.value = -1 }

        // ----- daemon 进程原语 -----

        /**
         * 拉起本机 daemon（仅"本机 + 有特权模式"节点才需要）。
         * 特权预检已收敛在 [DaemonProcessDataSource.startDaemon] 内部（唯一的特权检查点），
         * 这里只负责按是否为"本机拉起型节点"来决定是否启动，不再重复做权限检查。
         *
         * @return true 表示可继续连接；false 表示"自动启动服务端"被关闭且 daemon 未运行，应静默跳过。
         * @throws [PrivilegeException] 拉起失败。
         */
        suspend fun ensureDaemonRunning(
            host: String, port: Int, password: String,
            privilegeMode: PrivilegeMode, enforceAutoStart: Boolean
        ): Boolean {
            val proc = processDataSource
            if (node.isLocal && proc != null && privilegeMode != PrivilegeMode.NONE) {
                if (enforceAutoStart) {
                    val autoStart = settingsDataSource.getAutoStartServerSync()
                    val isRunning = withContext(Dispatchers.IO) { proc.getDaemonPid() > 0 }
                    if (!autoStart && !isRunning) {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        return false
                    }
                }
                val started = withContext(Dispatchers.IO) {
                    proc.startDaemon(port, node.host, password)
                }
                if (!started) throw PrivilegeException("启动服务端进程失败")
                _daemonPid.value = withContext(Dispatchers.IO) { proc.getDaemonPid() }
            } else {
                _daemonPid.value = -1
            }
            return true
        }

        /**
         * 重连前确保 daemon 存活：缓存 PID 无效或端口未监听时重启 daemon。
         * 仅在"本机 + 有特权模式"节点生效，远程节点直接视为就绪。
         *
         * @return true 表示 daemon 就绪可继续重连；false 表示拉起失败，应退避后重试。
         */
        suspend fun ensureDaemonAlive(host: String, port: Int, password: String): Boolean {
            val privilegeMode = settingsDataSource.getPrivilegeModeSync()
            val proc = processDataSource
            if (node.isLocal && proc != null && privilegeMode != PrivilegeMode.NONE) {
                val cachedPid = proc.getDaemonPid()
                val daemonListening = try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, port), 300)
                        true
                    }
                } catch (_: Exception) { false }

                if (cachedPid <= 0 || !daemonListening) {
                    Log.i(TAG, "[${node.uniqueKey()}] Daemon not alive, restarting...")
                    val started = runCatching {
                        proc.startDaemon(port, node.host, password)
                    }.getOrDefault(false)
                    if (!started) return false
                    _daemonPid.value = proc.getDaemonPid()
                }
            } else {
                _daemonPid.value = -1
            }
            return true
        }

        suspend fun stopDaemonProcess() { processDataSource?.stopDaemon() }

        suspend fun exitDaemon() { remoteDataSource.exitDaemon() }

        // ----- transport / rpc 原语 -----

        /**
         * 尝试建立 transport 连接。仅在抛异常时记录错误消息，供上层判定失败原因。
         * @return 是否连接成功。
         */
        suspend fun connectTransport(host: String, port: Int, password: String): Boolean = try {
            transport.connect(host, port, 5000, secretToken = password.takeIf { it.isNotEmpty() })
        } catch (t: Throwable) {
            Log.w(TAG, "[${node.uniqueKey()}] transport.connect threw", t)
            _connectionError.value = NetUtils.getFriendlyErrorMessage(t)
            false
        }

        /** 连接成功后标记已绑定、启动 RPC 消息循环并刷新显示状态。 */
        fun markConnected(rpcScope: CoroutineScope) {
            isBound = true
            rpc.startMessageLoop(rpcScope)
            _connectionStatus.value = ConnectionStatus.CONNECTED
            _connectionError.value = null
        }

        /**
         * 连接层失败（daemon 已拉起但 TCP/握手未就绪）后的状态收敛：
         * 设置兜底错误、进入 ERROR 状态并释放绑定意图（使后台重连可自愈）。
         */
        fun applyConnectFailure(host: String, port: Int) {
            if (_connectionError.value == null) {
                _connectionError.value = "连接失败：无法建立到 $host:$port 的连接。"
                _connectionStatus.value = ConnectionStatus.ERROR
            }
            isBound = true
        }

        fun stopMessageLoop() { rpc.stopMessageLoop() }
        suspend fun disconnectTransport() { transport.disconnect() }

        // ----- 视频流原语 -----

        suspend fun stopVideo() = runCatching { videoController.stop() }

        suspend fun stopStreaming() {
            Log.i(TAG, "[${node.uniqueKey()}] stopStreaming: stopping current stream (display=$currentStreamingDisplayId)")
            if (currentStreamingDisplayId == -1) return
            stopVideo().onFailure {
                Log.w(TAG, "[${node.uniqueKey()}] stopStreaming: videoController.stop failed", it)
            }
            currentStreamingDisplayId = -1
            currentVideoWidth = VideoDefaults.DEFAULT_WIDTH
            currentVideoHeight = VideoDefaults.DEFAULT_HEIGHT
        }

        fun resetStreaming() {
            currentStreamingDisplayId = -1
        }

        fun updateStreamingResolution(width: Int, height: Int) {
            currentVideoWidth = width; currentVideoHeight = height
            videoController.updateResolution(width, height)
        }

        fun applyVideoConfig(width: Int, height: Int) {
            currentVideoWidth = width; currentVideoHeight = height
        }

        suspend fun applyDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> {
            decoderSurface = surface
            return if (surface != null) {
                if (currentStreamingDisplayId != displayId) startStreamingToDisplay(displayId)
                else { runCatching { videoController.setSurface(surface) }.fold({ Result.success(Unit) }, { Result.failure(it) }) }
            } else {
                runCatching { videoController.setSurface(null) }
                Result.success(Unit)
            }
        }

        private suspend fun startStreamingToDisplay(displayId: Int): Result<Unit> {
            if (currentStreamingDisplayId == displayId) return Result.success(Unit)
            if (currentStreamingDisplayId != -1) stopVideo()

            // 用真实 VD 尺寸配置解码器，而不是固定 VideoDefaults.DEFAULT_WIDTH/HEIGHT：视频全屏切横屏后
            // VD 尺寸会变化（未必是 1920x1080），若解码器仍以固定尺寸创建，硬件解码器会对
            // 不匹配的帧输出错误的 crop/可见区域，表现为画面只显示一半。
            var width = VideoDefaults.DEFAULT_WIDTH
            var height = VideoDefaults.DEFAULT_HEIGHT
            runCatching {
                remoteDataSource.getActiveDisplayInfos().getOrNull()
                    ?.firstOrNull { it.displayId == displayId }
                    ?.let { info ->
                        if (info.width > 0 && info.height > 0) {
                            width = info.width
                            height = info.height
                        }
                    }
            }

            val result = videoController.start(displayId, decoderSurface, width, height)
            if (result.isSuccess) {
                currentStreamingDisplayId = displayId
                currentVideoWidth = width
                currentVideoHeight = height
            }
            return result
        }

        // ----- 显示器状态刷新 -----

        fun refreshManagedDisplays() {
            scope.launch {
                if (_connectionStatus.value != ConnectionStatus.CONNECTED) return@launch
                remoteDataSource.getActiveDisplayInfos().onSuccess { displayInfos ->
                    val saved = settingsDataSource.getDisplaysForServer(node)
                    val activeInfosMap = displayInfos.associateBy { it.displayId }
                    val updatedSaved = saved.filter { it.id in activeInfosMap.keys }.toMutableList()
                    val savedIds = updatedSaved.map { it.id }.toSet()
                    activeInfosMap.forEach { (id, info) ->
                        if (id !in savedIds) {
                            updatedSaved.add(SavedDisplay(
                                id = id, name = if (id == 0) "主屏幕" else "Virtual Display $id",
                                width = info.width, height = info.height, dpi = info.dpi,
                                mirrorDisplayId = info.mirrorDisplayId, isOwned = info.isOwned
                            ))
                        } else {
                            val idx = updatedSaved.indexOfFirst { it.id == id }
                            if (idx >= 0) {
                                val old = updatedSaved[idx]
                                if (old.mirrorDisplayId != info.mirrorDisplayId || old.width != info.width ||
                                    old.height != info.height || old.dpi != info.dpi || old.isOwned != info.isOwned) {
                                    updatedSaved[idx] = old.copy(width = info.width, height = info.height,
                                        dpi = info.dpi, mirrorDisplayId = info.mirrorDisplayId, isOwned = info.isOwned)
                                }
                            }
                        }
                    }
                    settingsDataSource.setDisplaysForServer(node, updatedSaved)
                    _managedDisplayIds.value = displayInfos.filter { it.isOwned }.map { it.displayId }.toSet()
                    _displayOwners.value = displayInfos.associate {
                        it.displayId to DisplayOwner(it.ownerPackage, it.ownerUid)
                    }
                    if (node.isLocal) _daemonPid.value = processDataSource?.getDaemonPid() ?: -1
                }.onFailure {
                    Log.w(TAG, "[${node.uniqueKey()}] refreshManagedDisplays failed", it)
                    _managedDisplayIds.value = emptySet()
                    _displayOwners.value = emptyMap()
                }
            }
        }

        // ----- 清理：统一收敛连接状态 -----
        fun resetConnectionState() {
            _connectionError.value = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _managedDisplayIds.value = emptySet()
            _displayOwners.value = emptyMap()
            currentStreamingDisplayId = -1
        }
    }

    // ======================================================================================
    // 连接生命周期控制器：编排连接 / 重连 / daemon 拉起 / 清理时序。
    // ======================================================================================
    private inner class ConnectionLifecycleController(private val res: SlotResources) {

        private val reconnectLock = Any()
        @Volatile private var reconnectJob: Job? = null

        private val cleanupLock = Any()
        private var cleanupJob: Job? = null

        /** 监听 RPC 消息循环异常退出，触发自动重连。 */
        fun startObserving() {
            scope.launch {
                res.rpc.connectionState.collect { state ->
                    if (state == DaemonRpcState.LOOP_EXITED_ABNORMALLY && res.isBound) {
                        Log.w(TAG, "[${node.uniqueKey()}] RPC loop exited abnormally, scheduling auto-reconnect")
                        scheduleAutoReconnect()
                    }
                }
            }
        }

        fun bindService() {
            if (res.isBound) return
            scope.launch {
                connectInternal(enforceAutoStart = true)
            }
        }

        fun unbindService() {
            if (!res.isBound) return
            res.updateBound(false)
            launchCleanupOnce(killDaemon = false)
        }

        /**
         * 启动并建立服务端连接的核心流程。
         *
         * 划分为三个清晰步骤（均委托资源容器原语，不再"一锅烩"）：
         * 1. 拉起 daemon（仅本机特权节点）；
         * 2. 建立 transport 连接；
         * 3. 标记连接成功并刷新显示 / 或标记失败并进入后台自动重连。
         *
         * @param enforceAutoStart 仅自动启动入口为 true：受 "自动启动服务端" 开关约束，
         * 关闭且 daemon 未运行时静默跳过。手动启动（[startDaemon]）传 false，强制拉起
         * daemon 并连接，不受开关影响。
         * @return 启动+连接结果，失败时 [Result.failure] 携带用户可读的中文错误。
         */
        private suspend fun connectInternal(enforceAutoStart: Boolean): Result<Unit> {
            res.setStatus(ConnectionStatus.BINDING)
            val host = if (node.host == "0.0.0.0") "127.0.0.1" else node.host
            val port = node.port
            val password = node.password
            val privilegeMode = settingsDataSource.getPrivilegeModeSync()

            try {
                // 步骤 1：拉起 daemon；返回 false 表示应静默跳过（自动启动被关闭且 daemon 未运行）
                if (!res.ensureDaemonRunning(host, port, password, privilegeMode, enforceAutoStart)) {
                    res.setStatus(ConnectionStatus.DISCONNECTED)
                    return Result.success(Unit)
                }

                // 步骤 2：建立 transport 连接
                val connected = res.connectTransport(host, port, password)

                // 步骤 3：成功则标记连接并刷新显示；失败则进入后台自动重连自愈
                if (connected) {
                    res.markConnected(scope)
                    res.refreshManagedDisplays()
                } else {
                    res.applyConnectFailure(host, port)
                    scheduleAutoReconnect()
                }
                return Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "[${node.uniqueKey()}] connectInternal failed", e)
                res.setError(NetUtils.getFriendlyErrorMessage(e))
                res.setStatus(ConnectionStatus.ERROR)
                // 权限/启动类异常无法通过重连自愈，撤销绑定意图（否则 bindService 将被短路）。
                res.updateBound(false)
                return Result.failure(e)
            }
        }

        /**
         * 后台自动重连：带指数退避的 daemon 存活检查 + transport 重连。
         * 以 [res.isBound] 作为外层守卫，解绑 / 清理时会取消该 Job 并退出循环。
         */
        private fun scheduleAutoReconnect() {
            synchronized(reconnectLock) {
                val existing = reconnectJob
                if (existing != null && existing.isActive) return
                reconnectJob = scope.launch(Dispatchers.IO) {
                    val host = NetUtils.resolveConnectHost(node.host)
                    val port = node.port
                    val password = node.password

                    var attempt = 0
                    val baseDelayMs = 200L
                    val maxDelayMs = 3000L
                    val maxRemoteAttempts = if (node.isLocal) Int.MAX_VALUE else 5

                    while (res.isBound) {
                        attempt++
                        val delayMs = (baseDelayMs * (1L shl (attempt - 1))).coerceAtMost(maxDelayMs)
                        res.setStatus(ConnectionStatus.RECONNECTING)

                        // 确保 daemon 存活（仅本机特权节点）；拉起失败则退避后重试
                        if (!res.ensureDaemonAlive(host, port, password)) {
                            delay(delayMs)
                            continue
                        }

                        Log.i(TAG, "[${node.uniqueKey()}] Reconnecting transport (attempt $attempt)...")
                        val connected = res.connectTransport(host, port, password)
                        if (connected) {
                            res.markConnected(this@launch)
                            res.refreshManagedDisplays()
                            Log.i(TAG, "[${node.uniqueKey()}] Reconnect succeeded on attempt $attempt")
                            return@launch
                        }
                        Log.w(TAG, "[${node.uniqueKey()}] Reconnect failed (attempt $attempt), backoff ${delayMs}ms")
                        if (attempt >= maxRemoteAttempts) {
                            Log.w(TAG, "[${node.uniqueKey()}] Max attempts reached, giving up")
                            res.setError("服务端已断开，请手动重连")
                            res.setStatus(ConnectionStatus.DISCONNECTED)
                            // 放弃重连后释放绑定意图，允许后续手动重选节点再次触发连接
                            res.updateBound(false)
                            return@launch
                        }
                        delay(delayMs)
                    }
                }
            }
        }

        /** 幂等去重的清理任务：同时刻仅执行一次清理。 */
        private fun launchCleanupOnce(killDaemon: Boolean): Job = synchronized(cleanupLock) {
            val existing = cleanupJob
            if (existing != null && existing.isActive) existing
            else scope.launch { performCleanup(killDaemon) }.also { cleanupJob = it }
        }

        /**
         * 统一清理流程。停止顺序必须严格保持：
         * reconnectJob.cancel → videoController.stop → exitDaemon → rpc.stopMessageLoop
         * → transport.disconnect → stopDaemon。
         */
        private suspend fun performCleanup(killDaemon: Boolean) {
            res.updateBound(false)
            runCatching { reconnectJob?.cancel() }
            try { res.stopVideo() } catch (e: Exception) { Log.w(TAG, "stop video failed", e) }
            if (killDaemon) {
                try { res.exitDaemon(); delay(100) }
                catch (e: Exception) { Log.w(TAG, "exitDaemon failed", e) }
            }
            res.stopMessageLoop()
            res.disconnectTransport()
            if (killDaemon && res.processDataSource != null) {
                withContext(Dispatchers.IO) { res.stopDaemonProcess() }
                res.resetDaemonPid()
            }
            res.resetConnectionState()
        }

        suspend fun startDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
            if (res.connectionStatus.value == ConnectionStatus.CONNECTED) return@withContext Result.success(Unit)
            if (res.isBound) {
                val job = withContext(Dispatchers.Main) {
                    res.updateBound(false)
                    launchCleanupOnce(killDaemon = false)
                }
                withTimeoutOrNull(1000) { job.join() }
            }
            // 手动启动强制拉起 daemon 并连接，不受 "自动启动服务端" 开关约束。
            connectInternal(enforceAutoStart = false)
        }

        suspend fun stopDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
            runCatching {
                val job = withContext(Dispatchers.Main) {
                    res.updateBound(false)
                    launchCleanupOnce(killDaemon = true)
                }
                withTimeoutOrNull(3000) { job.join() }
            }.map { }
        }

        fun destroyService() {
            res.updateBound(false)
            val job = launchCleanupOnce(killDaemon = true)
            runCatching { runBlocking { withTimeoutOrNull(3000) { job.join() } } }
            scope.cancel()
        }
    }
}
