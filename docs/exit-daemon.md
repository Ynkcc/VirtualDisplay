# Exit Daemon

请求 Daemon 模式的 scrcpy server 进程优雅退出。发送后 server 会先回复确认消息，然后在 100ms 后执行退出流程。

## Endpoint

```
CONTROL_MSG_TYPE: 208
```

## Request

### Binary Frame Format

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 消息类型，固定值 `208`                               |
| sequence     | int64   | 8 bytes| 请求序列号，用于匹配响应                             |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createExitDaemon()
// 通过 ControlChannel 发送 bytes
```

## Response

### Generic Response (TYPE: 100)

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 响应类型，固定值 `100`                               |
| sequence     | int64   | 8 bytes| 对应请求的序列号                                     |
| status_code  | int32   | 4 bytes| 状态码：固定为 `0`（确认请求已接收）                 |
| display_id   | int32   | 4 bytes| 固定为 `-1`                                          |
| msg_length   | int32   | 4 bytes| 响应消息的 UTF-8 字节长度                            |
| msg          | string  | N bytes| 固定为 `"OK"`                                        |

### Success Response Example

```
type: 100
sequence: 8
status_code: 0
display_id: -1
msg: "OK"
```

## Notes

- 响应消息发送后，server 会等待 100ms 再执行退出，给客户端足够时间读取响应
- 退出流程包括：DaemonCommandHandler 回复 OK → 延迟 100ms → `DaemonManager.requestExitDaemon()` 设置标志 → `DaemonCommandHandler.close()` 关闭线程池
- Controller 主循环检测到退出标志后跳出，ClientSession.run() 的 finally 块会调用 `DaemonServer.requestExit()` 触发服务器关闭
- `DaemonServer.shutdown()` 会关闭所有活跃 ClientSession、停止 acceptExecutor/clientExecutor、调用 `TcpDesktopConnection.closeServerSocket()`
- `DaemonRunner.run()` 的 finally 块确保即使异常也会调用 `DaemonManager.releaseAll()` 清理所有虚拟显示器
- 客户端应在收到此响应后关闭连接并清理本地资源

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java) — 常量 `TYPE_EXIT_DAEMON = 208`
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java) — TYPE_EXIT_DAEMON case
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DaemonCommandHandler.java) — TYPE_EXIT_DAEMON handler
- [DaemonServer.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonServer.java) — `requestExit()`, `shutdown()`
- [DaemonRunner.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonRunner.java) — 主循环和 finally 清理
- [DaemonManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonManager.java) — `requestExitDaemon()`, `releaseAll()`
