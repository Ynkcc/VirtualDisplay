package com.ynk.virtualdisplay.data.local

import android.content.Context
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.model.DisplayFlag
import com.ynk.virtualdisplay.data.model.SavedDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 本地配置数据源：AppSettings（DataStore）的统一门面（Facade）。
 *
 * 屏蔽底层存储实现细节（DataStore / Key / 序列化），供上层（Repository、Domain、
 * Video、UI）调用时无需关心具体存储技术和节点隔离逻辑。
 *
 * 属于 appModule：依赖 AppSettings.init()，只有在 bootstrapCore 后才可用。
 *
 * 方法签名与 [AppSettings] 保持一一对应，按读取方式分类：
 * - `Flow<T>`：可观察流（对应 AppSettings 的 `xxxFlow`）
 * - `StateFlow<T>`：内存缓存快照（对应 AppSettings 的 `xxxCache`）
 * - `suspend fun ...(): T`：一次性异步读取（对应 AppSettings 的 `getXxx`）
 * - `fun getXxxSync()`：同步读缓存（对应 AppSettings 的 `getXxxSync`）
 * - `suspend fun setXxx(...)`：写入
 *
 * 说明：
 * - 显示器列表、Flags、recentApps、prefDefaults 等按 ServerNode 隔离的数据，
 *   默认使用「当前节点」（不传 node 参数的版本），也提供显式传 node 的版本。
 * - 本门面只做封装收敛，不改变 [AppSettings] 底层 DataStore 实现。
 */
class AppSettingsDataSource(private val context: Context) {

    // === 同步内存缓存快照（StateFlow）===

    /** 是否捕获返回键（内存缓存） */
    val captureBackCache: StateFlow<Boolean> = AppSettings.captureBackCache

    /** 是否显示质量诊断信息（内存缓存） */
    val showPerformanceStatsCache: StateFlow<Boolean> = AppSettings.showPerformanceStatsCache

    /** 特权模式（内存缓存） */
    val privilegeModeCache: StateFlow<PrivilegeMode> = AppSettings.privilegeModeCache

    /** 当前服务器节点（内存缓存） */
    val currentServerNodeCache: StateFlow<ServerNode> = AppSettings.currentServerNodeCache

    /** 是否自动启动服务端（内存缓存） */
    val autoStartServerCache: StateFlow<Boolean> = AppSettings.autoStartServerCache

    /** 是否开启超低延迟（内存缓存） */
    val ultraLowLatencyCache: StateFlow<Boolean> = AppSettings.ultraLowLatencyCache

    /** 销毁虚拟显示器后是否将应用移回主屏（内存缓存） */
    val moveTasksOnDestroyCache: StateFlow<Boolean> = AppSettings.moveTasksOnDestroyCache

    // === 同步读缓存（getXxxSync）===

    /** 同步读取特权模式 */
    fun getPrivilegeModeSync(): PrivilegeMode = AppSettings.getPrivilegeModeSync()

    /** 同步读取当前服务器节点 */
    fun getCurrentServerNodeSync(): ServerNode = AppSettings.getCurrentServerNodeSync()

    /** 同步读取是否自动启动服务端 */
    fun getAutoStartServerSync(): Boolean = AppSettings.getAutoStartServerSync()

    /** 同步读取是否开启超低延迟 */
    fun getUltraLowLatencySync(): Boolean = AppSettings.getUltraLowLatencySync()

    /** 同步读取销毁虚拟显示器后是否将应用移回主屏 */
    fun getMoveTasksOnDestroySync(): Boolean = AppSettings.getMoveTasksOnDestroySync()

    // === Daemon 连接配置（serverHost / serverPort / serverPassword）===

    /** 监听 Daemon 服务监听端口 Flow */
    fun serverPortFlow(): Flow<Int> = AppSettings.serverPortFlow(context)

    /** 获取 Daemon 服务监听端口（默认 27183） */
    suspend fun getServerPort(): Int = AppSettings.getServerPort(context)

    /** 设置 Daemon 服务监听端口 */
    suspend fun setServerPort(port: Int) = AppSettings.setServerPort(context, port)

    /** 监听 Daemon 绑定地址 Flow */
    fun serverHostFlow(): Flow<String> = AppSettings.serverHostFlow(context)

