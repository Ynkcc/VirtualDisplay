# Get Active Display Infos

获取当前所有由 Daemon 管理的活跃虚拟显示器列表，**附带每个显示器的尺寸/DPI/旋转元数据**。

这是 [Get Active Display IDs](get-active-display-ids.md)（TYPE 205 / 响应 101，仅返回 id 列表）的增强版本，采用独立的请求/响应类型以保持向后兼容：旧的 205/101 路径保持不变。

## Endpoint

```
CONTROL_MSG_TYPE: 215
```

## Request

### Binary Frame Format

| Field    | Type   | Size    | Description                                          |
| -------- | ------ | ------- | ---------------------------------------------------- |
| type     | uint8  | 1 byte  | 消息类型，固定值 `215`                               |
| sequence | int64  | 8 bytes | 请求序列号，用于匹配响应                             |

### Kotlin Example

```kotlin
val infos = controlApi.getActiveDisplayInfos()  // 返回 List<DisplayInfoEntry>
infos.forEach { e ->
    println("display ${e.displayId}: ${e.width}x${e.height} dpi=${e.dpi} rot=${e.rotation}")
}
```

### Python Example

```python
resp = client.get_active_display_infos()
for d in resp["displays"]:
    print(d["display_id"], d["width"], d["height"], d["dpi"], d["rotation"])
```

## Response

### Active Display Infos Response (TYPE: 102)

| Field          | Type      | Size       | Description                                            |
| -------------- | --------- | ---------- | ------------------------------------------------------ |
| type           | uint8     | 1 byte     | 响应类型，固定值 `102`                                 |
| sequence       | int64     | 8 bytes    | 对应请求的序列号                                       |
| count          | int32     | 4 bytes    | 活跃显示器数量                                         |
| displays[]     | struct[]  | count×20B  | 每个显示器一条记录，结构见下                           |

每条 `displays[]` 记录布局（连续 5 个 int32，共 20 字节）：

| Field      | Type   | Size    | Description                                  |
| ---------- | ------ | ------- | -------------------------------------------- |
| display_id | int32  | 4 bytes | 显示器 ID                                    |
| width      | int32  | 4 bytes | 逻辑宽度（像素，已考虑旋转）                 |
| height     | int32  | 4 bytes | 逻辑高度（像素，已考虑旋转）                 |
| dpi        | int32  | 4 bytes | 逻辑密度 DPI                                |
| rotation   | int32  | 4 bytes | 旋转角度 `0/1/2/3`（ROTATION_0/90/180/270）  |

### Success Response Example

```
type: 102
sequence: 7
count: 2
displays: [
  { display_id: 2, width: 1080, height: 1920, dpi: 320, rotation: 0 },
  { display_id: 3, width: 720,  height: 1280, dpi: 240, rotation: 0 }
]
```

### Empty Response Example

```
type: 102
sequence: 7
count: 0
displays: []
```

## Notes

- 元数据来自 `DisplayManagerGlobal.getDisplayInfo(displayId)`（带 `dumpsys display` 兜底），因此 width/height 为逻辑尺寸（已应用旋转）。
- 主屏（displayId=0）不在返回列表中，列表仅包含通过 `createVirtualDisplay()` 创建的虚拟显示器。
- 若某个 displayId 的 `DisplayInfo` 无法解析（极少见），该条目会被跳过，不计入 `count`。
- 与 TYPE 205 的关系：205 返回 101（仅 id 数组）；215 返回 102（含元数据）。两者互不影响，客户端可按需选用。

## Source

- [DaemonControlMessages.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonControlMessages.java) — 常量 `TYPE_GET_ACTIVE_DISPLAY_INFOS = 215`
- [DaemonControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonControlMessageReader.java) — `TYPE_GET_ACTIVE_DISPLAY_INFOS` case
- [DaemonDeviceMessages.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonDeviceMessages.java) — `TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS = 102` / `createActiveDisplayInfosResponse()`
- [DaemonDeviceMessageWriter.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonDeviceMessageWriter.java) — TYPE 102 序列化
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonCommandHandler.java) — `TYPE_GET_ACTIVE_DISPLAY_INFOS` handler
- [VirtualDisplayRegistry.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/display/VirtualDisplayRegistry.java) — `getActiveDisplayInfos()`
