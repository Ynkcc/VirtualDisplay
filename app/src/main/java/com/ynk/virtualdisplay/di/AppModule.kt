package com.ynk.virtualdisplay.di

import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.data.remote.DaemonRemoteDataSource
import com.ynk.virtualdisplay.data.repository.DaemonDisplayRepository
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.system.LauncherDataSource
import com.ynk.virtualdisplay.domain.DisplayInteractor
import com.ynk.virtualdisplay.manager.DisplayMetricsManager
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.process.DaemonProcessController
import com.ynk.virtualdisplay.rpc.DaemonControlApi
import com.ynk.virtualdisplay.rpc.DaemonControlApiImpl
import com.ynk.virtualdisplay.rpc.DaemonRpc
import com.ynk.virtualdisplay.ui.main.MainViewModel
import com.ynk.virtualdisplay.util.ExceptionUtils
import com.ynk.virtualdisplay.video.VideoStreamController
import com.ynk.virtualdisplay.video.VideoStreamRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 应用主依赖模块：Shizuku 权限验证通过后才加载。
 *
 * 包含所有需要 Shizuku 授权或 AppSettings 初始化后才能使用的重型组件：
 * - 核心基础设施层（DaemonProcessController / DaemonTransport / DaemonRpc / DaemonControlApi / VideoStreamController）
 * - 数据层（IDisplayRepository 实现）
 * - 领域层（DisplayInteractor）
 * - ViewModel 层（MainViewModel）
 *
 * 加载时机：[com.ynk.virtualdisplay.MyApplication.bootstrapCore] 中通过 loadKoinModules(appModule) 增量加载。
 * 此时 ShizukuManager 已由 coreModule 提供，不会重复创建。
 */
val appModule = module {
    // === 核心基础设施层 ===
    single { DaemonProcessController(androidContext()) }
    single { DaemonTransport() }
    single { DaemonRpc(get()) }
    // 先注册实现类，再分别绑定两个接口（都指向同一个单例实例）
    // DaemonControlApi：控制命令 API（供 RemoteDataSource 使用）
    // VideoStreamRpc：视频流 RPC（供 VideoStreamController 使用）
    single { DaemonControlApiImpl(get()) }
    single<DaemonControlApi> { get<DaemonControlApiImpl>() }
    single<VideoStreamRpc> { get<DaemonControlApiImpl>() }

    // VideoStreamController 使用的协程 Scope：主线程调度器 + SupervisorJob + 异常处理器
    // 注意：这是跨组件共享的单例 Scope，任何地方都不得对其调用 cancel()，
    // 否则会不可逆地破坏 VideoStreamController 等所有使用者。
    single { CoroutineScope(Dispatchers.Main + SupervisorJob() + ExceptionUtils.coroutineExceptionHandler("VideoStreamController")) }

    single { VideoStreamController(get(), get(), get()) }

    // === Data 层 DataSource（4 个方向）===
    // Local：  AppSettings / DaemonPrefs 本地配置读写
    // Remote： Daemon 控制命令 RPC 调用
    // System： PackageManager / DisplayManager 系统服务查询
    // Process：Daemon 进程生命周期控制
    single { AppSettingsDataSource(androidContext()) }
    single { DaemonRemoteDataSource(get()) }
    single { LauncherDataSource(androidContext()) }
    single { DaemonProcessDataSource(get()) }

    // === 数据层 ===
    // Repository 只做数据编排，调用 4 个 DataSource + transport/rpc/videoController
    single<IDisplayRepository> {
        DaemonDisplayRepository(
            context = androidContext(),
            settingsDataSource = get(),
            remoteDataSource = get(),
            launcherDataSource = get(),
            processDataSource = get(),
            transport = get(),
            rpc = get(),
            videoController = get(),
            shizukuManager = get()
        )
    }

    // === 领域层 ===
    single {
        DisplayInteractor(
            context = androidContext(),
            repository = get(),
            shizukuManager = get()
        )
    }

    // === ViewModel 层 ===
    viewModel {
        MainViewModel(
            interactor = get(),
            shizukuManager = get(),
            displayMetricsManager = get(),
            context = androidContext()
        )
    }
}
