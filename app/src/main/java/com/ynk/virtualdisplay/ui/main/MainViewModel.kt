package com.ynk.virtualdisplay.ui.main

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainViewModel(
    val repository: IDisplayRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshDisplays(appContext)
        }
        override fun onDisplayRemoved(displayId: Int) {
            refreshDisplays(appContext)
        }
        override fun onDisplayChanged(displayId: Int) {
            refreshDisplays(appContext)
        }
    }

    private val REQUEST_PERMISSION_RESULT_LISTENER = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.d("MainViewModel", "Shizuku permission result: requestCode=$requestCode, granted=$granted")
        _uiState.update { it.copy(
            shizukuState = if (granted) ShizukuState.Ready else ShizukuState.PermissionDenied
        ) }
    }

    init {
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
        
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.registerDisplayListener(displayListener, null)
        
        // 订阅 Repository 状态
        viewModelScope.launch {
            repository.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
        
        viewModelScope.launch {
            repository.managedDisplayIds.collect { _ ->
                refreshDisplays(appContext)
            }
        }
    }

    fun switchTab(tab: ScreenTab) {
        _uiState.update { it.copy(currentTab = tab) }
        if (tab == ScreenTab.CONSOLE) {
            reloadDefaultInputsFromSettings(appContext)
        }
    }

    @Suppress("DEPRECATION")
    fun reloadDefaultInputsFromSettings(context: Context) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager

        val defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        defaultDisplay?.getRealSize(size)
        val metrics = DisplayMetrics()
        defaultDisplay?.getRealMetrics(metrics)

        val prefs = context.getSharedPreferences("virtual_display_settings", Context.MODE_PRIVATE)
        val defaultW = prefs.getString("pref_default_width", "") ?: ""
        val defaultH = prefs.getString("pref_default_height", "") ?: ""
        val defaultDpi = prefs.getString("pref_default_dpi", "") ?: ""

        val w = defaultW.ifEmpty { size.x.toString() }
        val h = defaultH.ifEmpty { size.y.toString() }
        val dpi = defaultDpi.ifEmpty { metrics.densityDpi.toString() }

        _uiState.update { state ->
            state.copy(
                inputWidth = w,
                inputHeight = h,
                inputDpi = dpi
            )
        }
    }

    fun launchSelectedApp(context: Context, displayId: Int, packageName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Launching $packageName on $displayId...") }
            repository.launchApp(packageName, displayId)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Launched $packageName") }
                }
                .onFailure { e ->
                    Log.e("MainViewModel", "Failed to launch app", e)
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Launch failed: ${e.message}") }
                }
        }
    }

    @Suppress("DEPRECATION")
    private fun checkAndInitDeviceMetrics(context: Context) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        defaultDisplay?.getRealSize(size)
        val metrics = DisplayMetrics()
        defaultDisplay?.getRealMetrics(metrics)

        val prefs = context.getSharedPreferences("virtual_display_settings", Context.MODE_PRIVATE)
        val defaultW = prefs.getString("pref_default_width", "") ?: ""
        val defaultH = prefs.getString("pref_default_height", "") ?: ""
        val defaultDpi = prefs.getString("pref_default_dpi", "") ?: ""

        val w = defaultW.ifEmpty { size.x.toString() }
        val h = defaultH.ifEmpty { size.y.toString() }
        val dpi = defaultDpi.ifEmpty { metrics.densityDpi.toString() }

        _uiState.update { state ->
            state.copy(
                inputWidth = state.inputWidth.ifEmpty { w },
                inputHeight = state.inputHeight.ifEmpty { h },
                inputDpi = state.inputDpi.ifEmpty { dpi }
            )
        }
    }

    fun checkShizukuStatus(context: Context) {
        checkAndInitDeviceMetrics(context)
        if (!repository.isShizukuAvailable()) {
            _uiState.update { it.copy(shizukuState = ShizukuState.NotRunning) }
            return
        }

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _uiState.update { it.copy(shizukuState = ShizukuState.Ready) }
                repository.bindService(context)
            } else {
                _uiState.update { it.copy(shizukuState = ShizukuState.PermissionDenied) }
            }
        } catch (e: IllegalStateException) {
            Log.w("MainViewModel", "Shizuku not attached yet, waiting for binder...", e)
            _uiState.update { it.copy(shizukuState = ShizukuState.Checking) }
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    checkShizukuStatus(context)
                }
            }
            Shizuku.addBinderReceivedListener(listener)
        }
    }

    fun updateInputs(width: String? = null, height: String? = null, dpi: String? = null) {
        _uiState.update { state ->
            state.copy(
                inputWidth = width ?: state.inputWidth,
                inputHeight = height ?: state.inputHeight,
                inputDpi = dpi ?: state.inputDpi
            )
        }
    }

    fun createVirtualDisplay(context: Context) {
        val state = _uiState.value
        val w = state.inputWidth.toIntOrNull() ?: 0
        val h = state.inputHeight.toIntOrNull() ?: 0
        val d = state.inputDpi.toIntOrNull() ?: 0

        if (w <= 0 || h <= 0 || d <= 0) {
            _uiState.update { it.copy(statusMessage = "Error: Invalid dimensions or DPI") }
            return
        }

        // 校验比例：长宽比/宽长比 =< 2.5
        val ratio = if (w > h) w.toFloat() / h else h.toFloat() / w
        if (ratio > 2.5f) {
            _uiState.update { it.copy(statusMessage = "Error: Aspect ratio too extreme (max 2.5, current ${String.format(java.util.Locale.US, "%.2f", ratio)})") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Creating display ${w}x${h}...") }
            repository.createDisplay(
                name = "Shizuku_VD_${System.currentTimeMillis()}",
                width = w,
                height = h,
                dpi = d
            ).onSuccess { displayId ->
                _uiState.update { it.copy(
                    isLoading = false, 
                    statusMessage = "Created Display ID: $displayId"
                ) }
                refreshDisplays(context)
            }.onFailure { e ->
                Log.e("MainViewModel", "Failed to create display", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}"
                ) }
            }
        }
    }

    fun refreshDisplays(context: Context) {
        checkAndInitDeviceMetrics(context)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val managedByService = repository.managedDisplayIds.value
        
        val displaysList = mutableListOf<DisplayInfoModel>()
        val orphans = mutableListOf<Int>()
        
        dm.displays.forEach { display ->
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                displaysList.add(
                    DisplayInfoModel(
                        id = display.displayId,
                        name = display.name,
                        width = size.x,
                        height = size.y
                    )
                )
                if (repository.connectionStatus.value == ConnectionStatus.CONNECTED && display.displayId !in managedByService) {
                    orphans.add(display.displayId)
                }
            }
        }
        
        _uiState.update { it.copy(
            displays = displaysList,
            orphanDisplayIds = orphans,
            statusMessage = if (it.statusMessage.startsWith("Error:")) it.statusMessage else "Displays refreshed"
        ) }
    }

    fun releaseDisplay(displayId: Int, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Releasing display $displayId...") }
            repository.releaseDisplay(displayId)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Released $displayId") }
                    refreshDisplays(context)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Release failed: ${e.message}") }
                }
        }
    }

    fun forceRestartService(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "重启守护进程...") }
            try {
                repository.unbindService()
                kotlinx.coroutines.delay(500)
                repository.bindService(context)
                _uiState.update { it.copy(isLoading = false, statusMessage = "守护进程已重启") }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to restart service", e)
                _uiState.update { it.copy(isLoading = false, statusMessage = "重启失败: ${e.message}") }
            }
        }
    }



    override fun onCleared() {
        super.onCleared()
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.unregisterDisplayListener(displayListener)
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }
}

class MainViewModelFactory(
    private val repository: IDisplayRepository,
    private val context: Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
