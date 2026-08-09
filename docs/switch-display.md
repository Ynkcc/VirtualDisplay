# Switch Display

切换当前 scrcpy 视频流的目标显示器。此操作会同时更新 `DaemonManager` 的目标 displayId 和 `ScreenCapture` 的捕获 displayId。

## Endpoint

```
CONTROL_MSG_TYPE: 207
```

## Request

### Binary Frame Format

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 消息类型，固定值 `207`                               |
| sequence     | int64   | 8 bytes| 请求序列号，用于匹配响应                             |
| display_id   | int32   | 4 bytes| 要切换到的目标显示器 ID                              |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createSwitchDisplay(
    displayId = 2
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
| display_id   | int32   | 4 bytes| 切换后的目标显示器 ID                                |
| msg_length   | int32   | 4 bytes| 响应消息的 UTF-8 字节长度                            |
| msg          | string  | N bytes| 响应描述：`"OK"` 或错误信息                          |

### Success Response Example

```
type: 100
sequence: 7
status_code: 0
display_id: 2
msg: "OK"
```

## Notes

- 命令会验证目标 displayId 的有效性：`displayId == 0`（主屏）或 `DaemonManager.hasDisplay(displayId)`（已创建的虚拟显示器）
- 调用后 `ScreenCapture.setDisplayId()` 会立即生效，下一个视频帧将来自新显示器
- 此操作同时更新 `DaemonManager.targetDisplayId`（全局默认目标）和 `Controller` 当前的 `ScreenCapture`
- 此操作不影响其他已创建的虚拟显示器，仅改变 scrcpy 当前捕获的目标
- 默认目标显示器为主屏（displayId = 0）
- 如果要从虚拟显示器切换回主屏，发送 `display_id = 0` 即可

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java) — 常量 `TYPE_SWITCH_DISPLAY = 207`
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java) — `parseSwitchDisplay()`
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DaemonCommandHandler.java) — TYPE_SWITCH_DISPLAY handler
- [DaemonManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonManager.java) — `setTargetDisplayId()`, `hasDisplay()`
