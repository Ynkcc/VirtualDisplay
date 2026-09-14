package com.ynk.virtualdisplay.data.repository

import android.util.Log
import android.view.Surface
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.model.SavedDisplay
import com.ynk.virtualdisplay.domain.PrivilegeException
import com.ynk.virtualdisplay.domain.model.RemoteAppInfo
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.data.remote.DaemonRemoteDataSource
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.rpc.DaemonRpc
import com.ynk.virtualdisplay.util.NetUtils
import com.ynk.virtualdisplay.video.VideoDefaults
import com.ynk.virtualdisplay.video.VideoStreamController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * 单条连接的资源容器：持有全部连接资源与共享可变状态，并提供低层原语操作。
 *
 * ### 并发契约
 * 本类的**全部可变状态**（[isBound]、[currentStreamingDisplayId]、[decoderSurface]、
 * [currentVideoWidth]、[currentVideoHeight] 等）只允许在 [ConnectionSlot.slotDispatcher]
 * 这一单线程串行域上访问。所有公开入口（[ConnectionSlot] 的 suspend 方法、槽级 scope）
 * 都已收敛到该调度器，因此无需任何锁或 `@Volatile`。
 *
 * 阻塞式 IO（transport 建连、进程控制、socket 探测、codec 起停）一律显式切到
 * [Dispatchers.IO]，避免占住串行域导致其他任务饿死。
 */
internal class SlotResources(private val slot: ConnectionSlot) {

    private val node get() = slot.node
    private val settingsDataSource get() = slot.settingsDataSource
    private val scope get() = slot.scope

    val remoteDataSource: DaemonRemoteDataSource get() = slot.remoteDataSource
    val processDataSource: DaemonProcessDataSource? get() = slot.processDataSource
    val transport: DaemonTransport get() = slot.transport
    val rpc: DaemonRpc get() = slot.rpc
    val videoController: VideoStreamController get() = slot.videoController

    private val nodeKey get() = node.uniqueKey()

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

    var isBound = false
    var currentStreamingDisplayId = -1
    var decoderSurface: Surface? = null
    var currentVideoWidth = VideoDefaults.DEFAULT_WIDTH
    var currentVideoHeight = VideoDefaults.DEFAULT_HEIGHT

    /** 已拉取的应用列表缓存（按连接槽隔离，切换/断开节点时随槽销毁而失效） */
    val appsCache = AtomicReference<List<RemoteAppInfo>?>(null)

    // ----- 状态原语 -----

    fun setStatus(status: ConnectionStatus) { _connectionStatus.value = status }
    fun setError(error: String?) { _connectionError.value = error }
    fun resetDaemonPid() { _daemonPid.value = -1 }

    // ----- daemon 进程原语 -----

    /**
     * 连接/拉起目标快照：本机节点在调用时一次性读取监听配置（serverHost/serverPort/serverPassword），
     * 远程节点使用节点自身配置。
     */
    suspend fun snapshotConnectTarget(): ConnectTarget =
        if (node.isLocal) {
            ConnectTarget(
                host = NetUtils.resolveConnectHost(settingsDataSource.getServerHost()),
                port = settingsDataSource.getServerPort(),
                password = settingsDataSource.getServerPassword()
            )
        } else {
            ConnectTarget(NetUtils.resolveConnectHost(node.host), node.port, node.password)
        }

    /**
     * 拉起 daemon 用的绑定地址快照：本机节点返回用户配置的原始监听地址
     * （不做 resolveConnectHost 转换，0.0.0.0 必须原样传给 daemon 绑定）。
     */
    suspend fun snapshotBindAddress(): String = settingsDataSource.getServerHost()

    /**
     * 拉起本机 daemon。全应用唯一拉起入口（设置页"启动"），不在任何连接流程内自动触发。
     * 特权预检已收敛在 [DaemonProcessDataSource.startDaemon] 内部（唯一的特权检查点）。
     *
     * @throws [PrivilegeException] 拉起失败；[IllegalStateException] 当前节点不支持本地拉起。
     */
    suspend fun startDaemonProcess() {
        val proc = processDataSource
            ?: throw IllegalStateException("当前环境未提供进程数据源，无法拉起本机 daemon")
        val privilegeMode = settingsDataSource.getPrivilegeModeSync()
        if (!node.isLocal || privilegeMode == PrivilegeMode.NONE) {
            throw IllegalStateException("当前节点（${node.name}）不支持本地拉起 daemon")
        }
        val target = snapshotConnectTarget()
        val bindAddress = snapshotBindAddress()
        val started = withContext(Dispatchers.IO) { proc.startDaemon(target.port, bindAddress, target.password) }
        if (!started) throw PrivilegeException("启动服务端进程失败")
        _daemonPid.value = withContext(Dispatchers.IO) { proc.getDaemonPid() }
    }

