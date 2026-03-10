package com.example.myapplication

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
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
        _uiState.update { currentState ->
            // 这里需要同步获取显示器列表，通常需要 Context
            // 在 ViewModel 中我们可以稍微取巧，或者让调用方传入 Context
            // 既然 refreshDisplays 已经有了逻辑，我们在这里只做简单的增量更新是不够的
            // 更好的做法是让 Repository 提供 Flow<List<Display>>
            currentState
        }
    }

    fun checkShizukuStatus(context: Context) {
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

    fun createVirtualDisplay(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Creating display...") }
            repository.createDisplay(
                name = "Shizuku_VD_${System.currentTimeMillis()}",
                width = 1280,
                height = 720,
                dpi = 240
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

    override fun onCleared() {
        super.onCleared()
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }
}
