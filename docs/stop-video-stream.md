# Stop Video Stream

停止当前 ClientSession 的视频流捕获。服务端会关闭 SurfaceEncoder、释放 ScreenCapture，并关闭视频管线中的所有资源。

## Endpoint

```
CONTROL_MSG_TYPE: 210
```

## Request

### Binary Frame Format

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 消息类型，固定值 `210`                               |
| sequence     | int64   | 8 bytes| 请求序列号，用于匹配响应                             |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createStopVideoStream()
// 通过 ControlChannel 发送 bytes
```

## Response

### Generic Response (TYPE: 100)

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 响应类型，固定值 `100`                               |
| sequence     | int64   | 8 bytes| 对应请求的序列号                                     |
| status_code  | int32   | 4 bytes| 状态码：`0` 成功，`-1` 失败                          |
| display_id   | int32   | 4 bytes| 固定为 `-1`                                          |
| msg_length   | int32   | 4 bytes| 响应消息的 UTF-8 字节长度                            |
| msg          | string  | N bytes| 响应描述：`"OK"` 或错误信息                          |

### Success Response Example

```
type: 100
sequence: 10
status_code: 0
display_id: -1
msg: "OK"
```

### Error Response Example

```
type: 100
sequence: 10
status_code: -1
display_id: -1
msg: "Video stream not started"
```

## Notes

- 如果当前没有活动的视频流，命令会返回失败（`video not started`）
- 停止视频流不会影响其他 ClientSession 的视频流（每个 session 独立）
- 停止后 video socket 仍然保持连接，可以重新发送 TYPE 209 开始新的视频流
- 停止后如果需要更换目标显示器，发送 TYPE 209 时指定不同的 displayId 即可
- ClientSession 关闭时会自动调用 `stopVideoInternal()` 清理资源

## 执行流程

```
ClientSession.stopVideoStream()
  │
  ├── 检查 videoStarted.get() == false → 返回 false
  │
  └── stopVideoInternal()
        ├── videoStarted.set(false)
        ├── surfaceEncoder.stop()
        ├── surfaceCapture.release()
        ├── surfaceEncoder = null
        ├── surfaceCapture = null
        └── videoStreamer = null
```

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java)
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java)
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonCommandHandler.java)
- [DaemonServer.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/DaemonServer.java)
- [VideoController.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/VideoController.java)
