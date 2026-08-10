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
import com.ynk.virtualdisplay.util.NetUtils
import kotlinx.coroutines.Dispatchers
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
                _uiState.update { it.copy(connectionError = error) }
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

        // 订阅 PrivilegeMode 和 ServerNode 变化
        viewModelScope.launch {
            AppSettings.privilegeModeFlow(appContext).collect { mode ->
                _uiState.update { it.copy(privilegeMode = mode) }
            }
        }
        viewModelScope.launch {
            AppSettings.serverNodesFlow(appContext).collect { list ->
                _uiState.update { it.copy(serverNodes = list) }
            }
        }
        viewModelScope.launch {
            AppSettings.currentServerNodeCache.collect { node ->
                _uiState.update { it.copy(currentServerNode = node) }
                interactor.setActiveNode(node) // Sync active node in repository
                reloadDefaultInputsFromSettings(force = true)
                refreshDisplaysInternal()
            }
        }
    }

    fun handleIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.CheckShizuku -> checkPrivilegeAndBind()
            is MainIntent.RequestShizukuPermission -> shizukuManager.requestPermission()
            is MainIntent.RefreshDisplays -> refreshDisplays()
            is MainIntent.SwitchTab -> {
                _uiState.update { it.copy(currentTab = intent.tab) }
                if (intent.tab == ScreenTab.CONSOLE) {
                    reloadDefaultInputsFromSettings()
                }
            }
            is MainIntent.CreateDisplay -> createVirtualDisplay(intent.width, intent.height, intent.dpi, intent.mirrorDisplayId)
            is MainIntent.ReleaseDisplay -> releaseDisplay(intent.displayId)
            is MainIntent.LaunchApp -> launchSelectedApp(intent.packageName, intent.displayId)
            is MainIntent.RestartService -> forceRestartService()
            is MainIntent.UpdateInputs -> updateInputs(intent.width, intent.height, intent.dpi)
            is MainIntent.BindService -> interactor.bindService()
            is MainIntent.UnbindService -> interactor.unbindService()
            
            is MainIntent.SelectServerNode -> selectServerNode(intent.node)
            is MainIntent.AddServerNode -> addServerNode(intent.node)
            is MainIntent.RemoveServerNode -> removeServerNode(intent.node)
            is MainIntent.UpdatePrivilegeMode -> updatePrivilegeMode(intent.mode)
            is MainIntent.CheckRootPermission -> checkRootPermission()
            is MainIntent.StartServer -> startServer()
            is MainIntent.StopServer -> stopServer()
        }
    }

    // === 特权与连接相关 ===

    private fun checkPrivilegeAndBind() {
        val mode = AppSettings.getPrivilegeModeSync()
        val node = AppSettings.getCurrentServerNodeSync()
        val isLocal = node.host == NetUtils.LOCAL_HOST || node.host == "localhost"

        checkAndInitDeviceMetrics()

        if (isLocal && mode != com.ynk.virtualdisplay.data.PrivilegeMode.NONE) {
            if (mode == com.ynk.virtualdisplay.data.PrivilegeMode.SHIZUKU) {
                shizukuManager.refreshState()
                if (shizukuManager.isAvailable()) {
                    interactor.bindService()
                } else {
                    _uiState.update { it.copy(statusMessage = "Shizuku 未就绪") }
                }
            } else if (mode == com.ynk.virtualdisplay.data.PrivilegeMode.ROOT) {
                viewModelScope.launch(Dispatchers.IO) {
                    val isRoot = isRootAvailable()
                    _uiState.update { it.copy(rootAvailable = isRoot) }
                    if (isRoot) {
                        interactor.bindService()
                    } else {
                        _uiState.update { it.copy(statusMessage = "Root 未授权") }
                    }
                }
            }
        } else {
            interactor.bindService()
        }
    }

    private fun selectServerNode(node: com.ynk.virtualdisplay.data.ServerNode) {
        viewModelScope.launch {
            AppSettings.setCurrentServerNode(appContext, node)
            interactor.setActiveNode(node)
            checkPrivilegeAndBind()
        }
    }

    private fun addServerNode(node: com.ynk.virtualdisplay.data.ServerNode) {
        viewModelScope.launch {
            AppSettings.addServerNode(appContext, node)
        }
    }

    private fun removeServerNode(node: com.ynk.virtualdisplay.data.ServerNode) {
        viewModelScope.launch {
            AppSettings.removeServerNode(appContext, node)
            val current = _uiState.value.currentServerNode
            if (current.host == node.host && current.port == node.port) {
                val localNode = com.ynk.virtualdisplay.data.ServerNode("本机", NetUtils.LOCAL_HOST, 27183, "")
                selectServerNode(localNode)
            }
        }
    }

    private fun updatePrivilegeMode(mode: com.ynk.virtualdisplay.data.PrivilegeMode) {
        viewModelScope.launch {
            AppSettings.setPrivilegeMode(appContext, mode)
            interactor.unbindService()
            kotlinx.coroutines.delay(300)
            checkPrivilegeAndBind()
        }
    }

    private fun checkRootPermission() {
        _uiState.update { it.copy(rootChecking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val isRoot = isRootAvailable()
            _uiState.update { it.copy(rootAvailable = isRoot, rootChecking = false) }
        }
    }

    private fun isRootAvailable(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            process.outputStream.use { os ->
                os.write("exit\n".toByteArray())
                os.flush()
            }
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
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
        val currentNode = _uiState.value.currentServerNode

        viewModelScope.launch {
            val savedDisplays = AppSettings.getDisplaysForServer(appContext, currentNode)
            val displaysList = savedDisplays.map { display ->
                DisplayInfoModel(
                    id = display.id,
                    name = display.name,
                    width = display.width,
                    height = display.height,
                    dpi = display.dpi,
                    mirrorDisplayId = display.mirrorDisplayId
                )
            }

            val orphans = mutableListOf<Int>()
            displaysList.forEach { display ->
                if (interactor.connectionStatus.value == ConnectionStatus.CONNECTED && display.id !in managedByService) {
                    orphans.add(display.id)
                }
            }

            _uiState.update { it.copy(
                displays = displaysList,
                orphanDisplayIds = orphans,
                statusMessage = if (it.statusMessage.startsWith("Error:")) it.statusMessage else "Displays refreshed"
            ) }
        }
    }

    // === 输入框默认值 ===

    private fun checkAndInitDeviceMetrics(force: Boolean = false) {
        val deviceSpec = displayMetricsManager.getDefaultDisplaySpec()
            ?: DisplayMetricsManager.DisplaySpec(1080, 1920, 420)
        viewModelScope.launch {
            val (defaultW, defaultH, defaultDpi) = AppSettings.getPrefDefaults(appContext)
            _uiState.update { state ->
                state.copy(
                    inputWidth = if (force) {
                        defaultW.ifEmpty { deviceSpec.width.toString() }
                    } else {
                        state.inputWidth.ifEmpty { defaultW.ifEmpty { deviceSpec.width.toString() } }
                    },
                    inputHeight = if (force) {
                        defaultH.ifEmpty { deviceSpec.height.toString() }
                    } else {
                        state.inputHeight.ifEmpty { defaultH.ifEmpty { deviceSpec.height.toString() } }
                    },
                    inputDpi = if (force) {
                        defaultDpi.ifEmpty { deviceSpec.dpi.toString() }
                    } else {
                        state.inputDpi.ifEmpty { defaultDpi.ifEmpty { deviceSpec.dpi.toString() } }
                    }
                )
            }
        }
    }

    fun reloadDefaultInputsFromSettings(force: Boolean = false) {
        checkAndInitDeviceMetrics(force)
    }

    // === 创建虚拟显示器 ===

    private fun createVirtualDisplay(widthStr: String, heightStr: String, dpiStr: String, mirrorDisplayId: Int = -1) {
        val w = widthStr.toIntOrNull() ?: 0
        val h = heightStr.toIntOrNull() ?: 0
        val d = dpiStr.toIntOrNull() ?: 0

        if (w <= 0 || h <= 0 || d <= 0) {
            _uiState.update { it.copy(statusMessage = "Error: Invalid dimensions or DPI") }
            return
        }

        viewModelScope.launch {
            val statusMsg = if (mirrorDisplayId >= 0) "Mirroring display $mirrorDisplayId..." else "Creating display ${w}x${h}..."
            _uiState.update { it.copy(isLoading = true, statusMessage = statusMsg) }
            interactor.createDisplay(
                name = if (mirrorDisplayId >= 0) "Mirror_VD_${mirrorDisplayId}" else "Shizuku_VD_${System.currentTimeMillis()}",
                width = w,
                height = h,
                dpi = d,
                mirrorDisplayId = mirrorDisplayId
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

    suspend fun launchHome(displayId: Int): Result<Int> {
        return interactor.launchHome(displayId)
    }

    suspend fun listApps(): Result<List<com.ynk.virtualdisplay.protocol.DeviceMessage.AppEntry>> {
        return interactor.listApps()
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

    private fun startServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "正在启动服务端...") }
            interactor.startDaemon()
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "服务端启动成功") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, statusMessage = "启动失败: ${e.message}") }
                }
        }
    }

    private fun stopServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "正在停止服务端...") }
            interactor.stopDaemon()
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "服务端已停止") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, statusMessage = "停止失败: ${e.message}") }
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
