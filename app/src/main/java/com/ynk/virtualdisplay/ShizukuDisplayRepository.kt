package com.ynk.virtualdisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import rikka.shizuku.Shizuku

class ShizukuDisplayRepository(private val context: Context) : IDisplayRepository {

    companion object {
        private const val TAG = "ShizukuDisplayRepository"
        const val REQUEST_CODE = 20260
    }

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _managedDisplayIds = MutableStateFlow<Set<Int>>(emptySet())
    override val managedDisplayIds: StateFlow<Set<Int>> = _managedDisplayIds.asStateFlow()

    private val bridgeDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG, "Coroutine execution failed inside Repository", exception)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private val binder = ShizukuServiceBinder(context.applicationContext)
    private var displayService: IDisplayService? = null
    private var displayManager: DisplayManager? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "onDisplayAdded: $displayId")
            refreshManagedDisplays()
        }
        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "onDisplayRemoved: $displayId")
            refreshManagedDisplays()
        }
        override fun onDisplayChanged(displayId: Int) {
            refreshManagedDisplays()
        }
    }

    init {
        scope.launch {
            binder.connectionStatus.collect { status ->
                Log.d(TAG, "[DIAGNOSTIC] Shizuku connection status updated: $status")
                _connectionStatus.value = status
                if (status == ConnectionStatus.CONNECTED) {
                    refreshManagedDisplays()
                } else if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.IDLE) {
                    _managedDisplayIds.value = emptySet()
                }
            }
        }

        scope.launch {
            binder.service.collect { service ->
                displayService = service
            }
        }
    }

    override fun bindService(context: Context) {
        if (displayManager == null) {
            displayManager = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager?.registerDisplayListener(displayListener, null)
            Log.d(TAG, "DisplayListener registered in bindService")
        }
        if (!isShizukuAvailable()) {
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }
        binder.bind()
    }

    override fun unbindService() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        binder.unbind()
    }

    private fun refreshManagedDisplays() {
        scope.launch {
            try {
                val ids = withContext(bridgeDispatcher) {
                    displayService?.activeDisplayIds?.toSet() ?: emptySet()
                }
                _managedDisplayIds.value = ids
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh managed displays", e)
            }
        }
    }

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Result<Int> = withContext(bridgeDispatcher) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            val displayId = svc.createVirtualDisplay(name, width, height, dpi, null, buildDefaultFlags())
            refreshManagedDisplays()
            displayId
        }
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> = withContext(bridgeDispatcher) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.releaseVirtualDisplay(displayId)
            refreshManagedDisplays()
        }
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> = withContext(bridgeDispatcher) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.setVirtualDisplaySurface(displayId, surface)
        }
    }

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> = withContext(bridgeDispatcher) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.resizeVirtualDisplay(displayId, width, height, dpi)
        }
    }

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> = withContext(bridgeDispatcher) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }.toBundle()
            
            svc.startActivity(intent, options)
        }
    }

    override suspend fun launchHome(displayId: Int): Result<Int> = withContext(bridgeDispatcher) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }.toBundle()
            
            svc.startActivity(intent, options)
        }
    }

    override suspend fun injectInput(event: InputEvent): Result<Boolean> = withContext(bridgeDispatcher) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.injectInputEvent(event, 0)
        }
    }

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> = withContext(bridgeDispatcher) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.injectInputEventWithDisplayId(event, displayId, 0)
        }
    }



    override fun isShizukuAvailable(): Boolean {
        return try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    }

    override fun destroyService() {
        try {
            displayService?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy service", e)
        }
    }

    private fun buildDefaultFlags(): Int {
        var flags = 1 or 2 or 8 or 64 or 128 or 256 or 512
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or 1024 or 2048 or 4096 or 8192
            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or 16384 or 32768
            }
        }
        return flags
    }
}
