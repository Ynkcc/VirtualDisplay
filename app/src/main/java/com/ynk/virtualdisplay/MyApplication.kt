package com.ynk.virtualdisplay

import android.app.Application
import android.content.Context
import android.util.Log
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.di.appModule
import com.ynk.virtualdisplay.di.coreModule
import com.ynk.virtualdisplay.util.ExceptionUtils
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.loadKoinModules
import org.koin.core.context.startKoin

/**
 * VirtualDisplay 自定义应用入口。
 *
 * 采用两阶段启动模式（参考 GAMEHELPER）：
 *
 * 【第一阶段：Application.onCreate - 冷启动立即执行】
 *   1. 初始化全局异常处理器
 *   2. startKoin(coreModule)：加载 ShizukuManager / PermissionManager / DisplayMetricsManager
 *      这些组件不需要 Shizuku 授权或存储权限，仅用于权限检测和显示信息查询
 *
 * 【第二阶段：Shizuku 权限验证通过后 - 由 MainActivity 调用 bootstrapCore】
 *   1. AppSettings.init()：初始化 DataStore
 *   2. loadKoinModules(appModule)：增量加载 DaemonProcessController / Repository /
 *      DisplayInteractor / MainViewModel 等依赖 Shizuku 授权的重型组件
 *
 * 这种设计确保：
 * - 权限页（ShizukuPermissionScreen）冷启动即可工作，不需要等待 Daemon 初始化
 * - Daemon 进程只在 Shizuku 授权后才尝试启动，避免无效的资源消耗
 */
class MyApplication : Application() {
    companion object {
        private const val TAG = "MyApplication"
    }

    /** 核心组件（AppSettings + appModule）是否已完成 bootstrap */
    @Volatile
    var isCoreBootstrapped: Boolean = false
        private set

    /**
     * 冷启动，加载全部模块。
     */
    override fun onCreate() {
        super.onCreate()
        ExceptionUtils.setupGlobalCrashHandler()
        Log.i(TAG, "冷启动：初始化 AppSettings 并加载所有模块")

        AppSettings.init(this)

        startKoin {
            androidContext(this@MyApplication)
            modules(coreModule, appModule)
        }
        isCoreBootstrapped = true
    }

    /**
     * 兼容性保留，已在冷启动中完成。
     */
    @Synchronized
    fun bootstrapCore() {
        Log.i(TAG, "bootstrapCore 调用已在冷启动完成，跳过")
    }
}