    /**
     * 重连前确保 daemon 存活：缓存 PID 无效或目标端口未监听时按监听配置重启 daemon。
     * 仅在"本机 + 有特权模式"节点生效，远程节点直接视为就绪。
     *
     * @return true 表示 daemon 就绪可继续重连；false 表示拉起失败，应退避后重试。
     */
    suspend fun ensureDaemonAlive(target: ConnectTarget): Boolean {
        val privilegeMode = settingsDataSource.getPrivilegeModeSync()
        val proc = processDataSource
        if (node.isLocal && proc != null && privilegeMode != PrivilegeMode.NONE) {
            val (cachedPid, daemonListening) = withContext(Dispatchers.IO) {
                val pid = proc.getDaemonPid()
                val listening = try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(target.host, target.port), 300)
                        true
                    }
                } catch (_: Exception) {
                    false
                }
                pid to listening
            }

            if (cachedPid <= 0 || !daemonListening) {
                Log.i(ConnectionSlot.TAG, "[$nodeKey] Daemon not alive, restarting...")
                val started = withContext(Dispatchers.IO) {
                    runCatching { proc.startDaemon(target.port, snapshotBindAddress(), target.password) }
                        .getOrDefault(false)
                }
                if (!started) return false
                _daemonPid.value = withContext(Dispatchers.IO) { proc.getDaemonPid() }
            }
        } else {
            _daemonPid.value = -1
        }
        return true
    }

    suspend fun stopDaemonProcess() {
        withContext(Dispatchers.IO) { processDataSource?.stopDaemon() }
    }

    suspend fun exitDaemon() { remoteDataSource.exitDaemon() }

    // ----- transport / rpc 原语 -----

    /**
     * 尝试建立 transport 连接。
     * @return null 表示连接成功，否则为用户可读的错误消息（不在此处写状态，由调用方统一收敛）。
     */
    suspend fun connectTransport(target: ConnectTarget): String? =
        withContext(Dispatchers.IO) {
            try {
                transport.connect(
                    target.host, target.port, 5000,
                    secretToken = target.password.takeIf { it.isNotEmpty() }
                )
                null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(ConnectionSlot.TAG, "[$nodeKey] transport.connect threw", t)
                NetUtils.getFriendlyErrorMessage(t)
            }
        }

    /** 连接成功后标记已绑定、启动 RPC 消息循环并刷新显示状态。 */
    fun markConnected(rpcScope: CoroutineScope) {
        isBound = true
        rpc.startMessageLoop(rpcScope)
        _connectionStatus.value = ConnectionStatus.CONNECTED
        _connectionError.value = null
    }

    /**
     * 连接层失败（daemon 已就绪但 TCP/握手未就绪）后的状态收敛：
     * 设置兜底错误、进入 ERROR 状态，并**保持**绑定意图，使后台自动重连可继续自愈。
     */
    fun applyConnectFailure(target: ConnectTarget) {
        if (_connectionError.value == null) {
            _connectionError.value = "连接失败：无法建立到 ${target.host}:${target.port} 的连接。"
        }
        _connectionStatus.value = ConnectionStatus.ERROR
        isBound = true
    }

    fun stopMessageLoop() { rpc.stopMessageLoop() }

    suspend fun disconnectTransport() { transport.disconnect() }

    // ----- 视频流原语 -----

    suspend fun stopVideo(): Result<Unit> =
        runCatching { withContext(Dispatchers.IO) { videoController.stop() } }

    suspend fun stopStreaming() {
        Log.i(ConnectionSlot.TAG, "[$nodeKey] stopStreaming: stopping current stream (display=$currentStreamingDisplayId)")
        if (currentStreamingDisplayId == -1) return
        stopVideo().onFailure {
            Log.w(ConnectionSlot.TAG, "[$nodeKey] stopStreaming: videoController.stop failed", it)
        }
        clearStreamingState()
    }

    fun updateStreamingResolution(width: Int, height: Int) {
        currentVideoWidth = width
        currentVideoHeight = height
        videoController.updateResolution(width, height)
    }

    fun applyVideoConfig(width: Int, height: Int) {
        currentVideoWidth = width
        currentVideoHeight = height
    }

    suspend fun applyDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> {
        decoderSurface = surface
        return if (surface != null) {
            if (currentStreamingDisplayId != displayId) {
                startStreamingToDisplay(displayId)
            } else {
                runCatching { videoController.setSurface(surface) }
                    .fold({ Result.success(Unit) }, { Result.failure(it) })
            }
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

        val result = withContext(Dispatchers.IO) {
            videoController.start(displayId, decoderSurface, width, height)
        }
        if (result.isSuccess) {
            currentStreamingDisplayId = displayId
            currentVideoWidth = width
            currentVideoHeight = height
        }
        return result
    }

    private fun clearStreamingState() {
        currentStreamingDisplayId = -1
        currentVideoWidth = VideoDefaults.DEFAULT_WIDTH
        currentVideoHeight = VideoDefaults.DEFAULT_HEIGHT
    }

    // ----- 显示器操作（供 ConnectionSlot 门面调用） -----

    suspend fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        mirrorDisplayId: Int
    ): Result<Int> {
        val result = remoteDataSource.createDisplay(name, width, height, dpi, flags, mirrorDisplayId)
        result.onSuccess { displayId ->
            settingsDataSource.saveDisplayForServer(
                node, SavedDisplay(displayId, name, width, height, dpi, mirrorDisplayId)
            )
            refreshManagedDisplays()
        }
        return result
    }

    suspend fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean): Result<Unit> {
        val result = remoteDataSource.releaseDisplay(displayId, moveTasksToDefaultDisplay)
        result.onSuccess {
            settingsDataSource.removeDisplayForServer(node, displayId)
            if (currentStreamingDisplayId == displayId) stopStreaming()
            refreshManagedDisplays()
        }
        return result
    }

    suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> {
        val result = remoteDataSource.resizeDisplay(displayId, width, height, dpi)
        result.onSuccess {
            if (currentStreamingDisplayId == displayId) updateStreamingResolution(width, height)
        }
        return result
    }

    suspend fun launchApp(packageName: String, displayId: Int): Result<Int> {
        val result = remoteDataSource.startActivity(packageName, displayId)
        result.onSuccess { settingsDataSource.addRecentApp(packageName) }
        return result
    }

    suspend fun listApps(forceRefresh: Boolean): Result<List<RemoteAppInfo>> {
        if (!forceRefresh) {
            appsCache.get()?.let { return Result.success(it) }
        }
        val result = remoteDataSource.listApps()
        result.onSuccess { appsCache.set(it) }
        return result
    }

    suspend fun injectInputWithDisplayId(
        event: android.view.InputEvent,
        displayId: Int
    ): Result<Boolean> =
        remoteDataSource.injectInput(displayId, event, currentVideoWidth, currentVideoHeight)

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
                        updatedSaved.add(
                            SavedDisplay(
                                id = id,
                                name = if (id == 0) "主屏幕" else "Virtual Display $id",
                                width = info.width,
                                height = info.height,
                                dpi = info.dpi,
                                mirrorDisplayId = info.mirrorDisplayId,
                                isOwned = info.isOwned
                            )
                        )
                    } else {
                        val idx = updatedSaved.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val old = updatedSaved[idx]
                            if (old.mirrorDisplayId != info.mirrorDisplayId || old.width != info.width ||
                                old.height != info.height || old.dpi != info.dpi || old.isOwned != info.isOwned
                            ) {
                                updatedSaved[idx] = old.copy(
                                    width = info.width,
                                    height = info.height,
                                    dpi = info.dpi,
                                    mirrorDisplayId = info.mirrorDisplayId,
                                    isOwned = info.isOwned
                                )
                            }
                        }
                    }
                }
                settingsDataSource.setDisplaysForServer(node, updatedSaved)
                _managedDisplayIds.value = displayInfos.filter { it.isOwned }.map { it.displayId }.toSet()
                _displayOwners.value = displayInfos.associate {
                    it.displayId to DisplayOwner(it.ownerPackage, it.ownerUid)
                }
                if (node.isLocal) {
                    _daemonPid.value = withContext(Dispatchers.IO) {
                        processDataSource?.getDaemonPid() ?: -1
                    }
                }
            }.onFailure {
                Log.w(ConnectionSlot.TAG, "[$nodeKey] refreshManagedDisplays failed", it)
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
        clearStreamingState()
    }
}
