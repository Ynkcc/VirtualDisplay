package com.ynk.virtualdisplay.di

import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.process.DaemonProcessDataSource
import com.ynk.virtualdisplay.data.remote.DaemonRemoteDataSource
import com.ynk.virtualdisplay.data.repository.MultiConnectionRepository
import com.ynk.virtualdisplay.data.repository.ConnectionSlotFactory
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
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
    single { DaemonProcessController(androidContext(), get()) }
    single { DaemonTransport() }
    single { DaemonRpc(get()) }
    // 先注册实现类，再分别绑定两个接口（都指向同一个单例实例）
    // DaemonControlApi：控制命令 API（供 RemoteDataSource 使用）
    // VideoStreamRpc：视频流 RPC（供 VideoStreamController 使用）
    single { DaemonControlApiImpl(get(), get()) }
    single<DaemonControlApi> { get<DaemonControlApiImpl>() }
    /**
     * 进程级守护 Scope（named("processScope")）。
     *
     * 生命周期：跟随应用进程，随进程结束而终结，绝不因某个组件的 cancel() 失效。
     *
     * 归属与共享：由本模块（进程级）创建并独占管理，供多个跨组件共享的"长驻"组件使用——
     * 主要是 [VideoStreamController]（视频流 ping 循环）与 [MultiConnectionRepository]（仓库编排）。
     *
     * 硬性约束：任何组件（VideoStreamController / MultiConnectionRepository / ConnectionSlot 等）
     * 都严禁对其调用 cancel()，否则会不可逆地破坏全部使用者。与其相对的是 ConnectionSlot 内部
     * 自建的可 cancel 槽级 scope——那是"谁创建谁负责关闭"的资源容器 scope，由 ConnectionSlot
     * 在 destroyService 中自行 cancel，二者用不同构造入口/命名严格区分，避免误 cancel。
     */
    single(named("processScope")) { CoroutineScope(Dispatchers.Main + SupervisorJob() + ExceptionUtils.coroutineExceptionHandler("VideoStreamController")) }

    // 应用级后台 Scope：用于 fire-and-forget 的清理/异步任务（如 Activity 销毁后
    // 仍需执行的 stopStreaming）。生命周期同样跟随应用进程，不随某个组件的 cancel 失效。
    // 与 [processScope] 的关系：二者都是进程级、不可 cancel 的守护 Scope；区别仅在调度器——
    // processScope 跑在 Main（长驻交互组件），此处跑在 IO（一次性后台清理）。
    single(named("appBackgroundScope")) {
        CoroutineScope(Dispatchers.IO + SupervisorJob() + ExceptionUtils.coroutineExceptionHandler("AppBackground"))
    }

    single { VideoStreamController(get(), get(), get(named("processScope")), get()) }

    // === Data 层 DataSource ===
    // Local：  AppSettings / DaemonPrefs 本地配置读写
    // Remote： Daemon 控制命令 RPC 调用（launchHome / listApps / startActivity 等统一走 RPC）
    // Process：Daemon 进程生命周期控制（本机节点独有）
    single { AppSettingsDataSource(androidContext()) }
    single { DaemonRemoteDataSource(get()) }
    // DaemonProcessDataSource 是特权检查的唯一汇聚点，故注入其判定所需的配置与 Shizuku 状态。
    single { DaemonProcessDataSource(get(), get(), get()) }

    // === Multi-Connection Infrastructure ===
    single {
        ConnectionSlotFactory(
            settingsDataSource = get(),
            processDataSource = get()
        )
    }

    // === 数据层 ===
    // Repository 只做数据编排，调用 DataSource + transport/rpc/videoController
    single<IDisplayRepository> {
        MultiConnectionRepository(
            slotFactory = get(),
            scope = get(named("processScope")) // 与 VideoStreamController 共享进程级守护 Scope，不可 cancel
        )
    }

    // === 领域层 ===
    single {
        DisplayInteractor(
            repository = get(),
            processDataSource = get(),
            settingsDataSource = get()
        )
    }

    // === ViewModel 层 ===
    viewModel {
        MainViewModel(
            interactor = get(),
            shizukuManager = get(),
            displayMetricsManager = get(),
            settingsDataSource = get()
        )
    }
}
