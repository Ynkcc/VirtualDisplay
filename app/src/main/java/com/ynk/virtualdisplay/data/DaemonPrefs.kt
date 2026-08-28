package com.ynk.virtualdisplay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 本机 daemon 进程运行时信息（端口/PID）的持久化与内存缓存。
 * 用于在应用重启后快速判断 daemon 是否仍在运行；构造时即从 DataStore
 * 加载到内存 StateFlow 并保持同步。
 * @param context 任意 Context（内部会取 applicationContext）
 */
class DaemonPrefs(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "client_daemon_prefs"
        private val SAVED_PORT_KEY = intPreferencesKey("saved_port")
        private val SAVED_PID_KEY = intPreferencesKey("saved_pid")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val Context.daemonDataStore: DataStore<Preferences> by preferencesDataStore(
        name = PREFS_NAME,
        produceMigrations = { ctx ->
            listOf(
                SharedPreferencesMigration(
                    ctx,
                    PREFS_NAME
                )
            )
        }
    )

    private val _savedPortCache = MutableStateFlow(-1)
    private val _savedPidCache = MutableStateFlow(-1)

    /** 上次启动 daemon 时使用的端口（-1 表示无记录）。 */
    val savedPortCache: StateFlow<Int> = _savedPortCache

    /** 上次启动 daemon 的进程 PID（-1 表示无记录）。 */
    val savedPidCache: StateFlow<Int> = _savedPidCache

    init {
        scope.launch {
            context.applicationContext.daemonDataStore.data.collect { prefs ->
                _savedPortCache.value = prefs[SAVED_PORT_KEY] ?: -1
                _savedPidCache.value = prefs[SAVED_PID_KEY] ?: -1
            }
        }
    }

    /** 同步读取上次启动 daemon 时使用的端口（-1 表示无记录）。 */
    fun getSavedPortSync(): Int = _savedPortCache.value

    /** 同步读取上次启动 daemon 的 PID（-1 表示无记录）。 */
    fun getSavedPidSync(): Int = _savedPidCache.value

    /**
     * 查询与指定端口匹配的 daemon PID。
     * @param port 目标端口
     * @return 端口匹配返回对应 PID，否则返回 -1
     */
    fun getSavedPidForPort(port: Int): Int {
        return if (_savedPortCache.value == port) _savedPidCache.value else -1
    }

    /** 保存 daemon 启动信息（端口 + PID），同时更新内存缓存并异步持久化。 */
    fun savePid(port: Int, pid: Int) {
        _savedPortCache.value = port
        _savedPidCache.value = pid
        scope.launch {
            context.applicationContext.daemonDataStore.edit { prefs ->
                prefs[SAVED_PORT_KEY] = port
                prefs[SAVED_PID_KEY] = pid
            }
        }
    }

    /** 清空 daemon 启动信息（内存缓存与持久化同步清除）。 */
    fun clearSavedPid() {
        _savedPortCache.value = -1
        _savedPidCache.value = -1
        scope.launch {
            context.applicationContext.daemonDataStore.edit { prefs ->
                prefs.remove(SAVED_PORT_KEY)
                prefs.remove(SAVED_PID_KEY)
            }
        }
    }
}
