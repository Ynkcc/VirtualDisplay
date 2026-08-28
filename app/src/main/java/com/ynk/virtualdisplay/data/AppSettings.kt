package com.ynk.virtualdisplay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import com.ynk.virtualdisplay.util.NetUtils
import com.ynk.virtualdisplay.data.model.SavedDisplay

/**
 * 特权模式：决定如何获得 root 权限以拉起本机 daemon。
 * - [SHIZUKU]：通过 Shizuku 服务授权；
 * - [ROOT]：通过 su 二进制；
 * - [NONE]：无特权，仅用于远程连接（不拉 daemon）。
 */
enum class PrivilegeMode {
    SHIZUKU,
    ROOT,
    NONE
}

/**
 * 一台可连接的 scrcpy daemon 服务器节点。
 *
 * @property name 节点显示名（"本机" 为特殊节点名，用于识别 local 节点）
 * @property host 主机地址（"本机" 节点实际连接地址来自全局 serverHost 配置）
 * @property port 服务端口
 * @property password 连接密码，可为空
 */
data class ServerNode(
    val name: String,
    val host: String,
    val port: Int,
    val password: String = ""
) {
    /** 是否为本机节点（本机节点可启动/停止守护进程） */
    val isLocal: Boolean get() = host == NetUtils.LOCAL_HOST || host == "localhost" || host == NetUtils.ANY_HOST

    /**
     * 生成节点唯一标识（host 中的 "." 替换为 "_" 并拼接端口），用作 per-node 数据隔离的 key 后缀。
     * @return 形如 `host_port` 的唯一 key
     */
    fun uniqueKey(): String {
        return "${host.replace(".", "_")}_$port"
    }

    /** 序列化为 `name|host|port|password` 字符串，用于持久化存储。 */
    fun toSerializedString(): String {
        return "$name|$host|$port|$password"
    }

    companion object {
        /**
         * 从 [toSerializedString] 的结果反序列化节点。
         * @param str 序列化字符串
         * @return 反序列化成功返回节点；字段不足或端口非法时返回 null
         */
        fun fromSerializedString(str: String): ServerNode? {
            val parts = str.split("|")
            if (parts.size < 3) return null
            val name = parts[0]
            val host = parts[1]
            val port = parts[2].toIntOrNull() ?: 27183
            val password = if (parts.size > 3) parts[3] else ""
            return ServerNode(name, host, port, password)
        }
    }
}

/**
 * 全局应用设置（DataStore）门面：集中读写所有持久化配置，并以内存缓存（StateFlow）
 * 形式提供热点配置的同步读取。所有配置项均通过 DataStore 持久化。
 */
object AppSettings {

    private const val PREFS_NAME = "virtual_display_settings"

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = PREFS_NAME,
        produceMigrations = { context ->
            listOf(
                SharedPreferencesMigration(
                    context,
                    PREFS_NAME
                )
            )
        }
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val serverPortKey = intPreferencesKey("server_port")
    val serverHostKey = stringPreferencesKey("server_host")
    val serverPasswordKey = stringPreferencesKey("server_password")
    val showPerformanceStatsKey = booleanPreferencesKey("show_performance_stats")
    val captureBackKey = booleanPreferencesKey("capture_back")
    val recentAppsKey = stringPreferencesKey("recent_apps")
    val prefDefaultWidthKey = stringPreferencesKey("pref_default_width")
    val prefDefaultHeightKey = stringPreferencesKey("pref_default_height")
    val prefDefaultDpiKey = stringPreferencesKey("pref_default_dpi")
    
    val serverNodesKey = stringPreferencesKey("server_nodes")
    val currentServerNodeKey = stringPreferencesKey("current_server_node")
    val privilegeModeKey = stringPreferencesKey("privilege_mode")
    val autoStartServerKey = booleanPreferencesKey("auto_start_server")
    val ultraLowLatencyKey = booleanPreferencesKey("ultra_low_latency")
    val moveTasksOnDestroyKey = booleanPreferencesKey("move_tasks_on_destroy")

