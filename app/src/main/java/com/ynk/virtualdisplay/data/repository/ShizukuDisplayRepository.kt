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
import com.ynk.virtualdisplay.daemon.ClientDaemonManager
import com.ynk.virtualdisplay.daemon.ClientDaemonConnection
import com.ynk.virtualdisplay.decoder.H264StreamDecoder
import com.ynk.virtualdisplay.protocol.CustomControlMessage
import com.ynk.virtualdisplay.protocol.CustomDeviceMessage
import com.ynk.virtualdisplay.util.ExceptionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class ShizukuDisplayRepository(private val context: Context) : IDisplayRepository {

    companion object {
        private const val TAG = "ShizukuDisplayRepository"
        const val REQUEST_CODE = 20260
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEFAULT_DPI = 320
    }

    private val exceptionHandler = ExceptionUtils.coroutineExceptionHandler(TAG)
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    override val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _managedDisplayIds = MutableStateFlow<Set<Int>>(emptySet())
    override val managedDisplayIds: StateFlow<Set<Int>> = _managedDisplayIds.asStateFlow()



    private val _daemonPid = MutableStateFlow(-1)
    override val daemonPid: StateFlow<Int> = _daemonPid.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private val daemonManager = ClientDaemonManager(context)
    private val daemonConnection = ClientDaemonConnection()

    private var decoder: H264StreamDecoder? = null
    private var decoderSurface: Surface? = null
    private var currentStreamingDisplayId: Int = -1
    private var videoConfigCallback: ((width: Int, height: Int) -> Unit)? = null
    private var performanceStatsCallback: ((String) -> Unit)? = null

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

    @Volatile
    private var isBound = false

    override fun bindService(context: Context) {
        if (isBound) {
            Log.d(TAG, "Already bound, skipping")
            return
        }
        if (!isShizukuAvailable()) {
            Log.e(TAG, "Shizuku not available")
            _connectionError.value = "Shizuku 服务未运行或未授权"
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }

        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)

        isBound = true
        scope.launch {
            _connectionError.value = null
            _connectionStatus.value = ConnectionStatus.BINDING

            val port = AppSettings.getServerPort(context)
            val host = AppSettings.getServerHost(context)

            val daemonStarted = withContext(Dispatchers.IO) {
                daemonManager.startDaemon(port, host)
            }
            if (!daemonStarted) {
                Log.e(TAG, "Failed to start daemon")
                _connectionError.value = "守护进程启动失败"
                _connectionStatus.value = ConnectionStatus.ERROR
                isBound = false
                return@launch
            }

            val connected = daemonConnection.connect(port = port, host = host, timeoutMs = 5000)
            if (!connected) {
                Log.e(TAG, "Failed to connect to daemon socket")
                daemonManager.stopDaemon()
                _connectionError.value = "无法连接到守护进程"
                _connectionStatus.value = ConnectionStatus.ERROR
                isBound = false
                return@launch
            }

            daemonConnection.startMessageLoop()
            _connectionError.value = null
            _connectionStatus.value = ConnectionStatus.CONNECTED
            _daemonPid.value = daemonManager.getDaemonPid()
            refreshManagedDisplays()
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
        scope.launch {
            stopDecoder()
            try {
                if (daemonConnection.isConnected()) {
                    Log.i(TAG, "Sending exit command to daemon...")
                    val sequence = CustomControlMessage.nextSequence()
                    val bytes = CustomControlMessage.createExitDaemon(sequence)
                    daemonConnection.sendControlMessage(bytes)
                    kotlinx.coroutines.delay(300)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send exit command to daemon", e)
            }
            daemonConnection.disconnect()
            daemonManager.stopDaemon()
            _daemonPid.value = -1
            _connectionError.value = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _managedDisplayIds.value = emptySet()
        }
    }

    private fun refreshManagedDisplays() {
        scope.launch {
            _daemonPid.value = daemonManager.getDaemonPid()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ensureConnected()
                    val sequence = CustomControlMessage.nextSequence()
                    val bytes = CustomControlMessage.createGetActiveDisplayIds(sequence)
                    val response = daemonConnection.sendAndAwait(bytes, sequence)
                    if (response is CustomDeviceMessage.ActiveDisplaysResponse) {
                        response.displayIds
                    } else {
                        null
                    }
                }
            }
            result.onSuccess { ids ->
                _managedDisplayIds.value = ids?.toSet() ?: emptySet()
            }.onFailure {
                Log.w(TAG, "refreshManagedDisplays failed", it)
                _managedDisplayIds.value = emptySet()
            }
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (daemonConnection.isConnected()) {
            return true
        }
        if (!isShizukuAvailable()) {
            return false
        }
        val port = AppSettings.getServerPort(context)
        val host = AppSettings.getServerHost(context)
        val connected = daemonConnection.connect(port = port, host = host, timeoutMs = 5000)
        if (connected) {
            daemonConnection.startMessageLoop()
        }
        return connected
    }

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val finalFlags = if (flags != 0) flags else buildDefaultFlags()
                Log.d(TAG, "createDisplay: name=$name, width=$width, height=$height, dpi=$dpi, flags=0x${Integer.toHexString(finalFlags)}")
                val sequence = CustomControlMessage.nextSequence()
                val bytes = CustomControlMessage.createCreateVirtualDisplay(name, width, height, dpi, finalFlags, sequence)
                val response = daemonConnection.sendAndAwait(bytes, sequence)
                if (response == null) {
                    throw IllegalStateException("Failed to create display: Timeout or connection lost")
                }
                if (response is CustomDeviceMessage.GenericResponse) {
                    if (response.statusCode == 0) {
                        response.displayId
                    } else {
                        throw IllegalStateException("Failed to create display: ${response.message ?: "Unknown error"} (code=${response.statusCode})")
                    }
                } else {
                    throw IllegalStateException("Failed to create display: Unexpected response type $response")
                }
            }
        }
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val sequence = CustomControlMessage.nextSequence()
                val bytes = CustomControlMessage.createReleaseVirtualDisplay(displayId, sequence)
                val response = daemonConnection.sendAndAwait(bytes, sequence)
                if (response == null) {
                    throw IllegalStateException("Failed to release display: Timeout or connection lost")
                }
                if (response is CustomDeviceMessage.GenericResponse) {
                    if (response.statusCode == 0) {
                        refreshManagedDisplays()
                    } else {
                        throw IllegalStateException("Failed to release display: ${response.message ?: "Unknown error"} (code=${response.statusCode})")
                    }
                } else {
                    throw IllegalStateException("Failed to release display: Unexpected response type $response")
                }
            }
        }
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                decoderSurface = surface
                if (surface != null) {
                    val isFirstTime = currentStreamingDisplayId != displayId
                    if (isFirstTime) {
                        startStreamingToDisplay(displayId)
                    }
                    decoder?.setDisplaySurface(surface)
                    if (!isFirstTime) {
                        triggerScreenRefresh(displayId)
                    }
                } else {
                    decoder?.setDisplaySurface(null)
                }
                Unit
            }
        }
    }

    private suspend fun triggerScreenRefresh(displayId: Int) {
        if (daemonConnection.isConnected()) {
            val sequence = CustomControlMessage.nextSequence()
            val switchBytes = CustomControlMessage.createSwitchDisplay(displayId, sequence)
            Log.d(TAG, "triggerScreenRefresh: sending switchDisplay($displayId) for refresh")
            val response = daemonConnection.sendAndAwait(switchBytes, sequence)
            if (response !is CustomDeviceMessage.GenericResponse || response.statusCode != 0) {
                Log.w(TAG, "Trigger screen refresh switch display response not OK: $response")
            } else {
                Log.i(TAG, "Triggered screen refresh successfully for displayId $displayId")
            }
        }
    }

    private suspend fun startStreamingToDisplay(displayId: Int) {
        if (!daemonConnection.isConnected()) {
            val port = AppSettings.getServerPort(context)
            val host = AppSettings.getServerHost(context)
            val connected = daemonConnection.connect(port = port, host = host, timeoutMs = 5000)
            if (!connected) {
                throw IllegalStateException("Failed to connect to daemon")
            }
            daemonConnection.startMessageLoop()
        }

        if (displayId != 0 && currentStreamingDisplayId != displayId) {
            val sequence = CustomControlMessage.nextSequence()
            val switchBytes = CustomControlMessage.createSwitchDisplay(displayId, sequence)
            val response = daemonConnection.sendAndAwait(switchBytes, sequence)
            if (response !is CustomDeviceMessage.GenericResponse || response.statusCode != 0) {
                Log.w(TAG, "Switch display response not OK: $response")
            }
        }

        stopDecoder()

        val videoStream = daemonConnection.getVideoInputStream()
        if (videoStream != null) {
            currentStreamingDisplayId = displayId
            val d = H264StreamDecoder(videoStream, DEFAULT_WIDTH, DEFAULT_HEIGHT)
            decoder = d
            d.onVideoConfig = { _, w, h -> videoConfigCallback?.invoke(w, h) }
            d.onPerformanceStats = performanceStatsCallback
            decoderSurface?.let { d.setDisplaySurface(it) }
            d.start()
            Log.i(TAG, "Started streaming display $displayId")
        } else {
            Log.e(TAG, "Video stream is null after connecting to daemon")
        }
    }

    private suspend fun stopDecoder() {
        decoder?.stop()
        decoder = null
        currentStreamingDisplayId = -1
    }

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val sequence = CustomControlMessage.nextSequence()
                val bytes = CustomControlMessage.createResizeVirtualDisplay(displayId, width, height, dpi, sequence)
                val response = daemonConnection.sendAndAwait(bytes, sequence)
                if (response is CustomDeviceMessage.GenericResponse && response.statusCode == 0) {
                    if (currentStreamingDisplayId == displayId) {
                        decoder?.updateResolution(width, height)
                    }
                } else {
                    throw IllegalStateException("Failed to resize display: $response")
                }
            }
        }
    }

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val sequence = CustomControlMessage.nextSequence()
                val bytes = CustomControlMessage.createStartActivity(packageName, displayId, sequence)
                val response = daemonConnection.sendAndAwait(bytes, sequence)
                if (response is CustomDeviceMessage.GenericResponse && response.statusCode >= 0) {
                    RecentAppHelper.addRecentApp(context, packageName)
                    response.statusCode
                } else {
                    throw IllegalStateException("Failed to launch app: $response")
                }
            }
        }
    }

    override suspend fun launchHome(displayId: Int): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val launcherPackages = resolveHomeLauncherPackages()
                if (launcherPackages.isEmpty()) {
                    throw IllegalStateException("No home launcher found on device")
                }

                var lastError: Throwable? = null
                for (packageName in launcherPackages) {
                    val sequence = CustomControlMessage.nextSequence()
                    val bytes = CustomControlMessage.createStartActivity(packageName, displayId, sequence)
                    val response = daemonConnection.sendAndAwait(bytes, sequence)
                    if (response is CustomDeviceMessage.GenericResponse && response.statusCode >= 0) {
                        Log.i(TAG, "Launch home via $packageName succeeded")
                        return@runCatching response.statusCode
                    }
                    lastError = IllegalStateException("Launcher $packageName failed: $response")
                    Log.w(TAG, "Launch home via $packageName failed, trying next")
                }
                throw lastError ?: IllegalStateException("Failed to launch home")
            }
        }
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

        Log.d(TAG, "Resolved home launcher candidates: $candidates")
        return candidates
    }

    override suspend fun injectInput(event: InputEvent): Result<Boolean> {
        return injectInputWithDisplayId(event, 0)
    }

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val isKeyEvent = event is android.view.KeyEvent
                val parcel = Parcel.obtain()
                try {
                    event.writeToParcel(parcel, 0)
                    val parcelBytes = parcel.marshall()
                    val sequence = CustomControlMessage.nextSequence()
                    val bytes = CustomControlMessage.createInjectInputEventWithDisplayId(displayId, isKeyEvent, parcelBytes, sequence)
                    val response = daemonConnection.sendAndAwait(bytes, sequence)
                    response is CustomDeviceMessage.GenericResponse && response.statusCode == 0
                } finally {
                    parcel.recycle()
                }
            }
        }
    }

    override suspend fun switchDisplay(displayId: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                startStreamingToDisplay(displayId)
            }
        }
    }

    override suspend fun getActiveDisplayIds(): Result<IntArray> {
        return Result.success(_managedDisplayIds.value.toIntArray())
    }

    override fun isShizukuAvailable(): Boolean {
        return try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    }

    override fun destroyService() {
        scope.launch {
            stopDecoder()
            try {
                if (daemonConnection.isConnected()) {
                    Log.i(TAG, "Sending exit command to daemon...")
                    val sequence = CustomControlMessage.nextSequence()
                    val bytes = CustomControlMessage.createExitDaemon(sequence)
                    daemonConnection.sendControlMessage(bytes)
                    kotlinx.coroutines.delay(100)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send exit command to daemon", e)
            }
            daemonConnection.disconnect()
            daemonManager.stopDaemon()
            _daemonPid.value = -1
            _connectionError.value = null
            _connectionStatus.value = ConnectionStatus.IDLE
            _managedDisplayIds.value = emptySet()
        }
        scope.cancel()
    }

    override fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?) {
        videoConfigCallback = callback
        decoder?.onVideoConfig = { _, w, h -> callback?.invoke(w, h) }
    }

    override fun setPerformanceStatsCallback(callback: ((String) -> Unit)?) {
        performanceStatsCallback = callback
        decoder?.onPerformanceStats = callback
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
