# Start Video Stream

为当前 ClientSession 启动视频流捕获。需要先建立 ROLE_VIDEO Socket，然后指定要捕获的目标显示器 ID（可以是主屏 displayId=0 或已创建的虚拟显示器）。

## Endpoint

```
CONTROL_MSG_TYPE: 209
```

## Request

### Binary Frame Format

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 消息类型，固定值 `209`                               |
| sequence     | int64   | 8 bytes| 请求序列号，用于匹配响应                             |
| display_id   | int32   | 4 bytes| 要捕获的目标显示器 ID                                |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createStartVideoStream(
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
| display_id   | int32   | 4 bytes| 成功时为目标显示器 ID；失败时为 `-1`                 |
| msg_length   | int32   | 4 bytes| 响应消息的 UTF-8 字节长度                            |
| msg          | string  | N bytes| 响应描述：`"OK"` 或错误信息                          |

### Success Response Example

```
type: 100
sequence: 9
status_code: 0
display_id: 2
msg: "OK"
```

### Error Response Example

```
type: 100
sequence: 9
status_code: -1
display_id: -1
msg: "Video socket not connected within timeout"
```

## Notes

- 必须先通过 ROLE_VIDEO Socket 建立视频通道连接，再发送此命令；否则服务端会在 10 秒后超时失败
- 同一 ClientSession 只能有一个活动的视频流；如果视频已在运行，需先发送 TYPE 210 停止
- displayId 可以是主屏 `0` 或任何已创建的虚拟显示器 ID
- 内部会创建独立的 `ScreenCapture(displayId)` + `SurfaceEncoder` + `Streamer` 管线
- 视频编码参数从 `Options` 继承（分辨率、编码格式、码率等）

## 执行流程

```
ClientSession.startVideoStream(displayId)
  │
  ├── ensureVideoFdReady()
  │     └── 轮询等待 video socket 绑定（最多 10 秒）
  │
  ├── options.copyWithDisplayId(displayId)
  │
  ├── 创建 Streamer(videoFd, codec, sendMeta, frameMeta)
  │
  ├── 创建 ScreenCapture(controller, captureOptions)
  │     └── 绑定到指定 displayId
  │
  ├── 创建 SurfaceEncoder(screenCapture, streamer, captureOptions)
  │
  ├── controller.setSurfaceCapture(screenCapture)
  │
  ├── videoThread = new Thread(SurfaceEncoder::start)
  │     └── MediaCodec 编码循环
  │
  └── videoStarted.set(true)
```

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java)
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java)
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DaemonCommandHandler.java)
- [DaemonServer.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonServer.java)
- [VideoController.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/VideoController.java)
