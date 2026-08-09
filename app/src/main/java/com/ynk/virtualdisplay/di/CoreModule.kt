package com.ynk.virtualdisplay.di

import com.ynk.virtualdisplay.manager.DisplayMetricsManager
import com.ynk.virtualdisplay.manager.PermissionManager
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.ui.screens.PermissionsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 核心前置依赖模块：在 Application.onCreate 冷启动时立即加载。
 *
 * 仅包含 Shizuku 权限检查、运行时权限检查、显示信息查询等最小依赖集合。
 * 不触碰 Daemon 进程、RPC 传输层、VideoStreamController 等重型组件，
 * 也不依赖 Shizuku 授权完成（ShizukuManager 仅检测状态，不发起连接）。
 *
 * 启动时机：[com.ynk.virtualdisplay.MyApplication.onCreate] → startKoin(coreModule)
 */
val coreModule = module {
    // Shizuku 管理器：检测 Shizuku 可用性与权限状态，全局单例
    single { ShizukuManager() }

    // 运行时权限管理器：检查存储/悬浮窗等权限
    single { PermissionManager(androidContext()) }

    // 屏幕信息管理器：分辨率/DPI/虚拟显示器列表查询
    single { DisplayMetricsManager(androidContext()) }

    // 权限页 ViewModel：仅依赖 coreModule 组件，Shizuku 授权前即可使用
    viewModel { PermissionsViewModel(get(), get()) }
}
