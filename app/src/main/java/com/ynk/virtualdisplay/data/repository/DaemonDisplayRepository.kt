package com.ynk.virtualdisplay.data.repository

import android.content.Context
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.model.SavedDisplay
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
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
 * 显示器仓库实现：协调 DataSource + 传输/视频流组件，完成虚拟显示器的管理。
 *
 * 数据源拆分（严格遵循方案二分层）：
 * - [settingsDataSource]   → 本地配置读写（AppSettings / DataStore）
 * - [remoteDataSource]     → 远程 RPC 调用（Daemon 控制命令，含 launchHome / listApps）
 * - [processDataSource]    → 守护进程生命周期控制（启动/停止/查 PID）
 *
 * 仍直接持有的状态性组件（不属于"纯数据"DataSource 职责）：
 * - [transport]            → TCP 连接/重连/握手状态
 * - [rpc]                  → RPC 消息循环线程
 * - [videoController]      → 视频解码器/Surface 状态管理
 * - [shizukuManager]       → Shizuku 权限状态检测
 *
 * 业务规则（比例校验、默认 Flags 计算等）已抽离到
 * [com.ynk.virtualdisplay.domain.DisplayInteractor]，此处仅做数据编排。
 */
class DaemonDisplayRepository(
    private val context: Context,
    private val settingsDataSource: AppSettingsDataSource,
    private val remoteDataSource: DaemonRemoteDataSource,
    private val processDataSource: DaemonProcessDataSource,
    private val transport: DaemonTransport,
    private val rpc: DaemonRpc,
    private val videoController: VideoStreamController,
    private val shizukuManager: ShizukuManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob() +
            ExceptionUtils.coroutineExceptionHandler("DaemonDisplayRepository"))
) : IDisplayRepository {

    companion object {
        private const val TAG = "DaemonDisplayRepository"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEFAULT_DPI = 320
    }

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    override val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _managedDisplayIds = MutableStateFlow<Set<Int>>(emptySet())
    override val managedDisplayIds: StateFlow<Set<Int>> = _managedDisplayIds.asStateFlow()

    private val _daemonPid = MutableStateFlow(-1)
    override val daemonPid: StateFlow<Int> = _daemonPid.asStateFlow()

    private var isBound = false
    private var currentStreamingDisplayId: Int = -1
    private var decoderSurface: Surface? = null
    private var currentVideoWidth: Int = DEFAULT_WIDTH
    private var currentVideoHeight: Int = DEFAULT_HEIGHT

    // Guards the automatic-reconnect job so only one reconnect loop is ever
    // in flight. Without this, a burst of LOOP_EXITED_ABNORMALLY emissions
    // (e.g. multiple IOExceptions) could launch several concurrent attempts.
    private val reconnectLock = Any()
    @Volatile
    private var reconnectJob: Job? = null

    init {
        // Watch the RPC message loop state. Whenever the negotiation socket
        // dies (EOF, closed by server, daemon crash, etc.) while we are still
        // supposed to be bound, kick off an auto-reconnect that re-runs
        // transport.connect + startMessageLoop. Without this the user sees
        // permanent "Connection error or timeout" on every tap until they
        // kill and restart the app process.
        scope.launch {
            rpc.connectionState.collect { state ->
                if (state == DaemonRpcState.LOOP_EXITED_ABNORMALLY && isBound) {
                    Log.w(TAG, "DaemonRpc loop exited abnormally while isBound=true, scheduling auto-reconnect")
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
                val currentNode = AppSettings.getCurrentServerNodeSync()
                val (host, port, password) = if (currentNode.name == "本机") {
                    Triple(
                        settingsDataSource.getServerHost(),
                        settingsDataSource.getServerPort(),
                        settingsDataSource.getServerPassword()
                    )
                } else {
                    Triple(
                        currentNode.host,
                        currentNode.port,
                        currentNode.password
                    )
                }

                var attempt = 0
                val baseDelayMs = 200L
                val maxDelayMs = 3000L
                // 远程节点无法重启服务端进程，超过此次数后放弃重连，告知用户服务端已断开
                val isLocal = currentNode.name == "本机"
                val maxRemoteAttempts = if (isLocal) Int.MAX_VALUE else 5
                while (isBound) {
                    attempt++
                    val delayMs = (baseDelayMs * (1L shl (attempt - 1))).coerceAtMost(maxDelayMs)
                    _connectionStatus.value = ConnectionStatus.RECONNECTING

                    // Decide whether we need to re-launch the daemon process.
                    // getDaemonPid() has a process cache so a stale PID may still
                    // read positive after the process was killed externally
                    // (e.g. `adb shell kill`, native crash, Low Memory Killer).
                    // Hence we also probe the daemon TCP port with a short-lived
                    // socket; if port probe fails, treat the daemon as dead and
                    // restart it regardless of the cached PID.
                    val privilegeMode = AppSettings.getPrivilegeModeSync()

                    if (isLocal && privilegeMode != PrivilegeMode.NONE) {
                        val cachedPid = processDataSource.getDaemonPid()
                        val connectHost = if (host == "0.0.0.0") "127.0.0.1" else host
                        val daemonListening = try {
                            java.net.Socket().use { s ->
                                s.connect(java.net.InetSocketAddress(connectHost, port), 300)
                                true
                            }
                        } catch (_: Exception) {
                            false
                        }

                        if (cachedPid <= 0 || !daemonListening) {
                            Log.i(TAG, "Auto-reconnect: daemon not alive (pid=$cachedPid, listening=$daemonListening), restarting daemon...")
                            val started = processDataSource.startDaemon(port, host, password)
                            if (!started) {
                                Log.w(TAG, "Auto-reconnect: restartDaemon failed, retrying in ${delayMs}ms")
                                kotlinx.coroutines.delay(delayMs)
                                continue
                            }
                            _daemonPid.value = processDataSource.getDaemonPid()
                        }
                    } else {
                        _daemonPid.value = -1
                    }

                    Log.i(TAG, "Auto-reconnect: re-establishing transport negotiation (attempt $attempt)...")
                    val connectHost = if (host == "0.0.0.0") "127.0.0.1" else host
                    val connected = try {
                        // Post-b7aef962 CONFIGURE_SESSION / roles declaration / token auth
                        // handshake is handled inside transport.connect(...) as defaults.
                        transport.connect(connectHost, port, 5000, secretToken = password.takeIf { it.isNotEmpty() })
                    } catch (t: Throwable) {
                        Log.w(TAG, "Auto-reconnect: transport.connect threw", t)
                        false
                    }
                    if (connected) {
                        rpc.startMessageLoop(this@launch)
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        _connectionError.value = null
                        refreshManagedDisplays()
                        Log.i(TAG, "Auto-reconnect succeeded on attempt $attempt (sessionId=${transport.session?.sessionId})")
                        return@launch
                    }
                    Log.w(TAG, "Auto-reconnect: transport.connect failed (attempt $attempt), backing off ${delayMs}ms")
                    if (attempt >= maxRemoteAttempts) {
                        Log.w(TAG, "Auto-reconnect: reached max attempts ($maxRemoteAttempts), giving up. Server disconnected.")
                        _connectionError.value = "服务端已断开，请手动重连"
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        return@launch
                    }
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
    }

    // Single-flight cleanup: ensures performCleanup runs at most once
    // concurrently. destroyService JOINs the in-flight cleanup launched by
    // unbindService instead of cancelling it (cancellation mid-teardown would
    // leak sockets/processes).
    private val cleanupLock = Any()
    private var cleanupJob: Job? = null

    private fun launchCleanupOnce(killDaemon: Boolean): Job = synchronized(cleanupLock) {
        val existing = cleanupJob
        if (existing != null && existing.isActive) {
            existing
        } else {
            scope.launch { performCleanup(killDaemon) }.also { cleanupJob = it }
        }
    }

    override fun bindService() {
        if (isBound) {
            Log.d(TAG, "Already bound, skipping bindService")
            return
        }
        isBound = true

        scope.launch {
            try {
                _connectionStatus.value = ConnectionStatus.BINDING

                val currentNode = AppSettings.getCurrentServerNodeSync()
                val (host, port, password) = if (currentNode.name == "本机") {
                    Triple(
                        settingsDataSource.getServerHost(),
                        settingsDataSource.getServerPort(),
                        settingsDataSource.getServerPassword()
                    )
                } else {
                    Triple(
                        currentNode.host,
                        currentNode.port,
                        currentNode.password
                    )
                }

                val isLocal = currentNode.name == "本机"
                val privilegeMode = AppSettings.getPrivilegeModeSync()

                if (isLocal && privilegeMode != PrivilegeMode.NONE) {
                    if (privilegeMode == PrivilegeMode.SHIZUKU && !shizukuManager.isAvailable()) {
                        _connectionError.value = "Shizuku is not available"
                        _connectionStatus.value = ConnectionStatus.ERROR
                        return@launch
                    }

                    // 检查自动启动选项
                    val autoStart = AppSettings.getAutoStartServerSync()
                    val isRunning = withContext(Dispatchers.IO) { processDataSource.getDaemonPid() > 0 }
                    if (!autoStart && !isRunning) {
                        Log.i(TAG, "autoStart is false and daemon not running, skipping startDaemon and connection")
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        return@launch
                    }

                    // 启动守护进程 → Process DataSource
                    val started = withContext(Dispatchers.IO) {
                        processDataSource.startDaemon(port, host, password)
                    }
                    if (!started) {
                        _connectionError.value = "Failed to start daemon process"
                        _connectionStatus.value = ConnectionStatus.ERROR
                        return@launch
                    }

                    _daemonPid.value = withContext(Dispatchers.IO) { processDataSource.getDaemonPid() }
                } else {
                    _daemonPid.value = -1
                }

                // Post-b7aef962: transport.connect performs the full 2-stage handshake
                // (ROLE_NEGOTIATION → sessionId+deviceName → CONFIGURE_SESSION with
                // declared roles) as well as optional secret_token auth. Defaults
                // (secretToken=null, rolesMask=0 = "declare-on-bind" per-socket roles)
                // match the server's default configuration.
                val connectHost = if (host == "0.0.0.0") "127.0.0.1" else host
                val connected = transport.connect(connectHost, port, 5000, secretToken = password.takeIf { it.isNotEmpty() })
                if (connected) {
                    rpc.startMessageLoop(scope)
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    _connectionError.value = null
                    refreshManagedDisplays()
                } else {
                    _connectionError.value = "Failed to connect to daemon port"
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "bindService failed", e)
                _connectionError.value = e.message ?: "Unknown error"
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }

    override fun unbindService() {
        if (!isBound) {
            Log.d(TAG, "Not bound, skipping unbind")
            return
        }
        isBound = false
        launchCleanupOnce(killDaemon = false)
    }

    private suspend fun performCleanup(killDaemon: Boolean) {
        // Cancel any in-flight auto-reconnect before tearing things down;
        // otherwise it races exitDaemon/transport.disconnect and re-opens
        // sockets that we are about to kill.
        runCatching { reconnectJob?.cancel() }
        try { videoController.stop() } catch (e: Exception) { Log.w(TAG, "Failed to stop video streaming", e) }
        
        if (killDaemon) {
            try {
                // exitDaemon → Remote DataSource
                remoteDataSource.exitDaemon()
                kotlinx.coroutines.delay(100)
            } catch (e: Exception) { Log.w(TAG, "Failed to send exit command", e) }
        }
        
        rpc.stopMessageLoop()
        transport.disconnect()
        
        if (killDaemon) {
            // 停止守护进程 → Process DataSource
            withContext(Dispatchers.IO) { processDataSource.stopDaemon() }
            _daemonPid.value = -1
        }
        _connectionError.value = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _managedDisplayIds.value = emptySet()
        currentStreamingDisplayId = -1
    }

    private fun refreshManagedDisplays() {
        scope.launch {
            val currentNode = AppSettings.getCurrentServerNodeSync()
            val isLocal = currentNode.name == "本机"
            if (isLocal) {
                _daemonPid.value = withContext(Dispatchers.IO) { processDataSource.getDaemonPid() }
            } else {
                _daemonPid.value = -1
            }
            // 获取显示器详情列表 → Remote DataSource
            val result = remoteDataSource.getActiveDisplayInfos()
            result.onSuccess { displayInfos ->
                val saved = AppSettings.getDisplaysForServer(context, currentNode)
                val activeInfosMap = displayInfos.associateBy { it.displayId }
                val activeIdsSet = activeInfosMap.keys
                
                val updatedSaved = saved.filter { it.id in activeIdsSet }.toMutableList()
                val savedIds = updatedSaved.map { it.id }.toSet()
                
                activeInfosMap.forEach { (id, info) ->
                    if (id !in savedIds) {
                        updatedSaved.add(SavedDisplay(
                            id = id,
                            name = if (id == 0) "主屏幕" else "Virtual Display $id",
                            width = info.width,
                            height = info.height,
                            dpi = info.dpi,
                            mirrorDisplayId = info.mirrorDisplayId,
                            isOwned = info.isOwned
                        ))
                    } else {
                        val idx = updatedSaved.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val old = updatedSaved[idx]
                            if (old.mirrorDisplayId != info.mirrorDisplayId || old.width != info.width || old.height != info.height || old.dpi != info.dpi || old.isOwned != info.isOwned) {
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
                
                AppSettings.setDisplaysForServer(context, currentNode, updatedSaved)
                
                // managedDisplayIds 应当仅包含服务端自己创建/接管的虚拟显示器 (即 isOwned 为 true 的)
                val activeOwnedIdsSet = displayInfos.filter { it.isOwned }.map { it.displayId }.toSet()
                _managedDisplayIds.value = activeOwnedIdsSet
            }.onFailure {
                Log.w(TAG, "refreshManagedDisplays failed", it)
                _managedDisplayIds.value = emptySet()
            }
        }
    }

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int, mirrorDisplayId: Int): Result<Int> {
        val finalFlags = if (flags != 0) flags else 0
        Log.d(TAG, "createDisplay: name=$name, width=$width, height=$height, dpi=$dpi, flags=0x${Integer.toHexString(finalFlags)}, mirrorDisplayId=$mirrorDisplayId")
        // 创建显示器 → Remote DataSource
        val result = remoteDataSource.createDisplay(name, width, height, dpi, finalFlags, mirrorDisplayId)
        result.onSuccess { displayId ->
            val currentNode = AppSettings.getCurrentServerNodeSync()
            AppSettings.saveDisplayForServer(context, currentNode, SavedDisplay(displayId, name, width, height, dpi, mirrorDisplayId))
            refreshManagedDisplays()
        }
        return result
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> {
        // 释放显示器 → Remote DataSource
        val result = remoteDataSource.releaseDisplay(displayId)
        result.onSuccess {
            val currentNode = AppSettings.getCurrentServerNodeSync()
            AppSettings.removeDisplayForServer(context, currentNode, displayId)
            
            if (currentStreamingDisplayId == displayId) {
                videoController.stop()
                currentStreamingDisplayId = -1
            }
            refreshManagedDisplays()
        }
        return result
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> {
        Log.i(TAG, "setDisplaySurface: displayId=$displayId surface=$surface valid=${surface?.isValid} currentStreaming=$currentStreamingDisplayId")
        decoderSurface = surface
        if (surface != null) {
            val isFirstTime = currentStreamingDisplayId != displayId
            if (isFirstTime) {
                // startStreamingToDisplay 绝不 throw——失败通过 Result 返回
                return startStreamingToDisplay(displayId)
            } else {
                runCatching { videoController.setSurface(surface) }.onFailure {
                    Log.e(TAG, "setSurface failed for display $displayId", it)
                    return Result.failure(it)
                }
            }
        } else {
            runCatching { videoController.setSurface(null) }.onFailure {
                Log.w(TAG, "setSurface(null) failed", it)
                // Surface 销毁属正常生命周期，不把清理失败向上抛为 UI 层错误
            }
        }
        return Result.success(Unit)
    }

    private suspend fun startStreamingToDisplay(displayId: Int): Result<Unit> {
        if (currentStreamingDisplayId == displayId) return Result.success(Unit)
        if (currentStreamingDisplayId != -1) {
            runCatching { videoController.stop() }.onFailure {
                Log.w(TAG, "stop previous stream failed, proceeding anyway", it)
            }
        }
        Log.i(TAG, "startStreamingToDisplay: displayId=$displayId surface=$decoderSurface valid=${decoderSurface?.isValid} dims=${DEFAULT_WIDTH}x${DEFAULT_HEIGHT}")
        val startResult = videoController.start(displayId, decoderSurface, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        if (startResult.isSuccess) {
            currentStreamingDisplayId = displayId
            currentVideoWidth = DEFAULT_WIDTH
            currentVideoHeight = DEFAULT_HEIGHT
            Log.i(TAG, "startStreamingToDisplay: success, currentStreamingDisplayId=$currentStreamingDisplayId")
        } else {
            Log.e(TAG, "startStreamingToDisplay: failed for displayId=$displayId", startResult.exceptionOrNull())
        }
        return startResult
    }

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> {
        // 调整尺寸 → Remote DataSource
        val result = remoteDataSource.resizeDisplay(displayId, width, height, dpi)
        result.onSuccess {
            if (currentStreamingDisplayId == displayId) {
                currentVideoWidth = width
                currentVideoHeight = height
                videoController.updateResolution(width, height)
            }
        }
        return result
    }

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> {
        // 启动应用 → Remote DataSource
        val result = remoteDataSource.startActivity(packageName, displayId)
        // 最近应用写入 → Local DataSource
        result.onSuccess { settingsDataSource.addRecentApp(packageName) }
        return result
    }

    override suspend fun launchHome(displayId: Int): Result<Int> =
        remoteDataSource.launchHome(displayId)

    override suspend fun listApps(): Result<List<DeviceMessage.AppEntry>> =
        remoteDataSource.listApps()

    override suspend fun injectInput(event: InputEvent): Result<Boolean> {
        return injectInputWithDisplayId(event, 0)
    }

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> {
        // Post-b7aef962: 直接通过 ROLE_CONTROL socket 上的 scrcpy 原生协议发送。
        // 不再需要 Parcel marshalling —— 输入事件由 ScrcpyControlEncoder 编码。
        val result = remoteDataSource.injectInput(displayId, event, currentVideoWidth, currentVideoHeight)
        if (result.isFailure) {
            Log.w(TAG, "injectInputWithDisplay(displayId=$displayId) failed: ${result.exceptionOrNull()?.message}")
        }
        return result
    }



    override suspend fun getActiveDisplayIds(): Result<IntArray> {
        return Result.success(_managedDisplayIds.value.toIntArray())
    }

    override suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>> =
        remoteDataSource.getActiveDisplayInfos()

    override fun destroyService() {
        isBound = false
        val job = launchCleanupOnce(killDaemon = true)
        runCatching {
            runBlocking {
                withTimeoutOrNull(3000) { job.join() }
            }
        }
        scope.cancel()
    }

    override suspend fun startDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 已处于连接状态，不打断现有连接
            if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                Log.d(TAG, "startDaemon: already connected, skipping")
                return@runCatching
            }
            // 先完整停止（若当前已绑定但未连接）
            if (isBound) {
                val job = withContext(Dispatchers.Main) {
                    isBound = false
                    launchCleanupOnce(killDaemon = false)
                }
                withTimeoutOrNull(1000) { job.join() }
            }
            withContext(Dispatchers.Main) {
                bindService()
            }
        }
    }

    override suspend fun stopDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // launchCleanupOnce(killDaemon=true) 会依次：取消重连、停止视频流、
            // 发送 exitDaemon 命令、断开 transport、杀守护进程、重置状态
            val job = withContext(Dispatchers.Main) {
                isBound = false
                launchCleanupOnce(killDaemon = true)
            }
            withTimeoutOrNull(3000) { job.join() }
        }.map { }
    }

    override fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?) {
        videoController.onVideoConfig = { _, w, h ->
            currentVideoWidth = w
            currentVideoHeight = h
            callback?.invoke(w, h)
        }
    }

    override fun setPerformanceStatsCallback(callback: ((String) -> Unit)?) {
        videoController.onPerformanceStats = callback
    }

    override fun refreshDisplays() {
        refreshManagedDisplays()
    }
}
