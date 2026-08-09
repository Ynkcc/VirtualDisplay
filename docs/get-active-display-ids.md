# Get Active Display IDs

获取当前所有由 DaemonManager 管理的活跃虚拟显示器 ID 列表。

## Endpoint

```
CONTROL_MSG_TYPE: 205
```

## Request

### Binary Frame Format

| Field        | Type    | Size   | Description                                          |
| ------------ | ------- | ------ | ---------------------------------------------------- |
| type         | uint8   | 1 byte | 消息类型，固定值 `205`                               |
| sequence     | int64   | 8 bytes| 请求序列号，用于匹配响应                             |

### Kotlin Example

```kotlin
val bytes = CustomControlMessage.createGetActiveDisplayIds()
// 通过 ControlChannel 发送 bytes
```

## Response

### Active Displays Response (TYPE: 101)

| Field          | Type    | Size   | Description                                          |
| -------------- | ------- | ------ | ---------------------------------------------------- |
| type           | uint8   | 1 byte | 响应类型，固定值 `101`                               |
| sequence       | int64   | 8 bytes| 对应请求的序列号                                     |
| count          | int32   | 4 bytes| 活跃显示器数量                                       |
| display_ids[]  | int32[] | N×4 bytes | 活跃显示器 ID 数组，长度等于 `count`               |

### Success Response Example

```
type: 101
sequence: 5
count: 2
display_ids: [2, 3]
```

### Empty Response Example

当没有活跃虚拟显示器时：

```
type: 101
sequence: 5
count: 0
display_ids: []
```

## Notes

- 返回的 displayId 来自 `DaemonManager.activeSessions` 映射表的当前快照
- 主屏（displayId=0）不在返回列表中，列表仅包含通过 `createVirtualDisplay()` 创建的虚拟显示器
- 列表顺序不保证与创建顺序一致

## Source

- [ControlMessage.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java) — 常量 `TYPE_GET_ACTIVE_DISPLAY_IDS = 205`
- [ControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java) — TYPE_GET_ACTIVE_DISPLAY_IDS case
- [DeviceMessageWriter.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DeviceMessageWriter.java) — TYPE_RESPONSE_ACTIVE_DISPLAYS 序列化
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/control/DaemonCommandHandler.java) — TYPE_GET_ACTIVE_DISPLAY_IDS handler
- [DaemonManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/DaemonManager.java) — `getActiveDisplayIds()`
