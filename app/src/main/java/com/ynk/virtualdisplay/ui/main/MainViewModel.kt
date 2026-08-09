package com.ynk.virtualdisplay.ui.main

import android.content.Context
import android.util.Log
import android.view.Display
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.domain.DisplayInteractor
import com.ynk.virtualdisplay.manager.DisplayMetricsManager
import com.ynk.virtualdisplay.manager.ShizukuManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel（MVI 模式）。
 *
 * 职责：
 * - 持有 [MainUiState] 状态流，供 Compose UI 订阅
 * - 通过 [handleIntent] 统一分发用户意图（MVI Intent）
 * - 通过 [effect] 推送一次性副作用事件（Toast / Error）
 * - 委派业务逻辑给 [DisplayInteractor]
 * - 委派 Shizuku 状态管理给 [ShizukuManager]
 * - 委派显示信息查询给 [DisplayMetricsManager]
 *
 * 构造参数全部通过 Koin 注入，无需手写 Factory。
 */
class MainViewModel(
    private val interactor: DisplayInteractor,
    private val shizukuManager: ShizukuManager,
    private val displayMetricsManager: DisplayMetricsManager,
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val appContext = context.applicationContext

    // === MVI State ===
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // === MVI Effect ===
    private val _effect = MutableSharedFlow<MainEffect>(extraBufferCapacity = 16)
    val effect: SharedFlow<MainEffect> = _effect.asSharedFlow()

    init {
        // 订阅 ShizukuManager 状态流
        viewModelScope.launch {
            shizukuManager.shizukuState.collect { state ->
                _uiState.update { it.copy(shizukuState = state) }
            }
        }

        // 订阅 Repository 连接状态
        viewModelScope.launch {
            interactor.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }

        // 订阅连接错误
        viewModelScope.launch {
            interactor.connectionError.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(statusMessage = "连接错误: $error") }
                }
            }
        }

        // 订阅 Daemon PID
        viewModelScope.launch {
            interactor.daemonPid.collect { pid ->
                _uiState.update { it.copy(daemonPid = pid) }
            }
        }

        // 订阅管理中的显示器列表变化
        viewModelScope.launch {
            interactor.managedDisplayIds.collect {
                refreshDisplaysInternal()
            }
        }
    }

    /**
     * MVI 意图分发入口。所有用户操作都通过此方法进入。
     */
    fun handleIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.CheckShizuku -> checkShizuku()
            is MainIntent.RequestShizukuPermission -> shizukuManager.requestPermission()
            is MainIntent.RefreshDisplays -> refreshDisplays()
            is MainIntent.SwitchTab -> {
                _uiState.update { it.copy(currentTab = intent.tab) }
                if (intent.tab == ScreenTab.CONSOLE) {
                    reloadDefaultInputsFromSettings()
                }
            }
            is MainIntent.CreateDisplay -> createVirtualDisplay(intent.width, intent.height, intent.dpi)
            is MainIntent.ReleaseDisplay -> releaseDisplay(intent.displayId)
            is MainIntent.LaunchApp -> launchSelectedApp(intent.packageName, intent.displayId)
            is MainIntent.RestartService -> forceRestartService()
            is MainIntent.UpdateInputs -> updateInputs(intent.width, intent.height, intent.dpi)
            is MainIntent.BindService -> interactor.bindService()
            is MainIntent.UnbindService -> interactor.unbindService()
        }
    }

    // === Shizuku 相关 ===

    private fun checkShizuku() {
        shizukuManager.refreshState()
        checkAndInitDeviceMetrics()
        if (shizukuManager.isAvailable()) {
            interactor.bindService()
        }
    }

    // === 显示器列表刷新 ===

    fun refreshDisplays() {
        refreshDisplaysInternal()
        checkAndInitDeviceMetrics()
    }

    private fun refreshDisplaysInternal() {
        interactor.refreshDisplays()
        val managedByService = interactor.managedDisplayIds.value

        val displaysList = mutableListOf<DisplayInfoModel>()
        val orphans = mutableListOf<Int>()

        displayMetricsManager.getVirtualDisplays().forEach { display ->
            val spec = displayMetricsManager.getVirtualDisplaySpec(display)
            displaysList.add(
                DisplayInfoModel(
                    id = display.displayId,
                    name = display.name,
                    width = spec.width,
                    height = spec.height
                )
            )
            if (interactor.connectionStatus.value == ConnectionStatus.CONNECTED && display.displayId !in managedByService) {
                orphans.add(display.displayId)
            }
        }

        _uiState.update { it.copy(
            displays = displaysList,
            orphanDisplayIds = orphans,
            statusMessage = if (it.statusMessage.startsWith("Error:")) it.statusMessage else "Displays refreshed"
        ) }
    }

    // === 输入框默认值 ===

    private fun checkAndInitDeviceMetrics() {
        val deviceSpec = displayMetricsManager.getDefaultDisplaySpec()
            ?: DisplayMetricsManager.DisplaySpec(1080, 1920, 420)
        viewModelScope.launch {
            val (defaultW, defaultH, defaultDpi) = AppSettings.getPrefDefaults(appContext)
            _uiState.update { state ->
                state.copy(
                    inputWidth = state.inputWidth.ifEmpty { defaultW.ifEmpty { deviceSpec.width.toString() } },
                    inputHeight = state.inputHeight.ifEmpty { defaultH.ifEmpty { deviceSpec.height.toString() } },
                    inputDpi = state.inputDpi.ifEmpty { defaultDpi.ifEmpty { deviceSpec.dpi.toString() } }
                )
            }
        }
    }

    fun reloadDefaultInputsFromSettings() {
        checkAndInitDeviceMetrics()
    }

    // === 创建虚拟显示器 ===

    private fun createVirtualDisplay(widthStr: String, heightStr: String, dpiStr: String) {
        val w = widthStr.toIntOrNull() ?: 0
        val h = heightStr.toIntOrNull() ?: 0
        val d = dpiStr.toIntOrNull() ?: 0

        if (w <= 0 || h <= 0 || d <= 0) {
            _uiState.update { it.copy(statusMessage = "Error: Invalid dimensions or DPI") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Creating display ${w}x${h}...") }
            interactor.createDisplay(
                name = "Shizuku_VD_${System.currentTimeMillis()}",
                width = w,
                height = h,
                dpi = d
            ).onSuccess { displayId ->
                _uiState.update { it.copy(isLoading = false, statusMessage = "Created Display ID: $displayId") }
                refreshDisplays()
            }.onFailure { e ->
                Log.e(TAG, "Failed to create display", e)
                _uiState.update { it.copy(isLoading = false, statusMessage = "Error: ${e.message}") }
            }
        }
    }

    // === 释放显示器 ===

    private fun releaseDisplay(displayId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Releasing display $displayId...") }
            interactor.releaseDisplay(displayId)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Released $displayId") }
                    refreshDisplays()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Release failed: ${e.message}") }
                }
        }
    }

    // === 启动应用 ===

    fun launchSelectedApp(packageName: String, displayId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Launching $packageName on $displayId...") }
            interactor.launchApp(packageName, displayId)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Launched $packageName") }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to launch app", e)
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Launch failed: ${e.message}") }
                }
        }
    }

    // === 重启服务 ===

    private fun forceRestartService() {
        if (_uiState.value.isRestartCooldown) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isRestartCooldown = true, statusMessage = "重启守护进程...") }
            try {
                interactor.restartDaemon()
                    .onSuccess {
                        _uiState.update { it.copy(isLoading = false, statusMessage = "守护进程已重启") }
                    }
                    .onFailure { e ->
                        _uiState.update { it.copy(isLoading = false, statusMessage = "重启失败: ${e.message}") }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service", e)
                _uiState.update { it.copy(isLoading = false, statusMessage = "重启失败: ${e.message}") }
            } finally {
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(isRestartCooldown = false) }
            }
        }
    }

    // === 更新输入框 ===

    private fun updateInputs(width: String?, height: String?, dpi: String?) {
        _uiState.update { state ->
            state.copy(
                inputWidth = width ?: state.inputWidth,
                inputHeight = height ?: state.inputHeight,
                inputDpi = dpi ?: state.inputDpi
            )
        }
    }

    // === 生命周期 ===

    override fun onCleared() {
        super.onCleared()
        interactor.unbindService()
    }
}
