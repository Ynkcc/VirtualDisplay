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
 * 采用单阶段启动模式：
 *
 * 【Application.onCreate - 冷启动一次性完成全部初始化】
 *   1. 初始化全局异常处理器（ExceptionUtils）
 *   2. AppSettings.init()：初始化 DataStore
 *   3. startKoin(coreModule, appModule)：一次性加载全部模块，包括
 *      ShizukuManager / PermissionManager / DisplayMetricsManager，以及
 *      DaemonProcessController / Repository / DisplayInteractor / MainViewModel 等
 *
 * 不再存在延迟加载：所有模块在冷启动时即全部就绪，isCoreBootstrapped 在
 * onCreate 末尾被置为 true，标识核心已初始化完成。
 *
 * 这种设计确保：
 * - 应用启动后所有模块立即可用，无需等待 Shizuku 授权或其他条件
 * - bootstrapCore() 仅作为兼容性保留的空操作，不再承担第二阶段加载职责
 */
class MyApplication : Application() {
    companion object {
        private const val TAG = "MyApplication"
    }

    /** 核心组件（AppSettings + 全部 Koin 模块）是否已初始化完成；在 onCreate 末尾被置为 true */
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
     * 兼容性保留的空操作。
     *
     * 冷启动（onCreate）已完成全部初始化，此处无需再做任何加载。
     * 保留该方法以兼容外部调用点（如 MainActivity），避免破坏既有调用方。
     */
    @Synchronized
    fun bootstrapCore() {
        Log.i(TAG, "bootstrapCore 调用已在冷启动完成，跳过")
    }
}
