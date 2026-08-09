# Inject Input Event With Display ID

在指定显示器上注入原始输入事件（KeyEvent 或 MotionEvent）。事件数据使用 Android Parcel 序列化格式。

## Endpoint

```
CONTROL_MSG_TYPE: 206
```

## Request

### Binary Frame Format

| Field          | Type     | Size   | Description                                          |
| -------------- | -------- | ------ | ---------------------------------------------------- |
| type           | uint8    | 1 byte | 消息类型，固定值 `206`                               |
| sequence       | int64    | 8 bytes| 请求序列号，用于匹配响应                             |
| display_id     | int32    | 4 bytes| 目标显示器 ID                                        |
| is_key_event   | bool     | 1 byte | `1` 表示 KeyEvent，`0` 表示 MotionEvent             |
| parcel_length  | int32    | 4 bytes| Parcel 序列化数据的字节长度                          |
| parcel_data    | bytes    | N bytes| 通过 `Parcel.marshall()` 序列化的输入事件数据        |

### Kotlin Example

```kotlin
// 构造 MotionEvent 并序列化为 Parcel
val motionEvent = MotionEvent.obtain(
    SystemClock.uptimeMillis(),
    SystemClock.uptimeMillis(),
    MotionEvent.ACTION_DOWN,
    x, y, 0
)
val parcel = Parcel.obtain()
motionEvent.writeToParcel(parcel, 0)
val parcelBytes = parcel.marshall()
parcel.recycle()
motionEvent.recycle()

val bytes = CustomControlMessage.createInjectInputEventWithDisplayId(
    displayId = 2,
    isKeyEvent = false,
    parcelBytes = parcelBytes
)
// 通过 ControlChannel 发送 bytes
```

### KeyEvent Example (Kotlin)

```kotlin
val keyEvent = KeyEvent(
    SystemClock.uptimeMillis(),
    SystemClock.uptimeMillis(),
    KeyEvent.ACTION_DOWN,
    KeyEvent.KEYCODE_ENTER,
    0
)
val parcel = Parcel.obtain()
keyEvent.writeToParcel(parcel, 0)
val parcelBytes = parcel.marshall()
parcel.recycle()

val bytes = CustomControlMessage.createInjectInputEventWithDisplayId(
    displayId = 2,
    isKeyEvent = true,
    parcelBytes = parcelBytes
)
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
sequence: 6
status_code: 0
display_id: 2
msg: "OK"
```

### Error Response Example

```
type: 100
sequence: 6
status_code: -1
display_id: 2
msg: "Failed to inject input event"
```

## Notes

- 使用 `Device.injectEvent(event, displayId, Device.INJECT_MODE_ASYNC)` 模式注入（displayId != 0 时）
- 当 `displayId == 0` 时，使用 `ServiceManager.getInputManager().injectInputEvent(event, INJECT_MODE_ASYNC)` 注入，不走 Device 层
- 对非主屏事件，会先调用 `InputManager.setDisplayId(event, targetDisplayId)` 设置事件目标
- Parcel 数据通过 `Parcel.obtain()` → `unmarshall()` → `CREATOR.createFromParcel()` 反序列化
- 此方法绕过了 Controller 的输入映射逻辑，直接在目标 display 上发送原始事件
- 最大 Parcel 数据大小受 `MESSAGE_MAX_SIZE` (256KB) 限制

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java) — 常量 `TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID = 206`
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java) — `parseInjectInputEventWithDisplayId()`
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DaemonCommandHandler.java) — TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID handler
