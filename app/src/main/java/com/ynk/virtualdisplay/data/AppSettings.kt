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

enum class PrivilegeMode {
    SHIZUKU,
    ROOT,
    NONE
}

data class ServerNode(
    val name: String,
    val host: String,
    val port: Int,
    val password: String = ""
) {
    /** 是否为本机节点（可启动/停止守护进程） */
    val isLocal: Boolean get() = host == NetUtils.LOCAL_HOST || host == "localhost" || host == NetUtils.ANY_HOST

    fun uniqueKey(): String {
        return "${host.replace(".", "_")}_$port"
    }

    fun toSerializedString(): String {
        return "$name|$host|$port|$password"
    }

    companion object {
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

    @Volatile
    private var initialized = false

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

    fun serverPortFlow(context: Context): Flow<Int> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[serverPortKey] ?: 27183
        }
    }

    suspend fun getServerPort(context: Context): Int {
        return context.applicationContext.dataStore.data.first()[serverPortKey] ?: 27183
    }

    suspend fun setServerPort(context: Context, port: Int) {
        context.applicationContext.dataStore.edit { it[serverPortKey] = port }
    }

    fun serverHostFlow(context: Context): Flow<String> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[serverHostKey] ?: NetUtils.LOCAL_HOST
        }
    }

    suspend fun getServerHost(context: Context): String {
        return context.applicationContext.dataStore.data.first()[serverHostKey] ?: NetUtils.LOCAL_HOST
    }

    suspend fun setServerHost(context: Context, host: String) {
        context.applicationContext.dataStore.edit { it[serverHostKey] = host }
    }

    fun serverPasswordFlow(context: Context): Flow<String> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[serverPasswordKey] ?: ""
        }
    }

    suspend fun getServerPassword(context: Context): String {
        return context.applicationContext.dataStore.data.first()[serverPasswordKey] ?: ""
    }

    suspend fun setServerPassword(context: Context, password: String) {
        context.applicationContext.dataStore.edit { it[serverPasswordKey] = password }
    }

    fun showPerformanceStatsFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[showPerformanceStatsKey] ?: true
        }
    }

    suspend fun getShowPerformanceStats(context: Context): Boolean {
        return context.applicationContext.dataStore.data.first()[showPerformanceStatsKey] ?: true
    }

    suspend fun setShowPerformanceStats(context: Context, show: Boolean) {
        context.applicationContext.dataStore.edit { it[showPerformanceStatsKey] = show }
    }

    fun captureBackFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[captureBackKey] ?: false
        }
    }

    suspend fun setCaptureBack(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[captureBackKey] = enabled }
    }

    fun prefDefaultWidthFlow(context: Context, node: ServerNode): Flow<String> {
        val key = stringPreferencesKey("pref_default_width_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[key] ?: ""
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultWidthFlow(context: Context): Flow<String> {
        return _currentServerNodeCache.flatMapLatest { node ->
            prefDefaultWidthFlow(context, node)
        }
    }

    fun prefDefaultHeightFlow(context: Context, node: ServerNode): Flow<String> {
        val key = stringPreferencesKey("pref_default_height_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[key] ?: ""
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultHeightFlow(context: Context): Flow<String> {
        return _currentServerNodeCache.flatMapLatest { node ->
            prefDefaultHeightFlow(context, node)
        }
    }

    fun prefDefaultDpiFlow(context: Context, node: ServerNode): Flow<String> {
        val key = stringPreferencesKey("pref_default_dpi_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[key] ?: ""
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefDefaultDpiFlow(context: Context): Flow<String> {
        return _currentServerNodeCache.flatMapLatest { node ->
            prefDefaultDpiFlow(context, node)
        }
    }

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

    suspend fun getPrefDefaults(context: Context): Triple<String, String, String> {
        val node = getCurrentServerNodeSync()
        return getPrefDefaults(context, node)
    }

    suspend fun setPrefDefaultWidth(context: Context, node: ServerNode, value: String) {
        val key = stringPreferencesKey("pref_default_width_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { it[key] = value }
    }

    suspend fun setPrefDefaultWidth(context: Context, value: String) {
        val node = getCurrentServerNodeSync()
        setPrefDefaultWidth(context, node, value)
    }

    suspend fun setPrefDefaultHeight(context: Context, node: ServerNode, value: String) {
        val key = stringPreferencesKey("pref_default_height_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { it[key] = value }
    }

    suspend fun setPrefDefaultHeight(context: Context, value: String) {
        val node = getCurrentServerNodeSync()
        setPrefDefaultHeight(context, node, value)
    }

    suspend fun setPrefDefaultDpi(context: Context, node: ServerNode, value: String) {
        val key = stringPreferencesKey("pref_default_dpi_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { it[key] = value }
    }

    suspend fun setPrefDefaultDpi(context: Context, value: String) {
        val node = getCurrentServerNodeSync()
        setPrefDefaultDpi(context, node, value)
    }

    fun flagFlow(context: Context, key: String, defaultValue: Boolean, node: ServerNode): Flow<Boolean> {
        val prefsKey = booleanPreferencesKey("${key}_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[prefsKey] ?: defaultValue
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun flagFlow(context: Context, key: String, defaultValue: Boolean): Flow<Boolean> {
        return _currentServerNodeCache.flatMapLatest { node ->
            flagFlow(context, key, defaultValue, node)
        }
    }

    suspend fun getFlags(context: Context, node: ServerNode): Map<String, Boolean> {
        val prefs = context.applicationContext.dataStore.data.first()
        return ALL_DISPLAY_FLAGS.associate { flag ->
            val key = booleanPreferencesKey("${flag.key}_${node.uniqueKey()}")
            flag.key to (prefs[key] ?: flag.isDefaultEnabled)
        }
    }

    suspend fun getFlags(context: Context): Map<String, Boolean> {
        val node = getCurrentServerNodeSync()
        return getFlags(context, node)
    }

    suspend fun setFlag(context: Context, node: ServerNode, key: String, enabled: Boolean) {
        val prefsKey = booleanPreferencesKey("${key}_${node.uniqueKey()}")
        context.applicationContext.dataStore.edit { it[prefsKey] = enabled }
    }

    suspend fun setFlag(context: Context, key: String, enabled: Boolean) {
        val node = getCurrentServerNodeSync()
        setFlag(context, node, key, enabled)
    }

    suspend fun resetAllFlagsToDefault(context: Context, node: ServerNode) {
        context.applicationContext.dataStore.edit { prefs ->
            ALL_DISPLAY_FLAGS.forEach { flag ->
                val key = booleanPreferencesKey("${flag.key}_${node.uniqueKey()}")
                prefs[key] = flag.isDefaultEnabled
            }
        }
    }

    suspend fun resetAllFlagsToDefault(context: Context) {
        val node = getCurrentServerNodeSync()
        resetAllFlagsToDefault(context, node)
    }

    suspend fun recentApps(context: Context, node: ServerNode): List<String> {
        val key = stringPreferencesKey("recent_apps_${node.uniqueKey()}")
        val raw = context.applicationContext.dataStore.data.first()[key] ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(",")
    }

    suspend fun recentApps(context: Context): List<String> {
        val node = getCurrentServerNodeSync()
        return recentApps(context, node)
    }

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

    suspend fun addRecentApp(context: Context, packageName: String, maxLimit: Int = 10) {
        val node = getCurrentServerNodeSync()
        addRecentApp(context, node, packageName, maxLimit)
    }

    fun recentAppsFlow(context: Context, node: ServerNode): Flow<List<String>> {
        val key = stringPreferencesKey("recent_apps_${node.uniqueKey()}")
        return context.applicationContext.dataStore.data.map { prefs ->
            val raw = prefs[key] ?: ""
            if (raw.isEmpty()) emptyList() else raw.split(",")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentAppsFlow(context: Context): Flow<List<String>> {
        return _currentServerNodeCache.flatMapLatest { node ->
            recentAppsFlow(context, node)
        }
    }
    fun getPrivilegeModeSync(): PrivilegeMode = _privilegeModeCache.value

    fun getCurrentServerNodeSync(): ServerNode = _currentServerNodeCache.value

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

    suspend fun addServerNode(context: Context, node: ServerNode) {
        val currentList = getServerNodes(context).toMutableList()
        currentList.removeAll { it.host == node.host && it.port == node.port }
        currentList.add(node)
        val serialized = currentList.joinToString(",") { it.toSerializedString() }
        context.applicationContext.dataStore.edit { it[serverNodesKey] = serialized }
    }

    suspend fun removeServerNode(context: Context, node: ServerNode) {
        val currentList = getServerNodes(context).toMutableList()
        currentList.removeAll { it.host == node.host && it.port == node.port }
        val serialized = currentList.joinToString(",") { it.toSerializedString() }
        context.applicationContext.dataStore.edit { it[serverNodesKey] = serialized }
    }

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

    suspend fun setCurrentServerNode(context: Context, node: ServerNode) {
        // 仅写入节点身份，不覆写用户配置的监听地址/端口/密码
        context.applicationContext.dataStore.edit { prefs ->
            prefs[currentServerNodeKey] = node.toSerializedString()
        }
    }

    fun privilegeModeFlow(context: Context): Flow<PrivilegeMode> {
        return context.applicationContext.dataStore.data.map { prefs ->
            val modeStr = prefs[privilegeModeKey] ?: PrivilegeMode.SHIZUKU.name
            try { PrivilegeMode.valueOf(modeStr) } catch (e: Exception) { PrivilegeMode.SHIZUKU }
        }
    }

    suspend fun setPrivilegeMode(context: Context, mode: PrivilegeMode) {
        context.applicationContext.dataStore.edit { it[privilegeModeKey] = mode.name }
    }

    fun autoStartServerFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[autoStartServerKey] ?: true
        }
    }

    fun getAutoStartServerSync(): Boolean = _autoStartServerCache.value

    suspend fun setAutoStartServer(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[autoStartServerKey] = enabled }
    }

    fun ultraLowLatencyFlow(context: Context): Flow<Boolean> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[ultraLowLatencyKey] ?: false
        }
    }

    fun getUltraLowLatencySync(): Boolean = _ultraLowLatencyCache.value

    suspend fun setUltraLowLatency(context: Context, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[ultraLowLatencyKey] = enabled }
    }

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

    suspend fun setDisplaysForServer(context: Context, node: ServerNode, displays: List<SavedDisplay>) {
        val key = stringPreferencesKey("displays_${node.uniqueKey()}")
        val array = org.json.JSONArray()
        displays.forEach { array.put(it.toJsonObject()) }
        context.applicationContext.dataStore.edit { prefs ->
            prefs[key] = array.toString()
        }
    }
}
