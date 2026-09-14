package com.ynk.virtualdisplay.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.ynk.virtualdisplay.data.PrivilegeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 全局特性开关与特权模式配置。
 */
internal object FeatureTogglePrefs {

    /** 是否在显示器上叠加性能统计信息（帧率/延迟）的配置流（默认 true）。 */
    fun showPerformanceStatsFlow(context: Context): Flow<Boolean> =
        AppDataStore.store(context).data.map { it[AppDataStore.showPerformanceStatsKey] ?: true }

    /** 读取「显示性能统计」配置（默认 true）。 */
    suspend fun getShowPerformanceStats(context: Context): Boolean =
        AppDataStore.store(context).data.first()[AppDataStore.showPerformanceStatsKey] ?: true

    /** 持久化「显示性能统计」配置。 */
    suspend fun setShowPerformanceStats(context: Context, show: Boolean) {
        AppDataStore.store(context).edit { it[AppDataStore.showPerformanceStatsKey] = show }
    }

    /** 是否在虚拟显示器上捕获/转发 Back 键的配置流（默认 false）。 */
    fun captureBackFlow(context: Context): Flow<Boolean> =
        AppDataStore.store(context).data.map { it[AppDataStore.captureBackKey] ?: false }

    /** 持久化「捕获 Back 键」配置。 */
    suspend fun setCaptureBack(context: Context, enabled: Boolean) {
        AppDataStore.store(context).edit { it[AppDataStore.captureBackKey] = enabled }
    }

    /** 特权模式配置流（默认 SHIZUKU，解析失败回退 SHIZUKU）。 */
    fun privilegeModeFlow(context: Context): Flow<PrivilegeMode> =
        AppDataStore.store(context).data.map { prefs ->
            AppDataStore.parsePrivilegeMode(prefs[AppDataStore.privilegeModeKey] ?: PrivilegeMode.SHIZUKU.name)
        }

    /** 持久化特权模式。 */
    suspend fun setPrivilegeMode(context: Context, mode: PrivilegeMode) {
        AppDataStore.store(context).edit { it[AppDataStore.privilegeModeKey] = mode.name }
    }

    /** 是否启用超低延迟模式的配置流（默认 false）。 */
    fun ultraLowLatencyFlow(context: Context): Flow<Boolean> =
        AppDataStore.store(context).data.map { it[AppDataStore.ultraLowLatencyKey] ?: false }

    /** 持久化「超低延迟模式」配置。 */
    suspend fun setUltraLowLatency(context: Context, enabled: Boolean) {
        AppDataStore.store(context).edit { it[AppDataStore.ultraLowLatencyKey] = enabled }
    }

    /** 销毁虚拟显示器后是否将应用移回主屏的配置流（默认 true = 前台移回）。 */
    fun moveTasksOnDestroyFlow(context: Context): Flow<Boolean> =
        AppDataStore.store(context).data.map { it[AppDataStore.moveTasksOnDestroyKey] ?: true }

    /** 持久化「销毁虚拟显示器后是否将应用移回主屏」配置。 */
    suspend fun setMoveTasksOnDestroy(context: Context, enabled: Boolean) {
        AppDataStore.store(context).edit { it[AppDataStore.moveTasksOnDestroyKey] = enabled }
    }
}
