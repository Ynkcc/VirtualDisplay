# Create Virtual Display

创建一个新的虚拟显示器。虚拟显示器由系统 DisplayManager 管理，支持动态调整分辨率和 DPI。

## Endpoint

```
CONTROL_MSG_TYPE: 201
```

## Request

### Binary Frame Format

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 消息类型，固定值 `201`                               |
| sequence     | int64   | 8 bytes| 请求序列号，用于匹配响应                             |
| name_length  | int32   | 4 bytes| 虚拟显示器名称的 UTF-8 字节长度                      |
| name         | string  | N bytes| 虚拟显示器名称（UTF-8 编码）                         |
| width        | int32   | 4 bytes| 屏幕宽度（像素）                                     |
| height       | int32   | 4 bytes| 屏幕高度（像素）                                     |
| dpi          | int32   | 4 bytes| 屏幕密度（DPI）                                      |
| flags        | int32   | 4 bytes| 显示器创建标志位，参考 `DisplayManager.VIRTUAL_DISPLAY_FLAG_*` |

### Flags 常用值

| Flag Name                              | Value  | Description                          |
| -------------------------------------- | ------ | ------------------------------------ |
| VIRTUAL_DISPLAY_FLAG_PUBLIC            | 0x0001 | 创建公共虚拟显示                     |
| VIRTUAL_DISPLAY_FLAG_SECURE            | 0x0002 | 创建安全虚拟显示（支持 SurfaceFlinger） |
| VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH    | 0x0100 | 支持触摸事件                         |
| VIRTUAL_DISPLAY_FLAG_OWN_FOCUS         | 0x0400 | 拥有输入焦点                         |
| VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY  | 0x0008 | 仅显示自身内容                       |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createCreateVirtualDisplay(
    name = "VirtualDisplay_1",
    width = 1080,
    height = 1920,
    dpi = 320,
    flags = 0x0001 or 0x0100
)
// 通过 ControlChannel 发送 bytes
```

## Response

### Generic Response (TYPE: 100)

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 响应类型，固定值 `100`                               |
| sequence     | int64   | 8 bytes| 对应请求的序列号                                     |
| status_code  | int32   | 4 bytes| 状态码：`0` 成功，`-1` 失败                          |
| display_id   | int32   | 4 bytes| 成功时返回新创建的 displayId；失败时为 `-1`          |
| msg_length   | int32   | 4 bytes| 响应消息的 UTF-8 字节长度                            |
| msg          | string  | N bytes| 响应描述：`"OK"` 或错误信息                          |

### Success Response Example

```
type: 100
sequence: 1
status_code: 0
display_id: 2
msg: "OK"
```

### Error Response Example

```
type: 100
sequence: 1
status_code: -1
display_id: -1
msg: "Failed to create virtual display"
```

## Notes

- 创建虚拟显示会自动通过 `requestDisplayPower()` 点亮屏幕
- 虚拟显示会在内部维护 ImageReader 作为默认 Surface，可通过 `setDisplaySurface()` 替换
- 释放前需确保将应用任务迁移回主屏幕（自动处理）

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java) — 常量 `TYPE_CREATE_VIRTUAL_DISPLAY = 201`
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java) — `parseCreateVirtualDisplay()`
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DaemonCommandHandler.java) — `initRegistry()` 中 TYPE_CREATE_VIRTUAL_DISPLAY handler
- [DaemonManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonManager.java) — `createVirtualDisplay()`
- [VirtualDisplaySession.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/VirtualDisplaySession.java) — 会话封装类
