package com.ynk.virtualdisplay

import android.content.Context
import android.view.InputEvent
import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * 状态枚举定义
 */
enum class ConnectionStatus {
    IDLE,
    BINDING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

/**
 * 显示器管理核心仓库接口
 */
interface IDisplayRepository {
    /**
     * 连接状态流
     */
    val connectionStatus: StateFlow<ConnectionStatus>

    /**
     * 当前管理中的显示器 ID 列表
     */
    val managedDisplayIds: StateFlow<Set<Int>>

    /**
     * 绑定并初始化 Shizuku 服务
     */
    fun bindService(context: Context)

    /**
     * 解绑服务
     */
    fun unbindService()

    /**
     * 创建虚拟显示器 (挂起函数)
     */
    suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Result<Int>

    /**
     * 释放指定显示器 (挂起函数)
     */
    suspend fun releaseDisplay(displayId: Int): Result<Unit>

    /**
     * 设置显示器 Surface (挂起函数)
     */
    suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit>

    /**
     * 重新调整指定虚拟显示器的物理尺寸和 DPI (挂起函数)
     */
    suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit>

    /**
     * 在指定显示器启动应用 (挂起函数)
     */
    suspend fun launchApp(packageName: String, displayId: Int): Result<Int>

    /**
     * 在指定显示器启动主屏幕/Launcher (挂起函数)
     */
    suspend fun launchHome(displayId: Int): Result<Int>

    /**
     * 注入输入事件 (挂起函数)
     */
    suspend fun injectInput(event: InputEvent): Result<Boolean>

    /**
     * 注入带 DisplayId 的输入事件 (挂起函数)
     */
    suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean>



    /**
     * 判断 Shizuku 是否可用
     */
    fun isShizukuAvailable(): Boolean

    /**
     * 销毁远程特权服务
     */
    fun destroyService()
}
