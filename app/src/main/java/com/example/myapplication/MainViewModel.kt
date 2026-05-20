package com.example.myapplication

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
import com.example.myapplication.models.ShizukuState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainViewModel(
    private val repository: IDisplayRepository = ShizukuDisplayBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val REQUEST_PERMISSION_RESULT_LISTENER = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.d("MainViewModel", "Shizuku permission result: requestCode=$requestCode, granted=$granted")
        _uiState.update { it.copy(
            shizukuState = if (granted) ShizukuState.Ready else ShizukuState.PermissionDenied
        ) }
    }

    init {
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
        
        // 订阅 Repository 状态
        viewModelScope.launch {
            repository.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
        
        viewModelScope.launch {
            repository.managedDisplayIds.collect { managedIds ->
                updateDisplayLists(managedIds)
            }
        }
    }

    private fun updateDisplayLists(managedIds: Set<Int>) {
        // 由于需要 Context 获取完整的 DisplayManager 信息，
        // 我们可以在 init 中保存一个参考，或者在 collect 时由外部触发。
        // 但最简单的方法是在这里直接更新 displayIds 状态，
        // 真正的同步由 repository 触发 refreshManagedDisplays，
        // 然后 ViewModel 这里的 collect 会被叫到。
        _uiState.update { it.copy(
            displayIds = managedIds.toList(),
            statusMessage = "Displays updated: ${managedIds.size}"
        ) }
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

        val w = size.x.toString()
        val h = size.y.toString()
        val dpi = metrics.densityDpi.toString()

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
        if (!ShizukuDisplayBridge.isShizukuAvailable()) {
            _uiState.update { it.copy(shizukuState = ShizukuState.NotRunning) }
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            _uiState.update { it.copy(shizukuState = ShizukuState.Ready) }
            repository.bindService(context)
        } else {
            _uiState.update { it.copy(shizukuState = ShizukuState.PermissionDenied) }
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
            _uiState.update { it.copy(statusMessage = "Error: Aspect ratio too extreme (max 2.5, current ${String.format("%.2f", ratio)})") }
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
        
        val allIds = mutableListOf<Int>()
        val orphans = mutableListOf<Int>()
        
        dm.displays.forEach { display ->
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                allIds.add(display.displayId)
                if (managedByService.isNotEmpty() && display.displayId !in managedByService) {
                    orphans.add(display.displayId)
                }
            }
        }
        
        _uiState.update { it.copy(
            displayIds = allIds,
            orphanDisplayIds = orphans,
            statusMessage = "Displays refreshed"
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
            _uiState.update { it.copy(isLoading = true, statusMessage = "Force restarting service...") }
            try {
                ShizukuDisplayBridge.destroyService()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to destroy service", e)
            }
            kotlinx.coroutines.delay(1000)
            repository.bindService(context)
            _uiState.update { it.copy(isLoading = false, statusMessage = "Service restarted") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }
}
