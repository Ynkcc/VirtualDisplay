package com.ynk.virtualdisplay.ui.main

import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.ConnectionStatus

/**
 * 虚拟显示器元数据模型
 */
data class DisplayInfoModel(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int
)

enum class ScreenTab {
    CONSOLE,
    SETTINGS
}

/**
 * 统一的界面状态聚合类
 */
data class MainUiState(
    val shizukuState: ShizukuState = ShizukuState.Checking,
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
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
    val inputDpi: String = ""
)
