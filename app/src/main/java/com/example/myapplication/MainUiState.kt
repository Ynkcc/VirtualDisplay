package com.example.myapplication

import com.example.myapplication.models.ShizukuState

/**
 * 虚拟显示器元数据模型
 */
data class DisplayInfoModel(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int
)

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
    
    // 输入相关状态
    val inputWidth: String = "",
    val inputHeight: String = "",
    val inputDpi: String = ""
)
