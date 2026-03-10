# MyApplication - Shizuku Virtual Display Manager

## 项目架构 (Project Architecture)

本项目是一个利用 **Shizuku** 提权机制，在 Android 系统中管理和操控 **VirtualDisplay**（虚拟显示器）的应用。

- **Privileged Layer (特权层)**: 
  - `DisplayUserService`: 运行在独立进程（如 `:display_service`）中的核心组件。
  - **身份与权限**: 该进程由 Shizuku 启动，拥有 **Shell (UID 2000)** 或 **Root (UID 0)** 身份，天然具备调用系统隐藏 API 的权限。
  - **权限差异**: ADB (Shell) 身份受限（如无法访问其他 App 私有目录），而 Root (0) 拥有完整系统权限。
  - **Hidden API Access**: 使用 `HiddenApiBypass` 和 `scrcpy` 包装类（`com.genymobile.scrcpy`）直接调用系统隐藏的 `DisplayManager` 和 `InputManager` 方法。
- **IPC Layer (通信层)**: 
  - **AIDL 机制**: 基于 `IDisplayService.aidl` 定义 Binder 接口。App 进程通过 `Shizuku.bindUserService` 与特权进程建立双向通信。
  - **接口定义**: 包括创建/释放显示器、设置 Surface、启动 Activity 和注入输入事件。
- **App Layer (应用层)**:
  - `ShizukuDisplayBridge`: 作为 `IDisplayRepository` 的实现，封装了服务绑定、状态监听和协程包装的业务逻辑。
  - `ShizukuServiceBinder`: 专门负责 Shizuku 服务的连接生命周期管理。
- **UI Layer (视图层)**:
  - 基于 **Jetpack Compose** 的响应式界面。
  - 采用 **MVVM** 架构，通过 `MainViewModel` 和 `StateFlow` 进行状态驱动。

## 核心逻辑说明 (Core Logic)

1. **虚拟显示器创建**: 在特权进程中利用 `ServiceManager.getDisplayManager().createNewVirtualDisplay(...)` 绕过普通 App 无法创建包含系统内容的显示器的限制。
2. **输入注入路由**: 
   - 普通 App 注入事件通常只能作用于默认屏幕。
   - 本项目通过在特权侧（`DisplayUserService`）调用 `InputEvent.setDisplayId(displayId)`，确保触摸和按键事件能精确路由到指定的虚拟显示器。
3. **Surface 管理**: 
   - 虚拟显示器的内容渲染依赖于跨进程传递的 `Surface` 对象（通常来自 `DisplayActivity` 的 `SurfaceView` 或 `TextureView`）。
   - **关键**: 必须在 UI 销毁时通过 AIDL 调用 `setVirtualDisplaySurface(id, null)`，以断开远程引用，防止内存泄漏。

## 服务运行环境 (Service Runtime Environment)

- **非标准 App 上下文**: `DisplayUserService` 运行在一个类似于“裸 Java 程序”的环境中。虽然可以通过 Shizuku 获取 `Context`，但许多依赖 ActivityThread 的 API（如 `registerReceiver`, `getContentResolver`）将无法正常工作。
- **进程生命周期**: 
  - 使用 `daemon(true)` 保持服务在后台运行。
  - **回收机制**: 服务进程不会随 App 退出而直接结束。需通过 AIDL 接口中的 `destroy()`（实现时发送事务代码 `16777114`）通知特权进程执行 `System.exit(0)`。

## 关键约束与开发原则 (Critical Mandates)

- **资源清理**: `VirtualDisplay` 是昂贵的系统资源。任何创建动作必须配对释放动作。优先处理 "Orphan"（孤儿）显示器的检测与回收。
- **稳定性优先**: 所有的 AIDL 远程调用必须使用 `runCatching` 或 `try-catch` 包裹，以应对 `DeadObjectException` 或 Shizuku 服务意外退出的情况。
- **并发控制**: 所有的 Repository 方法应为 `suspend` 函数，并明确在 `Dispatchers.IO` 中执行。
- **代码风格**: 
  - 遵循 Kotlin 习惯用法，优先使用 `Flow` 进行异步流处理。
  - 保持特权服务 `DisplayUserService` 逻辑简洁，尽量减少其持有的状态。

## 开发建议 (Development Tips)

- **AS 配置**: 为确保服务使用最新代码，需在 Android Studio 的 "Run/Debug configurations" 中勾选 **"Always install with package manager"**。
- **调试限制**: 由于特权进程环境特殊，无法使用常规的 App 调试器，建议大量通过 `Log`（打印至 logcat）进行调试。
- **API 兼容性**: 建议频繁查阅 [cs.android.com](https://cs.android.com) 以确认不同 Android 版本下隐藏 API 的参数变化。
