# 屏幕分身（多虚拟显示控制）

本应用是一个基于 **Shizuku** 和 **scrcpy** 技术开发的 Android 辅助工具，用于在手机上创建多个“虚拟屏幕”，并能在这些独立的屏幕中运行不同的应用。主要适用于多开挂机、多任务并行等场景。

## 主要功能

- **创建虚拟屏幕**：支持自定义分辨率与屏幕密度（DPI）创建虚拟屏幕。
- **独立运行应用**：从应用列表中选择任意应用，让其在指定的虚拟屏幕中单独运行。
- **预览与控制**：支持实时预览虚拟屏幕的画面，并可以直接通过触摸进行交互与控制。

## 使用前提

1. **安装并启动 Shizuku**：本应用依赖 [Shizuku](https://shizuku.rikka.app/) 服务。请先在您的安卓设备上安装并激活 Shizuku（可通过无线调试或 Root 激活）。
2. **授予权限**：首次打开本应用时，请按照提示授予 Shizuku 访问权限。

## 快速上手

1. **启动服务**：确认 Shizuku 服务已启动，打开“屏幕分身”应用。
2. **新建屏幕**：输入您想要的宽度、高度和 DPI（例如 1080, 2400, 440），点击创建。
3. **管理与使用**：
   - **Play**：打开预览窗口，支持触摸操作。
   - **Launch**：选择想要在该屏幕中启动的应用。
   - **Delete**：删除/释放该虚拟屏幕。

## 常见问题

- **预览画面显示异常？**
  如果画面方向、比例或缩放不正确，可以尝试刷新显示或重新创建虚拟屏幕。

- **创建屏幕时出现 `packageName must match the calling uid`？**
  请确保以 **adb / shell 身份**激活 Shizuku，而非以 Root 身份激活。
  部分设备的 [`DisplayManagerService`](https://github.com/cn00/android/blob/master/services/core/java/com/android/server/display/DisplayManagerService.java#L1532) 会校验调用者的 UID 与 packageName 是否匹配；而scrcpy 通过将 `PACKAGE_NAME` 固定为 `"com.android.shell"`，以 Root 身份运行时 会导致二者不匹配从而抛出 `SecurityException`。（ 本项目暂未对此进行修复。）

## 致谢

本项目的实现离不开以下开源项目的支持：
- [Shizuku](https://github.com/RikkaApps/Shizuku) - 提供免 Root/特权 API 访问能力。
- [scrcpy](https://github.com/Genymobile/scrcpy) - 提供高效的屏幕控制与输入注入逻辑。

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 协议开源。