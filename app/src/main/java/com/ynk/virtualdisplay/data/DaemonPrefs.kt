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

    val savedPortCache: StateFlow<Int> = _savedPortCache
    val savedPidCache: StateFlow<Int> = _savedPidCache

    init {
        scope.launch {
            context.applicationContext.daemonDataStore.data.collect { prefs ->
                _savedPortCache.value = prefs[SAVED_PORT_KEY] ?: -1
                _savedPidCache.value = prefs[SAVED_PID_KEY] ?: -1
            }
        }
    }

    fun getSavedPortSync(): Int = _savedPortCache.value

    fun getSavedPidSync(): Int = _savedPidCache.value

    fun getSavedPidForPort(port: Int): Int {
        return if (_savedPortCache.value == port) _savedPidCache.value else -1
    }

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
