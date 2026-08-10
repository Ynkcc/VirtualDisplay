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
import kotlinx.coroutines.launch

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

    private val _captureBackCache = MutableStateFlow(false)
    val captureBackCache: StateFlow<Boolean> = _captureBackCache

    private val _showPerformanceStatsCache = MutableStateFlow(true)
    val showPerformanceStatsCache: StateFlow<Boolean> = _showPerformanceStatsCache

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
            prefs[serverHostKey] ?: "127.0.0.1"
        }
    }

    suspend fun getServerHost(context: Context): String {
        return context.applicationContext.dataStore.data.first()[serverHostKey] ?: "127.0.0.1"
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

    fun prefDefaultWidthFlow(context: Context): Flow<String> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[prefDefaultWidthKey] ?: ""
        }
    }

    fun prefDefaultHeightFlow(context: Context): Flow<String> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[prefDefaultHeightKey] ?: ""
        }
    }

    fun prefDefaultDpiFlow(context: Context): Flow<String> {
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[prefDefaultDpiKey] ?: ""
        }
    }

    suspend fun getPrefDefaults(context: Context): Triple<String, String, String> {
        val prefs = context.applicationContext.dataStore.data.first()
        return Triple(
            prefs[prefDefaultWidthKey] ?: "",
            prefs[prefDefaultHeightKey] ?: "",
            prefs[prefDefaultDpiKey] ?: ""
        )
    }

    suspend fun setPrefDefaultWidth(context: Context, value: String) {
        context.applicationContext.dataStore.edit { it[prefDefaultWidthKey] = value }
    }

    suspend fun setPrefDefaultHeight(context: Context, value: String) {
        context.applicationContext.dataStore.edit { it[prefDefaultHeightKey] = value }
    }

    suspend fun setPrefDefaultDpi(context: Context, value: String) {
        context.applicationContext.dataStore.edit { it[prefDefaultDpiKey] = value }
    }

    fun flagFlow(context: Context, key: String, defaultValue: Boolean): Flow<Boolean> {
        val prefsKey = booleanPreferencesKey(key)
        return context.applicationContext.dataStore.data.map { prefs ->
            prefs[prefsKey] ?: defaultValue
        }
    }

    suspend fun getFlags(context: Context): Map<String, Boolean> {
        val prefs = context.applicationContext.dataStore.data.first()
        return ALL_DISPLAY_FLAGS.associate { flag ->
            flag.key to (prefs[booleanPreferencesKey(flag.key)] ?: flag.isDefaultEnabled)
        }
    }

    suspend fun setFlag(context: Context, key: String, enabled: Boolean) {
        context.applicationContext.dataStore.edit { it[booleanPreferencesKey(key)] = enabled }
    }

    suspend fun resetAllFlagsToDefault(context: Context) {
        context.applicationContext.dataStore.edit { prefs ->
            ALL_DISPLAY_FLAGS.forEach { flag ->
                prefs[booleanPreferencesKey(flag.key)] = flag.isDefaultEnabled
            }
        }
    }

    suspend fun recentApps(context: Context): List<String> {
        val raw = context.applicationContext.dataStore.data.first()[recentAppsKey] ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(",")
    }

    suspend fun addRecentApp(context: Context, packageName: String, maxLimit: Int = 10) {
        context.applicationContext.dataStore.edit { prefs ->
            val current = prefs[recentAppsKey] ?: ""
            val list = if (current.isEmpty()) mutableListOf() else current.split(",").toMutableList()
            list.remove(packageName)
            list.add(0, packageName)
            val saved = if (list.size > maxLimit) list.take(maxLimit) else list
            prefs[recentAppsKey] = saved.joinToString(",")
        }
    }

    fun recentAppsFlow(context: Context): Flow<List<String>> {
        return context.applicationContext.dataStore.data.map { prefs ->
            val raw = prefs[recentAppsKey] ?: ""
            if (raw.isEmpty()) emptyList() else raw.split(",")
        }
    }
}
