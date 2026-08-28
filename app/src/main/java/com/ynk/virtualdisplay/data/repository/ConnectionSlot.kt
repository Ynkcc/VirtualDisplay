package com.ynk.virtualdisplay.data.repository

import android.content.Context
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.model.SavedDisplay
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.data.remote.DaemonRemoteDataSource
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.rpc.DaemonRpc
import com.ynk.virtualdisplay.rpc.DaemonRpcState
import com.ynk.virtualdisplay.util.ExceptionUtils
import com.ynk.virtualdisplay.video.VideoStreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * 每个 [ServerNode] 对应一个独立的 ConnectionSlot，持有：
 * - [transport]         → 独立 TCP socket（negotiation / control / video）
 * - [rpc]              → 独立 RPC 消息循环
 * - [remoteDataSource]  → 独立 RPC 控制调用
 * - [videoController]   → 独立视频解码器
 * - [processDataSource] → 仅本机节点（[node].isLocal）持有，远程为 null
 *
 * 实现 [IDisplayRepository] 接口，可直接注入到 [DisplayActivity] 等消费方。
 */
class ConnectionSlot(
    val node: ServerNode,
    private val context: Context,
    private val settingsDataSource: AppSettingsDataSource,
    val remoteDataSource: DaemonRemoteDataSource,
    val processDataSource: DaemonProcessDataSource?,
    val transport: DaemonTransport,
    val rpc: DaemonRpc,
    val videoController: VideoStreamController,
    private val shizukuManager: ShizukuManager,
    val scope: CoroutineScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            ExceptionUtils.coroutineExceptionHandler("ConnectionSlot[${node.uniqueKey()}]")
    )
) : IDisplayRepository {

    companion object {
        private const val TAG = "ConnectionSlot"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
    }

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    override val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _managedDisplayIds = MutableStateFlow<Set<Int>>(emptySet())
    override val managedDisplayIds: StateFlow<Set<Int>> = _managedDisplayIds.asStateFlow()

    private val _displayOwners = MutableStateFlow<Map<Int, DisplayOwner>>(emptyMap())
    override val displayOwners: StateFlow<Map<Int, DisplayOwner>> = _displayOwners.asStateFlow()

    private val _daemonPid = MutableStateFlow(-1)
    override val daemonPid: StateFlow<Int> = _daemonPid.asStateFlow()

    @Volatile var isBound = false
        private set
    private var currentStreamingDisplayId: Int = -1
    private var decoderSurface: Surface? = null
    private var currentVideoWidth: Int = DEFAULT_WIDTH
    private var currentVideoHeight: Int = DEFAULT_HEIGHT

    private val reconnectLock = Any()
    @Volatile private var reconnectJob: Job? = null

    init {
        scope.launch {
            rpc.connectionState.collect { state ->
                if (state == DaemonRpcState.LOOP_EXITED_ABNORMALLY && isBound) {
                    Log.w(TAG, "[${node.uniqueKey()}] RPC loop exited abnormally, scheduling auto-reconnect")
                    scheduleAutoReconnect()
                }
            }
        }
    }

    private fun scheduleAutoReconnect() {
        synchronized(reconnectLock) {
            val existing = reconnectJob
            if (existing != null && existing.isActive) return
            reconnectJob = scope.launch(Dispatchers.IO) {
                val host = if (node.host == "0.0.0.0") "127.0.0.1" else node.host
                val port = node.port
                val password = node.password

                var attempt = 0
                val baseDelayMs = 200L
                val maxDelayMs = 3000L
                val maxRemoteAttempts = if (node.isLocal) Int.MAX_VALUE else 5

                while (isBound) {
                    attempt++
                    val delayMs = (baseDelayMs * (1L shl (attempt - 1))).coerceAtMost(maxDelayMs)
                    _connectionStatus.value = ConnectionStatus.RECONNECTING

                    if (node.isLocal && processDataSource != null) {
                        val privilegeMode = AppSettings.getPrivilegeModeSync()
                        if (privilegeMode != PrivilegeMode.NONE) {
                            val cachedPid = processDataSource.getDaemonPid()
                            val daemonListening = try {
                                java.net.Socket().use { s ->
                                    s.connect(java.net.InetSocketAddress(host, port), 300)
                                    true
                                }
                            } catch (_: Exception) { false }

                            if (cachedPid <= 0 || !daemonListening) {
                                Log.i(TAG, "[${node.uniqueKey()}] Daemon not alive, restarting...")
                                val started = processDataSource.startDaemon(port, node.host, password)
                                if (!started) {
                                    kotlinx.coroutines.delay(delayMs)
                                    continue
                                }
                                _daemonPid.value = processDataSource.getDaemonPid()
                            }
                        }
                    } else {
                        _daemonPid.value = -1
                    }

                    Log.i(TAG, "[${node.uniqueKey()}] Reconnecting transport (attempt $attempt)...")
                    val connected = try {
                        transport.connect(host, port, 5000, secretToken = password.takeIf { it.isNotEmpty() })
                    } catch (t: Throwable) {
                        Log.w(TAG, "[${node.uniqueKey()}] transport.connect threw", t)
                        _connectionError.value = com.ynk.virtualdisplay.util.NetUtils.getFriendlyErrorMessage(t)
                        false
                    }
                    if (connected) {
                        rpc.startMessageLoop(this@launch)
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        _connectionError.value = null
                        refreshManagedDisplays()
                        Log.i(TAG, "[${node.uniqueKey()}] Reconnect succeeded on attempt $attempt")
                        return@launch
                    }
                    Log.w(TAG, "[${node.uniqueKey()}] Reconnect failed (attempt $attempt), backoff ${delayMs}ms")
                    if (attempt >= maxRemoteAttempts) {
                        Log.w(TAG, "[${node.uniqueKey()}] Max attempts reached, giving up")
                        _connectionError.value = "服务端已断开，请手动重连"
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        // 放弃重连后释放绑定意图，允许后续手动重选节点再次触发连接
                        isBound = false
                        return@launch
                    }
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
    }

    private val cleanupLock = Any()
    private var cleanupJob: Job? = null

    private fun launchCleanupOnce(killDaemon: Boolean): Job = synchronized(cleanupLock) {
        val existing = cleanupJob
        if (existing != null && existing.isActive) existing
        else scope.launch { performCleanup(killDaemon) }.also { cleanupJob = it }
    }

    override fun bindService() {
        if (isBound) return
        scope.launch {
            connectInternal(enforceAutoStart = true)
        }
    }

    /**
     * 启动并建立服务端连接的核心流程。
     *
     * @param enforceAutoStart 仅自动启动入口为 true：受 "自动启动服务端" 开关约束，
     * 关闭且 daemon 未运行时静默跳过。手动启动（[startDaemon]）传 false，强制拉起
     * daemon 并连接，不受开关影响。
     * @return 启动+连接结果，失败时 [Result.failure] 携带用户可读的中文错误。
     */
    private suspend fun connectInternal(enforceAutoStart: Boolean): Result<Unit> {
        _connectionStatus.value = ConnectionStatus.BINDING
        val host = if (node.host == "0.0.0.0") "127.0.0.1" else node.host
        val port = node.port
        val password = node.password
        val privilegeMode = AppSettings.getPrivilegeModeSync()

        try {
            if (node.isLocal && processDataSource != null && privilegeMode != PrivilegeMode.NONE) {
                if (privilegeMode == PrivilegeMode.SHIZUKU && !shizukuManager.isAvailable()) {
                    throw com.ynk.virtualdisplay.domain.PrivilegeException("Shizuku 未就绪，请先启动 Shizuku 并授权")
                }
                if (enforceAutoStart) {
                    val autoStart = AppSettings.getAutoStartServerSync()
                    val isRunning = withContext(Dispatchers.IO) { processDataSource.getDaemonPid() > 0 }
                    if (!autoStart && !isRunning) {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        return Result.success(Unit)
                    }
                }
                val started = withContext(Dispatchers.IO) {
                    processDataSource.startDaemon(port, node.host, password)
                }
                if (!started) {
                    throw com.ynk.virtualdisplay.domain.PrivilegeException("启动服务端进程失败")
                }
                _daemonPid.value = withContext(Dispatchers.IO) { processDataSource.getDaemonPid() }
            } else {
                _daemonPid.value = -1
            }

            val connected = try {
                transport.connect(host, port, 5000, secretToken = password.takeIf { it.isNotEmpty() })
            } catch (t: Throwable) {
                val err = com.ynk.virtualdisplay.util.NetUtils.getFriendlyErrorMessage(t)
                _connectionError.value = err
                _connectionStatus.value = ConnectionStatus.ERROR
                false
            }
            if (connected) {
                isBound = true
                rpc.startMessageLoop(scope)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _connectionError.value = null
                refreshManagedDisplays()
            } else {
                if (_connectionError.value == null) {
                    _connectionError.value = "连接失败：无法建立到 ${host}:${port} 的连接。"
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
                // 连接层失败（daemon 已拉起但 TCP/握手未就绪）：进入后台自动重连自愈，
                // 避免首屏连接失败后必须手动重选节点才能恢复。
                isBound = true
                scheduleAutoReconnect()
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "[${node.uniqueKey()}] connectInternal failed", e)
            val err = com.ynk.virtualdisplay.util.NetUtils.getFriendlyErrorMessage(e)
            _connectionError.value = err
            _connectionStatus.value = ConnectionStatus.ERROR
            // 权限/启动类异常无法通过重连自愈，撤销绑定意图（否则 bindService 将被短路）。
            isBound = false
            return Result.failure(e)
        }
    }

    override fun unbindService() {
        if (!isBound) return
        isBound = false
        launchCleanupOnce(killDaemon = false)
    }

    private suspend fun performCleanup(killDaemon: Boolean) {
        isBound = false
        runCatching { reconnectJob?.cancel() }
        try { videoController.stop() } catch (e: Exception) { Log.w(TAG, "stop video failed", e) }
        if (killDaemon) {
            try { remoteDataSource.exitDaemon(); kotlinx.coroutines.delay(100) }
            catch (e: Exception) { Log.w(TAG, "exitDaemon failed", e) }
        }
        rpc.stopMessageLoop()
        transport.disconnect()
        if (killDaemon && processDataSource != null) {
            withContext(Dispatchers.IO) { processDataSource.stopDaemon() }
            _daemonPid.value = -1
        }
        _connectionError.value = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _managedDisplayIds.value = emptySet()
        _displayOwners.value = emptyMap()
        currentStreamingDisplayId = -1
    }

    override suspend fun startDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) return@withContext Result.success(Unit)
        if (isBound) {
            val job = withContext(Dispatchers.Main) {
                isBound = false
                launchCleanupOnce(killDaemon = false)
            }
            withTimeoutOrNull(1000) { job.join() }
        }
        // 手动启动强制拉起 daemon 并连接，不受 "自动启动服务端" 开关约束。
        connectInternal(enforceAutoStart = false)
    }

    override suspend fun stopDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val job = withContext(Dispatchers.Main) {
                isBound = false
                launchCleanupOnce(killDaemon = true)
            }
            withTimeoutOrNull(3000) { job.join() }
        }.map { }
    }

    override fun destroyService() {
        isBound = false
        val job = launchCleanupOnce(killDaemon = true)
        runCatching { runBlocking { withTimeoutOrNull(3000) { job.join() } } }
        scope.cancel()
    }

    private fun refreshManagedDisplays() {
        scope.launch {
            if (_connectionStatus.value != ConnectionStatus.CONNECTED) return@launch
            remoteDataSource.getActiveDisplayInfos().onSuccess { displayInfos ->
                val saved = AppSettings.getDisplaysForServer(context, node)
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
                AppSettings.setDisplaysForServer(context, node, updatedSaved)
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

    override fun refreshDisplays() { refreshManagedDisplays() }

    override fun setActiveNode(node: ServerNode) {}
    override fun connectNode(node: ServerNode) {}
    override fun disconnectNode(node: ServerNode, killDaemon: Boolean) {}
    override fun getSlot(nodeKey: String): IDisplayRepository? = if (node.uniqueKey() == nodeKey) this else null
    override fun allSlots(): Collection<IDisplayRepository> = listOf(this)

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int, mirrorDisplayId: Int): Result<Int> {
        val result = remoteDataSource.createDisplay(name, width, height, dpi, flags, mirrorDisplayId)
        result.onSuccess { displayId ->
            AppSettings.saveDisplayForServer(context, node, SavedDisplay(displayId, name, width, height, dpi, mirrorDisplayId))
            refreshManagedDisplays()
        }
        return result
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> {
        val result = remoteDataSource.releaseDisplay(displayId)
        result.onSuccess {
            AppSettings.removeDisplayForServer(context, node, displayId)
            if (currentStreamingDisplayId == displayId) {
                videoController.stop()
                currentStreamingDisplayId = -1
            }
            refreshManagedDisplays()
        }
        return result
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> {
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
        if (currentStreamingDisplayId != -1) runCatching { videoController.stop() }
        val result = videoController.start(displayId, decoderSurface, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        if (result.isSuccess) {
            currentStreamingDisplayId = displayId
            currentVideoWidth = DEFAULT_WIDTH
            currentVideoHeight = DEFAULT_HEIGHT
        }
        return result
    }

    override suspend fun stopStreaming() {
        Log.i(TAG, "[${node.uniqueKey()}] stopStreaming: stopping current stream (display=$currentStreamingDisplayId)")
        if (currentStreamingDisplayId == -1) return
        runCatching { videoController.stop() }.onFailure {
            Log.w(TAG, "[${node.uniqueKey()}] stopStreaming: videoController.stop failed", it)
        }
        currentStreamingDisplayId = -1
        currentVideoWidth = DEFAULT_WIDTH
        currentVideoHeight = DEFAULT_HEIGHT
    }

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> {
        val result = remoteDataSource.resizeDisplay(displayId, width, height, dpi)
        result.onSuccess {
            if (currentStreamingDisplayId == displayId) {
                currentVideoWidth = width; currentVideoHeight = height
                videoController.updateResolution(width, height)
            }
        }
        return result
    }

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> {
        val result = remoteDataSource.startActivity(packageName, displayId)
        result.onSuccess { settingsDataSource.addRecentApp(packageName) }
        return result
    }

    override suspend fun launchHome(displayId: Int): Result<Int> =
        remoteDataSource.launchHome(displayId)

    override suspend fun listApps(): Result<List<DeviceMessage.AppEntry>> =
        remoteDataSource.listApps()

    override suspend fun injectInput(event: InputEvent): Result<Boolean> =
        injectInputWithDisplayId(event, 0)

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> =
        remoteDataSource.injectInput(displayId, event, currentVideoWidth, currentVideoHeight)

    override suspend fun getActiveDisplayIds(): Result<IntArray> =
        remoteDataSource.getActiveDisplayIds()

    override suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>> =
        remoteDataSource.getActiveDisplayInfos()

    override fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?) {
        videoController.onVideoConfig = { _, w, h ->
            currentVideoWidth = w; currentVideoHeight = h
            callback?.invoke(w, h)
        }
    }

    override fun setPerformanceStatsCallback(callback: ((String) -> Unit)?) {
        videoController.onPerformanceStats = callback
    }
}
