package com.example.myapplication

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import rikka.shizuku.Shizuku

object ShizukuDisplayBridge : IDisplayRepository {
    private const val TAG = "ShizukuDisplayBridge"
    const val REQUEST_CODE = 20260

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _managedDisplayIds = MutableStateFlow<Set<Int>>(emptySet())
    override val managedDisplayIds: StateFlow<Set<Int>> = _managedDisplayIds.asStateFlow()

    @Volatile
    private lateinit var binder: ShizukuServiceBinder
    private var displayService: IDisplayService? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
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

    fun initialize(context: Context) {
        if (!::binder.isInitialized) {
            binder = ShizukuServiceBinder(context.applicationContext)
            
            scope.launch {
                binder.connectionStatus.collect { status ->
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
        
        if (displayManager == null) {
            displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager?.registerDisplayListener(displayListener, null)
            Log.d(TAG, "DisplayListener registered in initialize")
        }
    }

    override fun bindService(context: Context) {
        initialize(context)
        if (!isShizukuAvailable()) {
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }
        binder.bind()
    }

    override fun unbindService() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null // 设置为 null，以便下次 initialize 时重新注册
        if (::binder.isInitialized) {
            binder.unbind()
        }
    }

    private fun refreshManagedDisplays() {
        scope.launch {
            try {
                val ids = withContext(Dispatchers.IO) {
                    displayService?.activeDisplayIds?.toSet() ?: emptySet()
                }
                _managedDisplayIds.value = ids
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh managed displays", e)
            }
        }
    }

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            val displayId = svc.createVirtualDisplay(name, width, height, dpi, null, buildDefaultFlags())
            refreshManagedDisplays()
            displayId
        }
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.releaseVirtualDisplay(displayId)
            refreshManagedDisplays()
        }
    }

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.setVirtualDisplaySurface(displayId, surface)
        }
    }

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> = withContext(Dispatchers.IO) {
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
    
    suspend fun startActivity(intent: Intent, options: Bundle?): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.startActivity(intent, options)
        }
    }

    override suspend fun injectInput(event: InputEvent): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.injectInputEvent(event, 0)
        }
    }

    /**
     * 在 Shizuku 特权进程中构造并注入 KeyEvent，displayId 在特权侧设置——复用 scrcpy 方案。
     */
    suspend fun injectKeyEvent(action: Int, keyCode: Int, displayId: Int, repeat: Int = 0, metaState: Int = 0): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.injectKeyEvent(action, keyCode, repeat, metaState, displayId, 0)
        }
    }

    /**
     * 注入任意 InputEvent（含 MotionEvent），在 Shizuku 特权侧调用 setDisplayId——复用 scrcpy 方案。
     * app 进程只负责坐标缩放，不再反射设置 displayId。
     */
    suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val svc = displayService ?: throw IllegalStateException("Service not connected")
            svc.injectInputEventWithDisplayId(event, displayId, 0)
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    }

    private fun buildDefaultFlags(): Int {
        var flags = 1 or 2 or 8 or 64 or 128 or 256 or 512 // 对应系统常量
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or 1024 or 2048 or 4096 or 8192
            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or 16384 or 32768
            }
        }
        return flags
    }
}
