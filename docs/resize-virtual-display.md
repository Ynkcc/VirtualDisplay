# Resize Virtual Display

动态调整已有虚拟显示器的分辨率和 DPI。会同时重建内部 ImageReader 以适配新尺寸。

## Endpoint

```
CONTROL_MSG_TYPE: 203
```

## Request

### Binary Frame Format

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 消息类型，固定值 `203`                               |
| sequence     | int64   | 8 bytes| 请求序列号，用于匹配响应                             |
| display_id   | int32   | 4 bytes| 要调整的虚拟显示器 ID                                |
| width        | int32   | 4 bytes| 新屏幕宽度（像素）                                   |
| height       | int32   | 4 bytes| 新屏幕高度（像素）                                   |
| dpi          | int32   | 4 bytes| 新屏幕密度（DPI）                                    |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createResizeVirtualDisplay(
    displayId = 2,
    width = 720,
    height = 1280,
    dpi = 240
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
| display_id   | int32   | 4 bytes| 被调整的 displayId                                   |
| msg_length   | int32   | 4 bytes| 响应消息的 UTF-8 字节长度                            |
| msg          | string  | N bytes| 响应描述：`"OK"` 或错误信息                          |

### Success Response Example

```
type: 100
sequence: 3
status_code: 0
display_id: 2
msg: "OK"
```

### Error Response Example

```
type: 100
sequence: 3
status_code: -1
display_id: 2
msg: "display id=2 not found for resize"
```

## Notes

- 执行流程：调用 `VirtualDisplay.resize()` → 关闭旧 ImageReader → 创建新 ImageReader → 重新绑定 Surface
- 如果当前已有外部设置的 Surface（通过 `setDisplaySurface()`），resize 后会继续使用该 Surface；否则使用新 ImageReader 的 Surface
- 调用方需自行确保 resize 后视频编码器也同步更新参数

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java) — 常量 `TYPE_RESIZE_VIRTUAL_DISPLAY = 203`
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java) — `parseResizeVirtualDisplay()`
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonCommandHandler.java) — TYPE_RESIZE_VIRTUAL_DISPLAY handler
- [DaemonManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonManager.java) — `resizeVirtualDisplay()`
