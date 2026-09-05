package com.ynk.virtualdisplay.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.domain.DisplayInteractor
import com.ynk.virtualdisplay.manager.DisplayMetricsManager
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.util.NetUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val settingsDataSource: AppSettingsDataSource
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

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
                // 连接建立后立即重新评估孤儿/接管状态。
                // 否则进入页面时 orphan 判定可能基于尚未更新的连接状态/快照执行，
                // 且 managedDisplayIds 可能因值与初值相同而不发射，导致误判为“已连接”。
                if (status == ConnectionStatus.CONNECTED) {
                    refreshDisplaysInternal()
                }
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

        // 订阅持有者信息变化（随刷新异步到达，需在更新后重建列表）
        viewModelScope.launch {
            interactor.displayOwners.collect {
                refreshDisplaysInternal()
            }
        }

        // 订阅 PrivilegeMode 和 ServerNode 变化
        viewModelScope.launch {
            settingsDataSource.privilegeModeFlow().collect { mode ->
                _uiState.update { it.copy(privilegeMode = mode) }
            }
        }
        viewModelScope.launch {
            settingsDataSource.serverNodesFlow().collect { list ->
                _uiState.update { it.copy(serverNodes = list) }
            }
        }
        viewModelScope.launch {
            settingsDataSource.currentServerNodeCache.collect { node ->
                _uiState.update { it.copy(currentServerNode = node) }
                interactor.setActiveNode(node) // Sync active node in repository
                reloadDefaultInputsFromSettings(force = true)
                refreshDisplaysInternal()
            }
        }
    }

    fun handleIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.InitializeConnection -> initializeAndBind()
            is MainIntent.RequestShizukuPermission -> shizukuManager.requestPermission()
            is MainIntent.RefreshDisplays -> refreshDisplays()
            is MainIntent.SwitchTab -> {
                _uiState.update { it.copy(currentTab = intent.tab) }
                if (intent.tab == ScreenTab.CONSOLE) {
                    reloadDefaultInputsFromSettings()
                }
            }
            is MainIntent.CreateDisplay -> createVirtualDisplay(intent.width, intent.height, intent.dpi, intent.mirrorDisplayId)
            is MainIntent.ReleaseDisplay -> releaseDisplay(intent.displayId, intent.moveTasksToDefaultDisplay)
            is MainIntent.LaunchApp -> launchSelectedApp(intent.packageName, intent.displayId)
            is MainIntent.RestartService -> forceRestartService()
            is MainIntent.UpdateInputs -> updateInputs(intent.width, intent.height, intent.dpi)
            is MainIntent.BindService -> interactor.bindService()
            is MainIntent.UnbindService -> interactor.unbindService()
            is MainIntent.Reconnect -> interactor.reconnect()
            
            is MainIntent.SelectServerNode -> selectServerNode(intent.node)
            is MainIntent.AddServerNode -> addServerNode(intent.node)
            is MainIntent.EditServerNode -> editServerNode(intent.oldNode, intent.newNode)
            is MainIntent.RemoveServerNode -> removeServerNode(intent.node)
            is MainIntent.UpdatePrivilegeMode -> updatePrivilegeMode(intent.mode)
            is MainIntent.CheckRootPermission -> checkRootPermission()
            is MainIntent.StartServer -> startServer()
            is MainIntent.StopServer -> stopServer()
            is MainIntent.ConnectServer -> connectServer()
            is MainIntent.DisconnectServer -> disconnectServer()
        }
    }

    // === 特权与连接相关 ===

    private fun initializeAndBind() {
        checkAndInitDeviceMetrics()
        // 仅建立连接：拉起 daemon 的唯一入口是设置页"启动"，连接流程不自动拉起进程。
        interactor.bindService()
    }

    private fun selectServerNode(node: ServerNode) {
        viewModelScope.launch {
            settingsDataSource.setCurrentServerNode(node)
            interactor.setActiveNode(node)
            initializeAndBind()
        }
    }

    private fun addServerNode(node: ServerNode) {
        viewModelScope.launch {
            settingsDataSource.addServerNode(node)
        }
    }

    private fun editServerNode(oldNode: ServerNode, newNode: ServerNode) {
        viewModelScope.launch {
            settingsDataSource.updateServerNode(oldNode, newNode)
            val current = _uiState.value.currentServerNode
            if (current.host == oldNode.host && current.port == oldNode.port) {
                selectServerNode(newNode)
            }
        }
    }

    private fun removeServerNode(node: ServerNode) {
        viewModelScope.launch {
            settingsDataSource.removeServerNode(node)
            val current = _uiState.value.currentServerNode
            if (current.host == node.host && current.port == node.port) {
                val localNode = ServerNode("本机", NetUtils.LOCAL_HOST, 27183, "")
                selectServerNode(localNode)
            }
        }
    }

    private fun updatePrivilegeMode(mode: PrivilegeMode) {
        viewModelScope.launch {
            settingsDataSource.setPrivilegeMode(mode)
        }
    }

    private fun checkRootPermission() {
        _uiState.update { it.copy(rootChecking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val isRoot = interactor.isRootAvailable()
            _uiState.update { it.copy(rootAvailable = isRoot, rootChecking = false) }
        }
    }

    // === 显示器列表刷新 ===

    fun refreshDisplays() {
        refreshDisplaysInternal()
        checkAndInitDeviceMetrics()
    }

    private fun refreshDisplaysInternal() {
        interactor.refreshDisplays()
        val currentNode = _uiState.value.currentServerNode

        viewModelScope.launch {
            // 连接断开时清空屏幕列表：不再展示无法确认状态的旧显示器。
            // 重新连接成功后，会重新从本地配置读取对应服务器的列表并恢复。
            val isConnected = interactor.connectionStatus.value == ConnectionStatus.CONNECTED
            if (!isConnected) {
                _uiState.update { it.copy(
                    displays = emptyList(),
                    orphanDisplayIds = emptyList()
                ) }
                return@launch
            }

            val savedDisplays = settingsDataSource.getDisplaysForServer(currentNode)
            // 在协程内读取“当前”状态：进入函数时 managedDisplayIds 可能尚未随异步刷新完成更新，
            // 若在函数入口捕获快照，刚进入页面时孤儿显示器会被误判为“已连接”，须手动刷新才恢复。
            // 组装与孤儿判定下沉到 DisplayInteractor.buildDisplayModels。
            val result = interactor.buildDisplayModels(
                savedDisplays = savedDisplays,
                owners = interactor.displayOwners.value,
                managedByService = interactor.managedDisplayIds.value,
                isConnected = isConnected
            )

            _uiState.update { it.copy(
                displays = result.displays,
                orphanDisplayIds = result.orphanDisplayIds,
                statusMessage = if (it.statusMessage.startsWith("Error:")) it.statusMessage else "Displays refreshed"
            ) }
        }
    }

    // === 输入框默认值 ===

    private fun checkAndInitDeviceMetrics(force: Boolean = false) {
        val deviceSpec = displayMetricsManager.getDefaultDisplaySpec()
            ?: DisplayMetricsManager.DisplaySpec(1080, 1920, 420)
        viewModelScope.launch {
            val (defaultW, defaultH, defaultDpi) = settingsDataSource.getPrefDefaults()
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
                // 仅简化创建时传入的名称（去掉毫秒时间戳），展示逻辑保持不变。
                name = interactor.defaultDisplayName(mirrorDisplayId),
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

    private fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Releasing display $displayId...") }
            interactor.releaseDisplay(displayId, moveTasksToDefaultDisplay)
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

    suspend fun listApps(forceRefresh: Boolean = false): Result<List<DeviceMessage.AppEntry>> {
        return interactor.listApps(forceRefresh)
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
                delay(2000)
                _uiState.update { it.copy(isRestartCooldown = false) }
            }
        }
    }

    private fun startServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "正在拉起本地服务端...") }
            interactor.startDaemon()
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "本地服务端已拉起（未连接）") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, statusMessage = "拉起失败: ${e.message}") }
                }
        }
    }

    private fun connectServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "正在连接服务端...") }
            interactor.connect()
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "连接请求已发起") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, statusMessage = "连接失败: ${e.message}") }
                }
        }
    }

    private fun disconnectServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "正在断开连接...") }
            interactor.disconnect()
            _uiState.update { it.copy(isLoading = false, statusMessage = "已断开连接") }
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
