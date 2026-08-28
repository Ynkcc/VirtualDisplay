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
 * VirtualDisplay 应用入口，采用单阶段冷启动。
 *
 * [onCreate] 中一次性完成全局异常处理器、DataStore 初始化，并
 * 通过 startKoin 加载全部模块（coreModule + appModule），启动后所有
 * 组件立即可用，无需等待 Shizuku 授权。所有模块就绪后置 [isCoreBootstrapped] 为 true；
 * [bootstrapCore] 仅为兼容旧调用点而保留的空操作。
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