    /** 获取 Daemon 绑定地址（默认 127.0.0.1） */
    suspend fun getServerHost(): String = AppSettings.getServerHost(context)

    /** 设置 Daemon 绑定地址 */
    suspend fun setServerHost(host: String) = AppSettings.setServerHost(context, host)

    /** 监听 Daemon 服务连接密码 Flow */
    fun serverPasswordFlow(): Flow<String> = AppSettings.serverPasswordFlow(context)

    /** 获取 Daemon 服务连接密码 */
    suspend fun getServerPassword(): String = AppSettings.getServerPassword(context)

    /** 设置 Daemon 服务连接密码 */
    suspend fun setServerPassword(password: String) = AppSettings.setServerPassword(context, password)

    // === 质量诊断信息（showPerformanceStats）===

    /** 监听是否显示质量诊断信息 Flow */
    fun showPerformanceStatsFlow(): Flow<Boolean> = AppSettings.showPerformanceStatsFlow(context)

    /** 获取是否显示质量诊断信息 */
    suspend fun getShowPerformanceStats(): Boolean = AppSettings.getShowPerformanceStats(context)

    /** 设置是否显示质量诊断信息 */
    suspend fun setShowPerformanceStats(show: Boolean) = AppSettings.setShowPerformanceStats(context, show)

    // === 捕获返回键（captureBack）===

    /** 监听是否捕获返回键 Flow */
    fun captureBackFlow(): Flow<Boolean> = AppSettings.captureBackFlow(context)

    /** 设置是否捕获返回键 */
    suspend fun setCaptureBack(enabled: Boolean) = AppSettings.setCaptureBack(context, enabled)

    // === 特权模式 / 自动启动（privilegeMode / autoStartServer）===

    /** 监听特权模式 Flow */
    fun privilegeModeFlow(): Flow<PrivilegeMode> = AppSettings.privilegeModeFlow(context)

    /** 设置特权模式 */
    suspend fun setPrivilegeMode(mode: PrivilegeMode) = AppSettings.setPrivilegeMode(context, mode)

    /** 监听是否自动启动服务端 Flow */
    fun autoStartServerFlow(): Flow<Boolean> = AppSettings.autoStartServerFlow(context)

    /** 设置是否自动启动服务端 */
    suspend fun setAutoStartServer(enabled: Boolean) = AppSettings.setAutoStartServer(context, enabled)

    // === 超低延迟（ultraLowLatency）===

    /** 监听是否开启超低延迟 Flow */
    fun ultraLowLatencyFlow(): Flow<Boolean> = AppSettings.ultraLowLatencyFlow(context)

    /** 设置是否开启超低延迟 */
    suspend fun setUltraLowLatency(enabled: Boolean) = AppSettings.setUltraLowLatency(context, enabled)

    // === 移回主屏（moveTasksOnDestroy）===

    /** 监听销毁虚拟显示器后是否将应用移回主屏 Flow */
    fun moveTasksOnDestroyFlow(): Flow<Boolean> = AppSettings.moveTasksOnDestroyFlow(context)

    /** 设置销毁虚拟显示器后是否将应用移回主屏 */
    suspend fun setMoveTasksOnDestroy(enabled: Boolean) = AppSettings.setMoveTasksOnDestroy(context, enabled)

    // === 服务器节点列表（serverNodes / currentServerNode）===

    /** 监听服务器节点列表 Flow */
    fun serverNodesFlow(): Flow<List<ServerNode>> = AppSettings.serverNodesFlow(context)

    /** 获取服务器节点列表 */
    suspend fun getServerNodes(): List<ServerNode> = AppSettings.getServerNodes(context)

    /** 新增服务器节点 */
    suspend fun addServerNode(node: ServerNode) = AppSettings.addServerNode(context, node)

    /** 移除服务器节点 */
    suspend fun removeServerNode(node: ServerNode) = AppSettings.removeServerNode(context, node)

    /** 更新服务器节点 */
    suspend fun updateServerNode(oldNode: ServerNode, newNode: ServerNode) =
        AppSettings.updateServerNode(context, oldNode, newNode)

