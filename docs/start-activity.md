# Start Activity

在指定的虚拟显示器上启动一个应用（通过包名解析 Launch Intent）。

## Endpoint

```
CONTROL_MSG_TYPE: 204
```

## Request

### Binary Frame Format

| Field           | Type    | Size   | Description                                          |
| --------------- | ------- | ------ | ---------------------------------------------------- |
| type            | uint8   | 1 byte | 消息类型，固定值 `204`                               |
| sequence        | int64   | 8 bytes| 请求序列号，用于匹配响应                             |
| package_length  | int32   | 4 bytes| 包名字符串的 UTF-8 字节长度                          |
| package_name    | string  | N bytes| 目标应用的包名（如 `com.example.app`）              |
| display_id      | int32   | 4 bytes| 目标显示器 ID（可为虚拟显示器 ID 或主屏 ID=0）       |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createStartActivity(
    packageName = "com.android.settings",
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
| display_id   | int32   | 4 bytes| 目标显示器 ID                                        |
| msg_length   | int32   | 4 bytes| 响应消息的 UTF-8 字节长度                            |
| msg          | string  | N bytes| 响应描述：`"OK"` 或错误信息                          |

### Success Response Example

```
type: 100
sequence: 4
status_code: 0
display_id: 2
msg: "OK"
```

### Error Response Example

```
type: 100
sequence: 4
status_code: -1
display_id: 2
msg: "Cannot create launch intent for app com.example.app"
```

## Notes

- 通过 `PackageManager.getLaunchIntentForPackage()` 解析启动 Intent
- 若标准 Launch Intent 为空，会尝试 `getLeanbackLaunchIntentForPackage()`（Android TV 场景）
- Android 8.0+ (API 26+) 使用 `ActivityOptions.setLaunchDisplayId()` 指定目标显示
- Intent 会自动添加 `FLAG_ACTIVITY_NEW_TASK` 标志

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java) — 常量 `TYPE_START_ACTIVITY = 204`
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java) — `parseStartActivityWithDisplay()`
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DaemonCommandHandler.java) — TYPE_START_ACTIVITY handler
- [DaemonManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonManager.java) — `startActivity()`
