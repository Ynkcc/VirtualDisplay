package com.ynk.virtualdisplay.ui.main

import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.util.NetUtils

/**
 * 虚拟显示器元数据模型
 */
data class DisplayInfoModel(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val mirrorDisplayId: Int = -1,
    val isOwned: Boolean = false,
    val ownerPackage: String? = null,
    val ownerUid: Int = 0
)

enum class ScreenTab {
    CONSOLE,
    SETTINGS
}

/**
 * 统一的界面状态聚合类（MVI State）
 */
data class MainUiState(
    val shizukuState: ShizukuState = ShizukuState.Checking,
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val connectionError: String? = null,
    val displays: List<DisplayInfoModel> = emptyList(),
    val orphanDisplayIds: List<Int> = emptyList(),
    val statusMessage: String = "Ready",
    val isLoading: Boolean = false,
    val currentTab: ScreenTab = ScreenTab.CONSOLE,
    val isRestartCooldown: Boolean = false,
    val daemonPid: Int = -1,
    // 输入相关状态
    val inputWidth: String = "",
    val inputHeight: String = "",
    val inputDpi: String = "",
    
    // 权限与设备切换状态
    val privilegeMode: com.ynk.virtualdisplay.data.PrivilegeMode = com.ynk.virtualdisplay.data.PrivilegeMode.SHIZUKU,
    val serverNodes: List<com.ynk.virtualdisplay.data.ServerNode> = emptyList(),
    val currentServerNode: com.ynk.virtualdisplay.data.ServerNode = com.ynk.virtualdisplay.data.ServerNode("本机", NetUtils.LOCAL_HOST, 27183, ""),
    val rootAvailable: Boolean = false,
    val rootChecking: Boolean = false
)

/**
 * MVI Intent：用户意图的密封类表示。
 * ViewModel 通过 [MainViewModel.handleIntent] 统一分发。
 */
sealed class MainIntent {
    /** 检查 Shizuku 状态 */
    data object CheckShizuku : MainIntent()
    /** 请求 Shizuku 权限 */
    data object RequestShizukuPermission : MainIntent()
    /** 刷新显示器列表 */
    data object RefreshDisplays : MainIntent()
    /** 切换 Tab */
    data class SwitchTab(val tab: ScreenTab) : MainIntent()
    /** 创建虚拟显示器 */
    data class CreateDisplay(val width: String, val height: String, val dpi: String, val mirrorDisplayId: Int = -1) : MainIntent()
    /** 释放显示器。moveTasksToDefaultDisplay=true 销毁前将应用移回主屏，false 则完全交给系统处理 */
    data class ReleaseDisplay(val displayId: Int, val moveTasksToDefaultDisplay: Boolean = true) : MainIntent()
    /** 在显示器上启动应用 */
    data class LaunchApp(val packageName: String, val displayId: Int) : MainIntent()
    /** 重启守护进程服务 */
    data object RestartService : MainIntent()
    /** 更新输入框值 */
    data class UpdateInputs(val width: String? = null, val height: String? = null, val dpi: String? = null) : MainIntent()
    /** 绑定服务 */
    data object BindService : MainIntent()
    /** 解绑服务 */
    data object UnbindService : MainIntent()
    /** 重连（unbind + bind，防重入） */
    data object Reconnect : MainIntent()

    // 外部服务端及特权切换 Intent
    data class SelectServerNode(val node: com.ynk.virtualdisplay.data.ServerNode) : MainIntent()
    data class AddServerNode(val node: com.ynk.virtualdisplay.data.ServerNode) : MainIntent()
    data class EditServerNode(val oldNode: com.ynk.virtualdisplay.data.ServerNode, val newNode: com.ynk.virtualdisplay.data.ServerNode) : MainIntent()
    data class RemoveServerNode(val node: com.ynk.virtualdisplay.data.ServerNode) : MainIntent()
    data class UpdatePrivilegeMode(val mode: com.ynk.virtualdisplay.data.PrivilegeMode) : MainIntent()
    data object CheckRootPermission : MainIntent()
    data object StartServer : MainIntent()
    data object StopServer : MainIntent()
}

/**
 * MVI Effect：一次性副作用事件。
 * UI 通过 SharedFlow 订阅，不会在配置变更时重放。
 */
sealed class MainEffect {
    /** 显示 Toast 消息 */
    data class ShowToast(val message: String) : MainEffect()
    /** 显示错误详情 */
    data class ShowError(val message: String) : MainEffect()
}