    private val _captureBackCache = MutableStateFlow(false)
    val captureBackCache: StateFlow<Boolean> = _captureBackCache

    private val _showPerformanceStatsCache = MutableStateFlow(true)
    val showPerformanceStatsCache: StateFlow<Boolean> = _showPerformanceStatsCache

    private val _privilegeModeCache = MutableStateFlow(PrivilegeMode.SHIZUKU)
    val privilegeModeCache: StateFlow<PrivilegeMode> = _privilegeModeCache

    private val _currentServerNodeCache = MutableStateFlow(ServerNode("本机", NetUtils.LOCAL_HOST, 27183, ""))
    val currentServerNodeCache: StateFlow<ServerNode> = _currentServerNodeCache

    private val _autoStartServerCache = MutableStateFlow(true)
    val autoStartServerCache: StateFlow<Boolean> = _autoStartServerCache

    private val _ultraLowLatencyCache = MutableStateFlow(false)
    val ultraLowLatencyCache: StateFlow<Boolean> = _ultraLowLatencyCache

    private val _moveTasksOnDestroyCache = MutableStateFlow(true)
    val moveTasksOnDestroyCache: StateFlow<Boolean> = _moveTasksOnDestroyCache

    @Volatile
    private var initialized = false

    /**
     * 初始化设置缓存：订阅 DataStore 将热点配置同步到内存 StateFlow，
     * 供同步读取（*Sync 系列）使用。可重复调用，仅首次生效。
     * @param context 任意 Context（内部会取 applicationContext）
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        scope.launch {
            appContext.dataStore.data.collect { prefs ->
                _captureBackCache.value = prefs[captureBackKey] ?: false
                _showPerformanceStatsCache.value = prefs[showPerformanceStatsKey] ?: true
                _autoStartServerCache.value = prefs[autoStartServerKey] ?: true
                _ultraLowLatencyCache.value = prefs[ultraLowLatencyKey] ?: false
                _moveTasksOnDestroyCache.value = prefs[moveTasksOnDestroyKey] ?: true

                val modeStr = prefs[privilegeModeKey] ?: PrivilegeMode.SHIZUKU.name
                _privilegeModeCache.value = try {
                    PrivilegeMode.valueOf(modeStr)
                } catch (e: Exception) {
                    PrivilegeMode.SHIZUKU
                }

                // currentServerNode 仅用于 per-node 数据隔离（flags、显示器存储等）
                // 实际连接地址/端口如果为“本机”，则从用户配置项（serverHostKey/serverPortKey）读取
                val host = prefs[serverHostKey] ?: NetUtils.LOCAL_HOST
                val port = prefs[serverPortKey] ?: 27183
                val pwd = prefs[serverPasswordKey] ?: ""

                val nodeStr = prefs[currentServerNodeKey] ?: ""
                val savedNode = ServerNode.fromSerializedString(nodeStr)
                _currentServerNodeCache.value = if (savedNode == null || savedNode.name == "本机") {
                    ServerNode("本机", host, port, pwd)
                } else {
                    savedNode
                }
            }
        }
    }

    /** 监听端口流（默认 27183）。 */
    fun serverPortFlow(context: Context): Flow<Int> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[serverPortKey] ?: 27183
        }
    }

    /** 读取当前监听端口（默认 27183）。 */
    suspend fun getServerPort(context: Context): Int {
        return context.applicationContext.dataStore.data.first()[serverPortKey] ?: 27183
    }

    /** 持久化监听端口。 */
    suspend fun setServerPort(context: Context, port: Int) {
        context.applicationContext.dataStore.edit { it[serverPortKey] = port }
    }

    /** 监听地址流（默认本机回环地址）。 */
    fun serverHostFlow(context: Context): Flow<String> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[serverHostKey] ?: NetUtils.LOCAL_HOST
        }
    }

    /** 读取当前监听地址（默认本机回环地址）。 */
    suspend fun getServerHost(context: Context): String {
        return context.applicationContext.dataStore.data.first()[serverHostKey] ?: NetUtils.LOCAL_HOST
    }

    /** 持久化监听地址。 */
    suspend fun setServerHost(context: Context, host: String) {
        context.applicationContext.dataStore.edit { it[serverHostKey] = host }
    }

    /** 连接密码流（默认为空）。 */
    fun serverPasswordFlow(context: Context): Flow<String> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[serverPasswordKey] ?: ""
        }
    }

    /** 读取当前连接密码（默认为空）。 */
    suspend fun getServerPassword(context: Context): String {
        return context.applicationContext.dataStore.data.first()[serverPasswordKey] ?: ""
    }

    /** 持久化连接密码。 */
    suspend fun setServerPassword(context: Context, password: String) {
        context.applicationContext.dataStore.edit { it[serverPasswordKey] = password }
    }

    /** 是否在显示器上叠加性能统计信息（帧率/延迟）的配置流（默认 true）。 */
    fun showPerformanceStatsFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[showPerformanceStatsKey] ?: true
        }
    }

    /** 读取「显示性能统计」配置（默认 true）。 */
    suspend fun getShowPerformanceStats(context: Context): Boolean {
        return context.applicationContext.dataStore.data.first()[showPerformanceStatsKey] ?: true
    }

    /** 持久化「显示性能统计」配置。 */
    suspend fun setShowPerformanceStats(context: Context, show: Boolean) {
        context.applicationContext.dataStore.edit { it[showPerformanceStatsKey] = show }
    }

    /** 是否在虚拟显示器上捕获/转发 Back 键的配置流（默认 false）。 */
    fun captureBackFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[captureBackKey] ?: false
        }
    }

    /** 持久化「捕获 Back 键」配置。 */
    suspend fun setCaptureBack(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[captureBackKey] = enabled }
    }

    /** 指定节点默认宽度配置流（未设置时为空串）。 */
    fun prefDefaultWidthFlow(context: Context, node: ServerNode): Flow<String> {
        val key = stringPreferencesKey("pref_default_width_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[key] ?: ""
        }
    }

    /** 当前节点默认宽度配置流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultWidthFlow(context: Context): Flow<String> {
        return _currentServerNodeCache.flatMapLatest { node ->
            prefDefaultWidthFlow(context, node)
        }
    }

    /** 指定节点默认高度配置流（未设置时为空串）。 */
    fun prefDefaultHeightFlow(context: Context, node: ServerNode): Flow<String> {
        val key = stringPreferencesKey("pref_default_height_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[key] ?: ""
        }
    }

    /** 当前节点默认高度配置流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultHeightFlow(context: Context): Flow<String> {
        return _currentServerNodeCache.flatMapLatest { node ->
            prefDefaultHeightFlow(context, node)
        }
    }

    /** 指定节点默认 DPI 配置流（未设置时为空串）。 */
    fun prefDefaultDpiFlow(context: Context, node: ServerNode): Flow<String> {
        val key = stringPreferencesKey("pref_default_dpi_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[key] ?: ""
        }
    }

    /** 当前节点默认 DPI 配置流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultDpiFlow(context: Context): Flow<String> {
        return _currentServerNodeCache.flatMapLatest { node ->
            prefDefaultDpiFlow(context, node)
        }
    }

    /**
     * 读取指定节点的默认画质参数。
     * @return Triple(width, height, dpi)，未设置时对应元素为空串
     */
    suspend fun getPrefDefaults(context: Context, node: ServerNode): Triple<String, String, String> {
        val widthKey = stringPreferencesKey("pref_default_width_${node.uniqueKey()}")
        val heightKey = stringPreferencesKey("pref_default_height_${node.uniqueKey()}")
        val dpiKey = stringPreferencesKey("pref_default_dpi_${node.uniqueKey()}")
        val prefs = context.applicationContext.dataStore.data.first()
        return Triple(
            prefs[widthKey] ?: "",
            prefs[heightKey] ?: "",
            prefs[dpiKey] ?: ""
        )
    }

    /** 读取当前节点的默认画质参数，参见 [getPrefDefaults]。 */
    suspend fun getPrefDefaults(context: Context): Triple<String, String, String> {
        val node = getCurrentServerNodeSync()
        return getPrefDefaults(context, node)
    }

    /** 持久化指定节点的默认宽度。 */
    suspend fun setPrefDefaultWidth(context: Context, node: ServerNode, value: String) {
        val key = stringPreferencesKey("pref_default_width_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { it[key] = value }
    }

    /** 持久化当前节点的默认宽度。 */
    suspend fun setPrefDefaultWidth(context: Context, value: String) {
        val node = getCurrentServerNodeSync()
        setPrefDefaultWidth(context, node, value)
    }

    /** 持久化指定节点的默认高度。 */
    suspend fun setPrefDefaultHeight(context: Context, node: ServerNode, value: String) {
        val key = stringPreferencesKey("pref_default_height_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { it[key] = value }
    }

    /** 持久化当前节点的默认高度。 */
    suspend fun setPrefDefaultHeight(context: Context, value: String) {
        val node = getCurrentServerNodeSync()
        setPrefDefaultHeight(context, node, value)
    }

    /** 持久化指定节点的默认 DPI。 */
    suspend fun setPrefDefaultDpi(context: Context, node: ServerNode, value: String) {
        val key = stringPreferencesKey("pref_default_dpi_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { it[key] = value }
    }

    /** 持久化当前节点的默认 DPI。 */
    suspend fun setPrefDefaultDpi(context: Context, value: String) {
        val node = getCurrentServerNodeSync()
        setPrefDefaultDpi(context, node, value)
    }

    /**
     * 指定节点某个开关 flag 的配置流。
     * @param key flag 标识（参见 [com.ynk.virtualdisplay.data.model.DisplayFlag.key]）
     * @param defaultValue 未设置时的默认值
     */
    fun flagFlow(context: Context, key: String, defaultValue: Boolean, node: ServerNode): Flow<Boolean> {
        val prefsKey = booleanPreferencesKey("${key}_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[prefsKey] ?: defaultValue
        }
    }

    /** 当前节点某个开关 flag 的配置流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun flagFlow(context: Context, key: String, defaultValue: Boolean): Flow<Boolean> {
        return _currentServerNodeCache.flatMapLatest { node ->
            flagFlow(context, key, defaultValue, node)
        }
    }

    /** 读取指定节点全部 flag 的当前值，key → 是否启用。 */
    suspend fun getFlags(context: Context, node: ServerNode): Map<String, Boolean> {
        val prefs = context.applicationContext.dataStore.data.first()
        return ALL_DISPLAY_FLAGS.associate { flag ->
            val key = booleanPreferencesKey("${flag.key}_${node.uniqueKey()}")
            flag.key to (prefs[key] ?: flag.isDefaultEnabled)
        }
    }

    /** 读取当前节点全部 flag 的当前值。 */
    suspend fun getFlags(context: Context): Map<String, Boolean> {
        val node = getCurrentServerNodeSync()
        return getFlags(context, node)
    }

    /** 持久化指定节点某个开关 flag。 */
    suspend fun setFlag(context: Context, node: ServerNode, key: String, enabled: Boolean) {
        val prefsKey = booleanPreferencesKey("${key}_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { it[prefsKey] = enabled }
    }

    /** 持久化当前节点某个开关 flag。 */
    suspend fun setFlag(context: Context, key: String, enabled: Boolean) {
        val node = getCurrentServerNodeSync()
        setFlag(context, node, key, enabled)
    }

    /** 将指定节点全部 flag 重置为默认值。 */
    suspend fun resetAllFlagsToDefault(context: Context, node: ServerNode) {
        context.applicationContext.dataStore.edit { prefs ->
            ALL_DISPLAY_FLAGS.forEach { flag ->
                val key = booleanPreferencesKey("${flag.key}_${node.uniqueKey()}")
                prefs[key] = flag.isDefaultEnabled
            }
        }
    }

    /** 将当前节点全部 flag 重置为默认值。 */
    suspend fun resetAllFlagsToDefault(context: Context) {
        val node = getCurrentServerNodeSync()
        resetAllFlagsToDefault(context, node)
    }

    /** 读取指定节点最近使用的应用包名列表（新→旧，去重）。 */
    suspend fun recentApps(context: Context, node: ServerNode): List<String> {
        val key = stringPreferencesKey("recent_apps_${node.uniqueKey()}")
        val raw = context.applicationContext.dataStore.data.first()[key] ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(",")
    }

    /** 读取当前节点最近使用的应用包名列表。 */
    suspend fun recentApps(context: Context): List<String> {
        val node = getCurrentServerNodeSync()
        return recentApps(context, node)
    }

    /**
     * 将应用加入指定节点的最近使用列表：置于首位并去重，超出 [maxLimit] 则截断。
     * @param packageName 应用包名
     * @param maxLimit 列表最大长度，默认 10
     */
    suspend fun addRecentApp(context: Context, node: ServerNode, packageName: String, maxLimit: Int = 10) {
        val key = stringPreferencesKey("recent_apps_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { prefs ->
            val current = prefs[key] ?: ""
            val list = if (current.isEmpty()) mutableListOf() else current.split(",").toMutableList()
            list.remove(packageName)
            list.add(0, packageName)
            val saved = if (list.size > maxLimit) list.take(maxLimit) else list
            prefs[key] = saved.joinToString(",")
        }
    }

    /** 将应用加入当前节点的最近使用列表，参见 [addRecentApp]。 */
    suspend fun addRecentApp(context: Context, packageName: String, maxLimit: Int = 10) {
        val node = getCurrentServerNodeSync()
        addRecentApp(context, node, packageName, maxLimit)
    }

    /** 指定节点最近使用应用列表流。 */
    fun recentAppsFlow(context: Context, node: ServerNode): Flow<List<String>> {
        val key = stringPreferencesKey("recent_apps_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            val raw = prefs[key] ?: ""
            if (raw.isEmpty()) emptyList() else raw.split(",")
        }
    }

    /** 当前节点最近使用应用列表流，跟随当前节点切换自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentAppsFlow(context: Context): Flow<List<String>> {
        return _currentServerNodeCache.flatMapLatest { node ->
            recentAppsFlow(context, node)
        }
    }

    /** 同步读取当前特权模式（需先调用 [init]）。 */
    fun getPrivilegeModeSync(): PrivilegeMode = _privilegeModeCache.value

    /** 同步读取当前选中的服务器节点（需先调用 [init]）。 */
    fun getCurrentServerNodeSync(): ServerNode = _currentServerNodeCache.value

    /** 服务器节点列表流；始终保证含有一个与本机全局网络配置同步的「本机」节点。 */
    fun serverNodesFlow(context: Context): Flow<List<ServerNode>> {
        return context.applicationContext.dataStore.data.map { prefs ->
            val host = prefs[serverHostKey] ?: NetUtils.LOCAL_HOST
            val port = prefs[serverPortKey] ?: 27183
            val pwd = prefs[serverPasswordKey] ?: ""
            
            val raw = prefs[serverNodesKey] ?: ""
            val list = if (raw.isEmpty()) {
                mutableListOf(ServerNode("本机", host, port, pwd))
            } else {
                raw.split(",").mapNotNull { ServerNode.fromSerializedString(it) }.toMutableList()
            }
            
            // 始终确保“本机”节点存在且与当前全局网络配置同步
            val localIdx = list.indexOfFirst { it.name == "本机" }
            if (localIdx >= 0) {
                list[localIdx] = list[localIdx].copy(host = host, port = port, password = pwd)
            } else {
                list.add(0, ServerNode("本机", host, port, pwd))
            }
            list
        }
    }

    /** 读取已保存的服务器节点列表，缺省时返回仅含「本机」的列表。 */
    suspend fun getServerNodes(context: Context): List<ServerNode> {
        val prefs = context.applicationContext.dataStore.data.first()
        val raw = prefs[serverNodesKey] ?: ""
        val list = if (raw.isEmpty()) {
            mutableListOf(ServerNode("本机", NetUtils.LOCAL_HOST, 27183, ""))
        } else {
            raw.split(",").mapNotNull { ServerNode.fromSerializedString(it) }.toMutableList()
        }

        val localIdx = list.indexOfFirst { it.name == "本机" }
        if (localIdx < 0) {
            list.add(0, ServerNode("本机", NetUtils.LOCAL_HOST, 27183, ""))
        }
        return list
    }

    /** 新增节点；若已存在同 host+port 的节点则先移除旧记录再追加。 */
    suspend fun addServerNode(context: Context, node: ServerNode) {
        val currentList = getServerNodes(context).toMutableList()
        currentList.removeAll { it.host == node.host && it.port == node.port }
        currentList.add(node)
        val serialized = currentList.joinToString(",") { it.toSerializedString() }
        context.applicationContext.dataStore.edit { it[serverNodesKey] = serialized }
    }

    /** 按 host+port 移除节点。 */
    suspend fun removeServerNode(context: Context, node: ServerNode) {
        val currentList = getServerNodes(context).toMutableList()
        currentList.removeAll { it.host == node.host && it.port == node.port }
        val serialized = currentList.joinToString(",") { it.toSerializedString() }
        context.applicationContext.dataStore.edit { it[serverNodesKey] = serialized }
    }

    /** 用 [newNode] 替换 [oldNode]；若旧节点不存在则直接追加新节点。 */
    suspend fun updateServerNode(context: Context, oldNode: ServerNode, newNode: ServerNode) {
        val currentList = getServerNodes(context).toMutableList()
        val index = currentList.indexOfFirst { it.host == oldNode.host && it.port == oldNode.port }
        if (index >= 0) {
            currentList[index] = newNode
        } else {
            currentList.removeAll { it.host == newNode.host && it.port == newNode.port }
            currentList.add(newNode)
        }
        val serialized = currentList.joinToString(",") { it.toSerializedString() }
        context.applicationContext.dataStore.edit { it[serverNodesKey] = serialized }
    }

    /** 持久化当前选中节点身份（仅写入节点身份，不覆写用户配置的监听地址/端口/密码）。 */
    suspend fun setCurrentServerNode(context: Context, node: ServerNode) {
        // 仅写入节点身份，不覆写用户配置的监听地址/端口/密码
        context.applicationContext.dataStore.edit { prefs ->
            prefs[currentServerNodeKey] = node.toSerializedString()
        }
    }

    /** 特权模式配置流（默认 SHIZUKU，解析失败回退 SHIZUKU）。 */
    fun privilegeModeFlow(context: Context): Flow<PrivilegeMode> {
        return context.applicationContext.dataStore.data.map { prefs ->
            val modeStr = prefs[privilegeModeKey] ?: PrivilegeMode.SHIZUKU.name
            try { PrivilegeMode.valueOf(modeStr) } catch (e: Exception) { PrivilegeMode.SHIZUKU }
        }
    }

    /** 持久化特权模式。 */
    suspend fun setPrivilegeMode(context: Context, mode: PrivilegeMode) {
        context.applicationContext.dataStore.edit { it[privilegeModeKey] = mode.name }
    }

    /** 是否随应用启动自动拉起本地 daemon 的配置流（默认 true）。 */
    fun autoStartServerFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[autoStartServerKey] ?: true
        }
    }

    /** 同步读取「自动拉起 daemon」配置（需先调用 [init]）。 */
    fun getAutoStartServerSync(): Boolean = _autoStartServerCache.value

    /** 持久化「自动拉起 daemon」配置。 */
    suspend fun setAutoStartServer(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[autoStartServerKey] = enabled }
    }

    /** 是否启用超低延迟模式的配置流（默认 false）。 */
    fun ultraLowLatencyFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[ultraLowLatencyKey] ?: false
        }
    }

    /** 同步读取「超低延迟模式」配置（需先调用 [init]）。 */
    fun getUltraLowLatencySync(): Boolean = _ultraLowLatencyCache.value

    /** 持久化「超低延迟模式」配置。 */
    suspend fun setUltraLowLatency(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[ultraLowLatencyKey] = enabled }
    }

    /** 销毁虚拟显示器后是否将应用移回主屏的配置流（默认 true = 前台移回）。 */
    fun moveTasksOnDestroyFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[moveTasksOnDestroyKey] ?: true
        }
    }

    /** 同步读取「销毁虚拟显示器后是否将应用移回主屏」配置（需先调用 [init]，默认 true）。 */
    fun getMoveTasksOnDestroySync(): Boolean = _moveTasksOnDestroyCache.value

    /** 持久化「销毁虚拟显示器后是否将应用移回主屏」配置。 */
    suspend fun setMoveTasksOnDestroy(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[moveTasksOnDestroyKey] = enabled }
    }

    /**
     * 读取指定节点保存的虚拟显示器列表。
     * @return 已保存的显示器列表；无数据或 JSON 解析失败时返回空列表
     */
    suspend fun getDisplaysForServer(context: Context, node: ServerNode): List<SavedDisplay> {
        val key = stringPreferencesKey("displays_${node.uniqueKey()}")
        val raw = context.applicationContext.dataStore.data.first()[key] ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<SavedDisplay>()
            for (i in 0 until array.length()) {
                list.add(SavedDisplay.fromJsonObject(array.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 保存/更新指定节点的一个虚拟显示器（同 id 覆盖旧记录）。 */
    suspend fun saveDisplayForServer(context: Context, node: ServerNode, display: SavedDisplay) {
        val key = stringPreferencesKey("displays_${node.uniqueKey()}")
        val current = getDisplaysForServer(context, node).toMutableList()
        current.removeAll { it.id == display.id }
        current.add(display)
        val array = org.json.JSONArray()
        current.forEach { array.put(it.toJsonObject()) }
        context.applicationContext.dataStore.edit { prefs ->
            prefs[key] = array.toString()
        }
    }

    /** 按 id 移除指定节点保存的虚拟显示器。 */
    suspend fun removeDisplayForServer(context: Context, node: ServerNode, displayId: Int) {
        val key = stringPreferencesKey("displays_${node.uniqueKey()}")
        val current = getDisplaysForServer(context, node).toMutableList()
        current.removeAll { it.id == displayId }
        val array = org.json.JSONArray()
        current.forEach { array.put(it.toJsonObject()) }
        context.applicationContext.dataStore.edit { prefs ->
            prefs[key] = array.toString()
        }
    }

    /** 整体覆写指定节点保存的虚拟显示器列表。 */
    suspend fun setDisplaysForServer(context: Context, node: ServerNode, displays: List<SavedDisplay>) {
        val key = stringPreferencesKey("displays_${node.uniqueKey()}")
        val array = org.json.JSONArray()
        displays.forEach { array.put(it.toJsonObject()) }
        context.applicationContext.dataStore.edit { prefs ->
            prefs[key] = array.toString()
        }
    }
}
