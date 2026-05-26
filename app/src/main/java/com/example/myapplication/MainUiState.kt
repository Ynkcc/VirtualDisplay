package com.example.myapplication

import com.example.myapplication.models.ShizukuState

/**
 * 统一的界面状态聚合类
 */
data class MainUiState(
    val shizukuState: ShizukuState = ShizukuState.Checking,
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val displayIds: List<Int> = emptyList(),
    val orphanDisplayIds: List<Int> = emptyList(),
    val statusMessage: String = "Ready",
    val isLoading: Boolean = false,
    
    // 输入相关状态
    val inputWidth: String = "",
    val inputHeight: String = "",
    val inputDpi: String = "",

    // 物理屏幕控制状态
    val isPhysicalScreenOn: Boolean = true
)
