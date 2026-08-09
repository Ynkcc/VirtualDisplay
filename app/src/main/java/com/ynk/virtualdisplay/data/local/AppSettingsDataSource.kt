package com.ynk.virtualdisplay.data.local

import android.content.Context
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.model.DisplayFlag
import kotlinx.coroutines.flow.Flow

/**
 * 本地配置数据源：统一封装对 AppSettings（DataStore）的读写。
 *
 * 屏蔽底层存储实现细节（DataStore），供上层 Repository 调用时
 * 无需关心具体存储技术和 Key。
 *
 * 属于 appModule：依赖 AppSettings.init()，只有在 bootstrapCore 后才可用。
 *
 * 当前主要暴露 [DaemonDisplayRepository] 实际使用到的读写方法，
 * 后续可按业务需求逐步扩展（setter 方法等）。
 */
class AppSettingsDataSource(private val context: Context) {

    // === Daemon 连接配置（Repository.bindService 实际使用）===

    /** 获取 Daemon 服务监听端口（默认 27183） */
    suspend fun getServerPort(): Int = AppSettings.getServerPort(context)

    /** 获取 Daemon 绑定地址（默认 127.0.0.1） */
    suspend fun getServerHost(): String = AppSettings.getServerHost(context)

    // === 显示器 Flags 配置（DisplayInteractor.buildDefaultFlags 实际使用）===

    /** 读取所有 DisplayFlag 的开关状态 Map<flagKey, enabled> */
    suspend fun getFlags(): Map<String, Boolean> = AppSettings.getFlags(context)

    // === 默认屏宽高/DPI（String 形式的 EditText 原始值）===

    suspend fun getPrefDefaults(): Triple<String, String, String> =
        AppSettings.getPrefDefaults(context)

    // === 最近使用 App 列表 ===

    suspend fun getRecentApps(): List<String> = AppSettings.recentApps(context)

    fun recentAppsFlow(): Flow<List<String>> = AppSettings.recentAppsFlow(context)

    suspend fun addRecentApp(packageName: String, maxLimit: Int = 10) =
        AppSettings.addRecentApp(context, packageName, maxLimit)

    // === 捕获返回键配置 ===

    fun captureBackFlow(): Flow<Boolean> = AppSettings.captureBackFlow(context)

    /**
     * 在 DataSource 层屏蔽 DisplayFlag 到 key 的转换，
     * 调用方直接传 [DisplayFlag] 即可。
     */
    suspend fun setFlag(flag: DisplayFlag, enabled: Boolean) =
        AppSettings.setFlag(context, flag.key, enabled)
}
