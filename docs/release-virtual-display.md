# Release Virtual Display

释放一个已存在的虚拟显示器。释放前会自动将显示上的应用任务迁移回默认主屏（displayId=0）。

## Endpoint

```
CONTROL_MSG_TYPE: 202
```

## Request

### Binary Frame Format

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 消息类型，固定值 `202`                               |
| sequence     | int64   | 8 bytes| 请求序列号，用于匹配响应                             |
| display_id   | int32   | 4 bytes| 要释放的虚拟显示器 ID                                |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createReleaseVirtualDisplay(
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
| display_id   | int32   | 4 bytes| 被释放的 displayId                                   |
| msg_length   | int32   | 4 bytes| 响应消息的 UTF-8 字节长度                            |
| msg          | string  | N bytes| 响应描述：`"OK"` 或错误信息                          |

### Success Response Example

```
type: 100
sequence: 2
status_code: 0
display_id: 2
msg: "OK"
```

### Error Response Example

```
type: 100
sequence: 2
status_code: -1
display_id: 99
msg: "display id=99 not found in activeSessions"
```

## Notes

- 如果 displayId 对应的虚拟显示器不存在（不在 `activeSessions` 中），会尝试通过反射方式清理孤立显示（`DisplayCompat.bestEffortReleaseOrphan`）
- 释放时会按顺序执行（在 `VirtualDisplaySession.close()` 中）：释放 externalSurface → 关闭 ImageReader → 停止 HandlerThread → setSurface(null) → VirtualDisplay.release()
- 所有活跃的虚拟显示器会在 daemon 退出时通过 `DaemonRunner` 的 finally 块中调用 `DaemonManager.releaseAll()` 自动释放

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java) — 常量 `TYPE_RELEASE_VIRTUAL_DISPLAY = 202`
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java) — `parseReleaseVirtualDisplay()`
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DaemonCommandHandler.java) — TYPE_RELEASE_VIRTUAL_DISPLAY handler
- [DaemonManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonManager.java) — `releaseVirtualDisplay()`
- [VirtualDisplaySession.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/VirtualDisplaySession.java) — `close()`
- [DisplayCompat.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/compat/DisplayCompat.java) — `moveTasksToDefaultDisplay()`, `bestEffortReleaseOrphan()`
