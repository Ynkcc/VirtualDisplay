package com.ynk.virtualdisplay.data.repository

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.process.DaemonProcessController
import com.ynk.virtualdisplay.rpc.DaemonControlApiImpl
import com.ynk.virtualdisplay.rpc.DaemonRpc
import com.ynk.virtualdisplay.util.ExceptionUtils
import com.ynk.virtualdisplay.video.VideoStreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class DaemonDisplayRepository(private val context: Context) : IDisplayRepository {

    companion object {
        private const val TAG = "DaemonDisplayRepository"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEFAULT_DPI = 320
    }

    private val exceptionHandler = ExceptionUtils.coroutineExceptionHandler(TAG)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private val processController = DaemonProcessController(context)
    private val transport = DaemonTransport()
    private val rpc = DaemonRpc(transport)
    private val controlApi = DaemonControlApiImpl(rpc)
    private val videoController = VideoStreamController(controlApi, transport, scope)


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
        override fun onDisplayAdded(displayId: Int) {
            refreshManagedDisplays()
        }
        override fun onDisplayRemoved(displayId: Int) {
            refreshManagedDisplays()
        }
        override fun onDisplayChanged(displayId: Int) {
            refreshManagedDisplays()
        }
    }

    override fun bindService(context: Context) {
        if (isBound) {
            Log.d(TAG, "Already bound, skipping bindService")
            return
        }
        isBound = true
        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)

        scope.launch {
            try {
                _connectionStatus.value = ConnectionStatus.BINDING
                val port = AppSettings.getServerPort(context)
                val host = AppSettings.getServerHost(context)
                if (!isShizukuAvailable()) {
                    _connectionError.value = "Shizuku is not available"
                    _connectionStatus.value = ConnectionStatus.ERROR
                    return@launch
                }
                
                val started = withContext(Dispatchers.IO) {
                    processController.startDaemon(port, host)
                }
                if (!started) {
                    _connectionError.value = "Failed to start daemon process"
                    _connectionStatus.value = ConnectionStatus.ERROR
                    return@launch
                }

                _daemonPid.value = withContext(Dispatchers.IO) { processController.getDaemonPid() }

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
        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.unregisterDisplayListener(displayListener)

        launchCleanupOnce()
    }

    private suspend fun performCleanup() {
        try {
            videoController.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop video streaming", e)
        }
        try {
            controlApi.exitDaemon()
            kotlinx.coroutines.delay(100)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send exit command", e)
        }
        rpc.stopMessageLoop()
        transport.disconnect()
        withContext(Dispatchers.IO) {
            processController.stopDaemon()
        }
        _daemonPid.value = -1
        _connectionError.value = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _managedDisplayIds.value = emptySet()
        currentStreamingDisplayId = -1
    }

    private fun refreshManagedDisplays() {
        scope.launch {
            // getDaemonPid() may spawn a blocking shell pgrep on cache miss; keep it
            // off the Main dispatcher. After startDaemon() the cached pid is returned
            // without any shell call.
            _daemonPid.value = withContext(Dispatchers.IO) { processController.getDaemonPid() }
            val result = controlApi.getActiveDisplayIds()
            result.onSuccess { ids ->
                _managedDisplayIds.value = ids.toSet()
            }.onFailure {
                Log.w(TAG, "refreshManagedDisplays failed", it)
                _managedDisplayIds.value = emptySet()
            }
        }
    }

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Result<Int> {
        val finalFlags = if (flags != 0) flags else buildDefaultFlags()
        Log.d(TAG, "createDisplay: name=$name, width=$width, height=$height, dpi=$dpi, flags=0x${Integer.toHexString(finalFlags)}")
        val result = controlApi.createDisplay(name, width, height, dpi, finalFlags)
        result.onSuccess {
            refreshManagedDisplays()
        }
        return result
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> {
        val result = controlApi.releaseDisplay(displayId)
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
                startStreamingToDisplay(displayId)
            } else {
                videoController.setSurface(surface)
            }
        } else {
            videoController.setSurface(null)
        }
        return Result.success(Unit)
    }

    private suspend fun startStreamingToDisplay(displayId: Int) {
        if (currentStreamingDisplayId == displayId) return

        // 停止之前的流
        if (currentStreamingDisplayId != -1) {
            videoController.stop()
        }

        videoController.start(displayId, decoderSurface, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        // 仅在 start 成功后才登记当前流目标，避免抛异常后状态不一致
        currentStreamingDisplayId = displayId
    }

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> {
        val result = controlApi.resizeDisplay(displayId, width, height, dpi)
        result.onSuccess {
            if (currentStreamingDisplayId == displayId) {
                videoController.updateResolution(width, height)
            }
        }
        return result
    }

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> {
        val result = controlApi.startActivity(packageName, displayId)
        result.onSuccess {
            RecentAppHelper.addRecentApp(context, packageName)
        }
        return result
    }

    override suspend fun launchHome(displayId: Int): Result<Int> {
        val launcherPackages = resolveHomeLauncherPackages()
        if (launcherPackages.isEmpty()) {
            return Result.failure(IllegalStateException("No home launcher found on device"))
        }

        var lastError: Throwable? = null
        for (packageName in launcherPackages) {
            val result = controlApi.startActivity(packageName, displayId)
            result.onSuccess {
                Log.i(TAG, "Launch home via $packageName succeeded")
                return result
            }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "Launch home via $packageName failed, trying next", lastError)
        }
        return Result.failure(lastError ?: IllegalStateException("Failed to launch home"))
    }

    private fun resolveHomeLauncherPackages(): List<String> {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        val defaultLauncher = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        val candidates = mutableListOf<String>()
        if (defaultLauncher != null) {
            candidates.add(defaultLauncher)
        }

        val allHomeActivities = pm.queryIntentActivities(homeIntent, 0)
        for (info in allHomeActivities) {
            val pkg = info.activityInfo.packageName
            if (pkg !in candidates) {
                candidates.add(pkg)
            }
        }

        if (candidates.isEmpty()) {
            val fallbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val fallbackResolved = pm.queryIntentActivities(fallbackIntent, 0)
            for (info in fallbackResolved) {
                val pkg = info.activityInfo.packageName
                if (pkg !in candidates) {
                    candidates.add(pkg)
                }
            }
        }
        return candidates
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
            controlApi.injectInput(displayId, isKeyEvent, parcelBytes)
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

    override fun isShizukuAvailable(): Boolean {
        return try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    }

    override fun destroyService() {
        isBound = false
        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.unregisterDisplayListener(displayListener)
        // JOIN the in-flight cleanup launched by unbindService (if any) instead
        // of cancelling it — cancelling mid-cleanup (mid videoController.stop /
        // transport.disconnect / stopDaemon) would leak sockets/processes, the
        // exact failure the runBlocking path was meant to prevent. If no
        // cleanup is running yet, launchCleanupOnce starts one and we join it.
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
