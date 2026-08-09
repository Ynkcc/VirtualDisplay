package com.ynk.virtualdisplay.data.repository

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Parcel
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.data.remote.DaemonRemoteDataSource
import com.ynk.virtualdisplay.data.system.LauncherDataSource
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.rpc.DaemonRpc
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
 * 显示器仓库实现：协调 4 个 DataSource + 传输/视频流组件，完成虚拟显示器的管理。
 *
 * 数据源拆分（严格遵循方案二分层）：
 * - [settingsDataSource]   → 本地配置读写（AppSettings / DataStore）
 * - [remoteDataSource]     → 远程 RPC 调用（Daemon 控制命令）
 * - [launcherDataSource]   → 系统服务查询（Launcher 列表 / DisplayManager）
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
    private val launcherDataSource: LauncherDataSource,
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

    // Single-flight cleanup: ensures performCleanup runs at most once
    // concurrently. destroyService JOINs the in-flight cleanup launched by
    // unbindService instead of cancelling it (cancellation mid-teardown would
    // leak sockets/processes).
    private val cleanupLock = Any()
    private var cleanupJob: Job? = null

    private fun launchCleanupOnce(): Job = synchronized(cleanupLock) {
        val existing = cleanupJob
        if (existing != null && existing.isActive) {
            existing
        } else {
            scope.launch { performCleanup() }.also { cleanupJob = it }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { refreshManagedDisplays() }
        override fun onDisplayRemoved(displayId: Int) { refreshManagedDisplays() }
        override fun onDisplayChanged(displayId: Int) { refreshManagedDisplays() }
    }

    override fun bindService() {
        if (isBound) {
            Log.d(TAG, "Already bound, skipping bindService")
            return
        }
        isBound = true
        // 通过 LauncherDataSource（System 数据源）管理 DisplayListener
        launcherDataSource.registerDisplayListener(displayListener)

        scope.launch {
            try {
                _connectionStatus.value = ConnectionStatus.BINDING

                // 读取连接配置 → Local DataSource
                val port = settingsDataSource.getServerPort()
                val host = settingsDataSource.getServerHost()

                if (!shizukuManager.isAvailable()) {
                    _connectionError.value = "Shizuku is not available"
                    _connectionStatus.value = ConnectionStatus.ERROR
                    return@launch
                }

                // 启动守护进程 → Process DataSource
                val started = withContext(Dispatchers.IO) {
                    processDataSource.startDaemon(port, host)
                }
                if (!started) {
                    _connectionError.value = "Failed to start daemon process"
                    _connectionStatus.value = ConnectionStatus.ERROR
                    return@launch
                }

                _daemonPid.value = withContext(Dispatchers.IO) { processDataSource.getDaemonPid() }

                val connected = transport.connect(host, port, 5000)
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
        launcherDataSource.unregisterDisplayListener(displayListener)
        launchCleanupOnce()
    }

    private suspend fun performCleanup() {
        try { videoController.stop() } catch (e: Exception) { Log.w(TAG, "Failed to stop video streaming", e) }
        try {
            // exitDaemon → Remote DataSource
            remoteDataSource.exitDaemon()
            kotlinx.coroutines.delay(100)
        } catch (e: Exception) { Log.w(TAG, "Failed to send exit command", e) }
        rpc.stopMessageLoop()
        transport.disconnect()
        // 停止守护进程 → Process DataSource
        withContext(Dispatchers.IO) { processDataSource.stopDaemon() }
        _daemonPid.value = -1
        _connectionError.value = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _managedDisplayIds.value = emptySet()
        currentStreamingDisplayId = -1
    }

    private fun refreshManagedDisplays() {
        scope.launch {
            _daemonPid.value = withContext(Dispatchers.IO) { processDataSource.getDaemonPid() }
            // 获取显示器列表 → Remote DataSource
            val result = remoteDataSource.getActiveDisplayIds()
            result.onSuccess { ids ->
                _managedDisplayIds.value = ids.toSet()
            }.onFailure {
                Log.w(TAG, "refreshManagedDisplays failed", it)
                _managedDisplayIds.value = emptySet()
            }
        }
    }

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Result<Int> {
        val finalFlags = if (flags != 0) flags else 0
        Log.d(TAG, "createDisplay: name=$name, width=$width, height=$height, dpi=$dpi, flags=0x${Integer.toHexString(finalFlags)}")
        // 创建显示器 → Remote DataSource
        val result = remoteDataSource.createDisplay(name, width, height, dpi, finalFlags)
        result.onSuccess { refreshManagedDisplays() }
        return result
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> {
        // 释放显示器 → Remote DataSource
        val result = remoteDataSource.releaseDisplay(displayId)
        result.onSuccess {
            if (currentStreamingDisplayId == displayId) {
                videoController.stop(force = true)
                currentStreamingDisplayId = -1
            }
            refreshManagedDisplays()
        }
        return result
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> {
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
        val startResult = videoController.start(displayId, decoderSurface, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        if (startResult.isSuccess) {
            currentStreamingDisplayId = displayId
        }
        return startResult
    }

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> {
        // 调整尺寸 → Remote DataSource
        val result = remoteDataSource.resizeDisplay(displayId, width, height, dpi)
        result.onSuccess {
            if (currentStreamingDisplayId == displayId) {
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

    override suspend fun launchHome(displayId: Int): Result<Int> {
        // Launcher 列表 → System DataSource
        val launcherPackages = launcherDataSource.resolveHomeLauncherPackages()
        if (launcherPackages.isEmpty()) {
            return Result.failure(IllegalStateException("No home launcher found on device"))
        }
        var lastError: Throwable? = null
        for (packageName in launcherPackages) {
            val result = remoteDataSource.startActivity(packageName, displayId)
            result.onSuccess {
                Log.i(TAG, "Launch home via $packageName succeeded")
                return result
            }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "Launch home via $packageName failed, trying next", lastError)
        }
        return Result.failure(lastError ?: IllegalStateException("Failed to launch home"))
    }

    override suspend fun injectInput(event: InputEvent): Result<Boolean> {
        return injectInputWithDisplayId(event, 0)
    }

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> {
        val isKeyEvent = event is android.view.KeyEvent
        val parcel = Parcel.obtain()
        return try {
            event.writeToParcel(parcel, 0)
            val parcelBytes = parcel.marshall()
            // 输入注入 → Remote DataSource
            remoteDataSource.injectInput(displayId, isKeyEvent, parcelBytes)
        } finally {
            parcel.recycle()
        }
    }

    override suspend fun switchDisplay(displayId: Int): Result<Unit> {
        startStreamingToDisplay(displayId)
        return Result.success(Unit)
    }

    override suspend fun getActiveDisplayIds(): Result<IntArray> {
        return Result.success(_managedDisplayIds.value.toIntArray())
    }

    override fun destroyService() {
        isBound = false
        launcherDataSource.unregisterDisplayListener(displayListener)
        val job = launchCleanupOnce()
        runCatching {
            runBlocking {
                withTimeoutOrNull(3000) { job.join() }
            }
        }
        scope.cancel()
    }

    override fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?) {
        videoController.onVideoConfig = { _, w, h -> callback?.invoke(w, h) }
    }

    override fun setPerformanceStatsCallback(callback: ((String) -> Unit)?) {
        videoController.onPerformanceStats = callback
    }

    override fun refreshDisplays() {
        refreshManagedDisplays()
    }
}
