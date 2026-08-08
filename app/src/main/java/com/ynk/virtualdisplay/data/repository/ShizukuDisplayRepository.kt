package com.ynk.virtualdisplay.data.repository

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import com.ynk.virtualdisplay.daemon.ClientDaemonManager
import com.ynk.virtualdisplay.daemon.ClientDaemonConnection
import com.ynk.virtualdisplay.decoder.H264StreamDecoder
import com.ynk.virtualdisplay.protocol.CustomControlMessage
import com.ynk.virtualdisplay.protocol.CustomDeviceMessage
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

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _managedDisplayIds = MutableStateFlow<Set<Int>>(emptySet())
    override val managedDisplayIds: StateFlow<Set<Int>> = _managedDisplayIds.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }

        val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, null)

        isBound = true
        scope.launch {
            _connectionStatus.value = ConnectionStatus.BINDING

            val daemonStarted = withContext(Dispatchers.IO) {
                daemonManager.startDaemon()
            }
            if (!daemonStarted) {
                Log.e(TAG, "Failed to start daemon")
                _connectionStatus.value = ConnectionStatus.ERROR
                isBound = false
                return@launch
            }

            val connected = daemonConnection.connect(timeoutMs = 5000)
            if (!connected) {
                Log.e(TAG, "Failed to connect to daemon socket")
                daemonManager.stopDaemon()
                _connectionStatus.value = ConnectionStatus.ERROR
                isBound = false
                return@launch
            }

            daemonConnection.startMessageLoop()
            _connectionStatus.value = ConnectionStatus.CONNECTED
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
            daemonConnection.disconnect()
            daemonManager.stopDaemon()
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _managedDisplayIds.value = emptySet()
        }
    }

    private fun refreshManagedDisplays() {
        scope.launch {
            val result = getActiveDisplayIds()
            result.onSuccess { ids ->
                _managedDisplayIds.value = ids.toSet()
            }
        }
    }

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val finalFlags = if (flags != 0) flags else buildDefaultFlags()
                val sequence = CustomControlMessage.nextSequence()
                val bytes = CustomControlMessage.createCreateVirtualDisplay(name, width, height, dpi, finalFlags, sequence)
                val response = daemonConnection.sendAndAwait(bytes, sequence)
                if (response is CustomDeviceMessage.GenericResponse && response.statusCode == 0) {
                    response.displayId
                } else {
                    throw IllegalStateException("Failed to create display: $response")
                }
            }
        }
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val sequence = CustomControlMessage.nextSequence()
                val bytes = CustomControlMessage.createReleaseVirtualDisplay(displayId, sequence)
                val response = daemonConnection.sendAndAwait(bytes, sequence)
                if (response is CustomDeviceMessage.GenericResponse && response.statusCode == 0) {
                    refreshManagedDisplays()
                } else {
                    throw IllegalStateException("Failed to release display: $response")
                }
            }
        }
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                decoderSurface = surface
                if (surface != null) {
                    if (currentStreamingDisplayId != displayId) {
                        startStreamingToDisplay(displayId)
                    }
                    decoder?.setDisplaySurface(surface)
                } else {
                    stopDecoder()
                }
                Unit
            }
        }
    }

    private suspend fun startStreamingToDisplay(displayId: Int) {
        if (!daemonConnection.isConnected()) {
            val connected = daemonConnection.connect(timeoutMs = 5000)
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
                val sequence = CustomControlMessage.nextSequence()
                val bytes = CustomControlMessage.createStartActivity("com.android.launcher3", displayId, sequence)
                val response = daemonConnection.sendAndAwait(bytes, sequence)
                if (response is CustomDeviceMessage.GenericResponse) {
                    if (response.statusCode >= 0) {
                        response.statusCode
                    } else {
                        val sequence2 = CustomControlMessage.nextSequence()
                        val launcherBytes = CustomControlMessage.createStartActivity("com.google.android.apps.nexuslauncher", displayId, sequence2)
                        val fallbackResponse = daemonConnection.sendAndAwait(launcherBytes, sequence2)
                        if (fallbackResponse is CustomDeviceMessage.GenericResponse && fallbackResponse.statusCode >= 0) {
                            fallbackResponse.statusCode
                        } else {
                            throw IllegalStateException("Failed to launch home")
                        }
                    }
                } else {
                    throw IllegalStateException("Failed to launch home: $response")
                }
            }
        }
    }

    override suspend fun injectInput(event: InputEvent): Result<Boolean> {
        return injectInputWithDisplayId(event, 0)
    }

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            runCatching {
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
        return withContext(Dispatchers.IO) {
            runCatching {
                val sequence = CustomControlMessage.nextSequence()
                val bytes = CustomControlMessage.createGetActiveDisplayIds(sequence)
                val response = daemonConnection.sendAndAwait(bytes, sequence)
                if (response is CustomDeviceMessage.ActiveDisplaysResponse) {
                    response.displayIds
                } else {
                    emptyArray<Int>().toIntArray()
                }
            }
        }
    }

    override fun isShizukuAvailable(): Boolean {
        return try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    }

    override fun destroyService() {
        scope.launch {
            stopDecoder()
            daemonConnection.disconnect()
            daemonManager.stopDaemon()
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

    private fun buildDefaultFlags(): Int {
        val prefs = context.getSharedPreferences("virtual_display_settings", Context.MODE_PRIVATE)
        var flagsSum = 0
        ALL_DISPLAY_FLAGS.forEach { flag ->
            if (flag.minSdk <= android.os.Build.VERSION.SDK_INT) {
                val isEnabled = prefs.getBoolean(flag.key, flag.isDefaultEnabled)
                if (isEnabled) {
                    flagsSum = flagsSum or flag.bitValue
                }
            }
        }
        return flagsSum
    }
}
