package com.ynk.virtualdisplay.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.util.NetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 全局配置存储的底层访问点：持有 DataStore 实例、进程级写入 scope、
 * 全部持久化 key、以及热点配置的内存缓存（StateFlow）。
 *
 * 各职责化配置单元（[DaemonConnectionPrefs] / [ServerNodePrefs] /
 * [DisplayQualityPrefs] / [DisplayFlagPrefs] / [RecentAppsPrefs] /
 * [DisplayPersistencePrefs] / [FeatureTogglePrefs]）统一通过 [store] 访问数据，
 * 并通过 [stringKey] / [boolKey] 生成按节点隔离的 key。
 */
internal object AppDataStore {

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

    /** 获取某 Context 对应的 DataStore 实例。 */
    fun store(context: Context): DataStore<Preferences> = context.applicationContext.dataStore

    // ----- 持久化 key -----

    val serverPortKey = intPreferencesKey("server_port")
    val serverHostKey = stringPreferencesKey("server_host")
    val serverPasswordKey = stringPreferencesKey("server_password")
    val showPerformanceStatsKey = booleanPreferencesKey("show_performance_stats")
    val captureBackKey = booleanPreferencesKey("capture_back")
    val ultraLowLatencyKey = booleanPreferencesKey("ultra_low_latency")
    val moveTasksOnDestroyKey = booleanPreferencesKey("move_tasks_on_destroy")
    val serverNodesKey = stringPreferencesKey("server_nodes")
    val currentServerNodeKey = stringPreferencesKey("current_server_node")
    val privilegeModeKey = stringPreferencesKey("privilege_mode")

    /** 按节点隔离的字符串配置 key（如 `pref_default_width_127_0_0_1_27183`）。 */
    fun stringKey(prefix: String, node: ServerNode): Preferences.Key<String> =
        stringPreferencesKey("${prefix}_${node.uniqueKey()}")

    /** 按节点隔离的布尔配置 key。 */
    fun boolKey(prefix: String, node: ServerNode): Preferences.Key<Boolean> =
        booleanPreferencesKey("${prefix}_${node.uniqueKey()}")

    // ----- 热点配置内存缓存 -----

    private val _captureBackCache = MutableStateFlow(false)
    val captureBackCache: StateFlow<Boolean> = _captureBackCache

    private val _showPerformanceStatsCache = MutableStateFlow(true)
    val showPerformanceStatsCache: StateFlow<Boolean> = _showPerformanceStatsCache

    private val _privilegeModeCache = MutableStateFlow(PrivilegeMode.SHIZUKU)
    val privilegeModeCache: StateFlow<PrivilegeMode> = _privilegeModeCache

    private val _currentServerNodeCache = MutableStateFlow(defaultLocalNode())
    val currentServerNodeCache: StateFlow<ServerNode> = _currentServerNodeCache

    private val _ultraLowLatencyCache = MutableStateFlow(false)
    val ultraLowLatencyCache: StateFlow<Boolean> = _ultraLowLatencyCache

    private val _moveTasksOnDestroyCache = MutableStateFlow(true)
    val moveTasksOnDestroyCache: StateFlow<Boolean> = _moveTasksOnDestroyCache

    @Volatile
    private var initialized = false

    /** 默认「本机」节点。 */
    fun defaultLocalNode(): ServerNode = ServerNode("本机", NetUtils.LOCAL_HOST, 27183, "")

    /**
     * 初始化设置缓存：订阅 DataStore 将热点配置同步到内存 StateFlow，
     * 供同步读取（*Sync 系列）使用。可重复调用，仅首次生效。
     * @param context 任意 Context（内部会取 applicationContext）
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val target = store(context)
        scope.launch {
            target.data.collect { prefs ->
                _captureBackCache.value = prefs[captureBackKey] ?: false
                _showPerformanceStatsCache.value = prefs[showPerformanceStatsKey] ?: true
                _ultraLowLatencyCache.value = prefs[ultraLowLatencyKey] ?: false
                _moveTasksOnDestroyCache.value = prefs[moveTasksOnDestroyKey] ?: true

                val modeStr = prefs[privilegeModeKey] ?: PrivilegeMode.SHIZUKU.name
                _privilegeModeCache.value = parsePrivilegeMode(modeStr)

                // 当前节点仅来自持久化的节点身份，不与监听配置（serverHost/serverPort）联动：
                // 连接参数在建立连接时读取一次（快照），连接存续期间修改监听配置不影响当前节点。
                val nodeStr = prefs[currentServerNodeKey] ?: ""
                val savedNode = ServerNode.fromSerializedString(nodeStr)
                _currentServerNodeCache.value = savedNode ?: defaultLocalNode()
            }
        }
    }

    /** 解析特权模式字符串，非法值回退 [PrivilegeMode.SHIZUKU]。 */
    fun parsePrivilegeMode(raw: String): PrivilegeMode =
        try {
            PrivilegeMode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            PrivilegeMode.SHIZUKU
        }

    /** 同步读取当前特权模式（需先调用 [init]）。 */
    fun getPrivilegeModeSync(): PrivilegeMode = _privilegeModeCache.value

    /** 同步读取当前选中的服务器节点（需先调用 [init]）。 */
    fun getCurrentServerNodeSync(): ServerNode = _currentServerNodeCache.value

    /** 同步读取「超低延迟模式」配置（需先调用 [init]）。 */
    fun getUltraLowLatencySync(): Boolean = _ultraLowLatencyCache.value

    /** 同步读取「销毁虚拟显示器后是否将应用移回主屏」配置（需先调用 [init]，默认 true）。 */
    fun getMoveTasksOnDestroySync(): Boolean = _moveTasksOnDestroyCache.value
}
