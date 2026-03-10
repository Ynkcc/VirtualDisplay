# MyApplication（多虚拟显示控制 Demo）


多虚拟显示器项目

计划达到轻量级虚拟机的效果
通过指定目标app和对应的辅助脚本 在特定虚拟屏幕上运行，以实现低成本挂机需求
受限于个人水平，未完成
只完成了虚拟屏创建 及移除 在目标虚拟屏启动/注入触摸 ，但是存在较多影响使用的bug

提交 0380e99753a4c72bddb2721a937752d2d504762f 应该对虚拟屏的稳定性有所帮助
以及 git commit 评论并不准确

以下为ai生成

---

一个基于 **Shizuku + scrcpy server 能力** 的 Android 示例工程，用于：

- 创建/管理虚拟显示（Virtual Display）
- 在指定虚拟显示上启动应用
- 在预览窗口中显示虚拟显示画面
- 将触摸事件映射后注入到目标 display

> 当前工程名为 `My Application`，包名为 `com.example.myapplication`。

## 功能概览

- **Shizuku 权限检查与连接管理**：自动检测 Shizuku 是否运行、是否授权，并绑定远程服务
- **虚拟显示管理**：支持自定义 `width/height/dpi` 创建虚拟显示，支持刷新、释放
- **应用启动到指定显示**：从应用列表中选择目标 App 并启动到指定 `displayId`
- **画面预览与触摸映射**：`DisplayActivity` 使用 `TextureView + Matrix` 做旋转/缩放适配，并将触摸坐标反变换后注入
- **孤儿显示识别**：服务端会对系统中可疑已管理显示进行对账，尽量避免残留 display 造成状态不一致

## 技术栈

- Kotlin
- Android Jetpack（Activity / ViewModel / Lifecycle / Compose）
- Shizuku API（`dev.rikka.shizuku`）
- HiddenApiBypass
- scrcpy server 侧代码复用（已通过 `sourceSets` 引入）

## 环境要求

- Linux / macOS / Windows（可构建 Android 工程）
- Android Studio（建议最新稳定版）
- JDK 17+（工程中 Android Java Toolchain 配置为 21，`compileOptions` 为 17）
- Android SDK：
  - `compileSdk = 36`（minorApiLevel = 1）
  - `targetSdk = 35`
  - `minSdk = 29`
- 设备端安装并运行 **Shizuku**

## 快速开始

### 1) 打开工程

在 Android Studio 中打开根目录：

`/home/ynk/AndroidStudioProjects/MyApplication`

### 2) 同步并构建

```bash
./gradlew :app:assembleDebug
```

生成 APK：

`app/build/outputs/apk/debug/app-debug.apk`

### 3) 设备准备（关键）

1. 在设备安装并启动 Shizuku
2. 按 Shizuku 指引通过 ADB 或 Root 激活服务
3. 首次启动本 App 时授予 Shizuku 权限

### 4) 使用流程

1. 进入主页面，确认状态为可用（Ready）
2. 输入虚拟显示参数（Width / Height / DPI）并创建
3. 在列表中选择一个 display：
   - **Play**：打开预览与触摸交互页面
   - **Launch**：将选中的应用启动到该显示
   - **Delete**：释放该虚拟显示

## 目录说明（核心）

- `app/src/main/java/com/example/myapplication/MainActivity.kt`：入口、Shizuku 状态驱动 UI
- `app/src/main/java/com/example/myapplication/MainViewModel.kt`：业务状态与 display 管理
- `app/src/main/java/com/example/myapplication/ShizukuDisplayBridge.kt`：App 进程与 Shizuku 服务桥接
- `app/src/main/java/com/example/myapplication/DisplayUserService.kt`：远程服务实现（创建显示、注入输入、启动 Activity）
- `app/src/main/java/com/example/myapplication/DisplayActivity.kt`：预览渲染与触摸映射

## 常见问题

- **Shizuku 不可用 / 权限被拒绝**  
  先确认 Shizuku 服务已启动，再在 App 内重新请求权限。

- **能创建显示但无法交互**  
  检查目标设备系统版本与权限状态，确认 Shizuku 侧输入注入接口可用。

- **显示比例异常或旋转不正确**  
  当前实现已通过 `TextureView` 变换矩阵处理常见场景，可优先尝试刷新显示或重建 display。

## 说明

该工程包含多个上游目录（如 `scrcpy/`、`Shizuku-API/`）用于参考与能力集成；主 Android App 模块以根目录 `app/` 为准。