    /** 设置当前服务器节点 */
    suspend fun setCurrentServerNode(node: ServerNode) = AppSettings.setCurrentServerNode(context, node)

    // === 默认屏宽高/DPI（prefDefault*，按节点隔离）===

    /** 监听默认屏宽 Flow（当前节点） */
    fun prefDefaultWidthFlow(): Flow<String> = AppSettings.prefDefaultWidthFlow(context)

    /** 监听默认屏高 Flow（当前节点） */
    fun prefDefaultHeightFlow(): Flow<String> = AppSettings.prefDefaultHeightFlow(context)

    /** 监听默认 DPI Flow（当前节点） */
    fun prefDefaultDpiFlow(): Flow<String> = AppSettings.prefDefaultDpiFlow(context)

    /** 获取默认屏宽高/DPI（String 形式的 EditText 原始值，当前节点） */
    suspend fun getPrefDefaults(): Triple<String, String, String> =
        AppSettings.getPrefDefaults(context)

    /** 获取指定节点的默认屏宽高/DPI */
    suspend fun getPrefDefaults(node: ServerNode): Triple<String, String, String> =
        AppSettings.getPrefDefaults(context, node)

    /** 设置默认屏宽（当前节点） */
    suspend fun setPrefDefaultWidth(value: String) = AppSettings.setPrefDefaultWidth(context, value)

    /** 设置默认屏高（当前节点） */
    suspend fun setPrefDefaultHeight(value: String) = AppSettings.setPrefDefaultHeight(context, value)

    /** 设置默认 DPI（当前节点） */
    suspend fun setPrefDefaultDpi(value: String) = AppSettings.setPrefDefaultDpi(context, value)

    // === 显示器 Flags 配置（按节点隔离）===

    /** 监听指定 flagKey 的开关状态 Flow（当前节点） */
    fun flagFlow(key: String, defaultValue: Boolean): Flow<Boolean> =
        AppSettings.flagFlow(context, key, defaultValue)

    /** 读取所有 DisplayFlag 的开关状态 Map<flagKey, enabled>（当前节点） */
    suspend fun getFlags(): Map<String, Boolean> = AppSettings.getFlags(context)

    /** 设置 DisplayFlag 开关状态（当前节点），自动屏蔽 key 转换 */
    suspend fun setFlag(flag: DisplayFlag, enabled: Boolean) =
        AppSettings.setFlag(context, flag.key, enabled)

    /** 按 key 设置 DisplayFlag 开关状态（当前节点） */
    suspend fun setFlag(key: String, enabled: Boolean) = AppSettings.setFlag(context, key, enabled)

    /** 重置所有 DisplayFlag 到默认值（当前节点） */
    suspend fun resetAllFlagsToDefault() = AppSettings.resetAllFlagsToDefault(context)

    // === 最近使用 App 列表（按节点隔离）===

    /** 获取最近使用 App 列表（当前节点） */
    suspend fun getRecentApps(): List<String> = AppSettings.recentApps(context)

    /** 监听最近使用 App 列表 Flow（当前节点） */
    fun recentAppsFlow(): Flow<List<String>> = AppSettings.recentAppsFlow(context)

    /** 添加最近使用 App（当前节点） */
    suspend fun addRecentApp(packageName: String, maxLimit: Int = 10) =
        AppSettings.addRecentApp(context, packageName, maxLimit)

    // === 各 ServerNode 的显示器列表持久化（按节点隔离）===

    /** 获取指定节点的显示器列表 */
    suspend fun getDisplaysForServer(node: ServerNode): List<SavedDisplay> =
        AppSettings.getDisplaysForServer(context, node)

    /** 覆盖保存指定节点的显示器列表 */
    suspend fun setDisplaysForServer(node: ServerNode, displays: List<SavedDisplay>) =
        AppSettings.setDisplaysForServer(context, node, displays)

    /** 新增/覆盖指定节点的单个显示器 */
    suspend fun saveDisplayForServer(node: ServerNode, display: SavedDisplay) =
        AppSettings.saveDisplayForServer(context, node, display)

    /** 从指定节点移除显示器 */
    suspend fun removeDisplayForServer(node: ServerNode, displayId: Int) =
        AppSettings.removeDisplayForServer(context, node, displayId)
}
