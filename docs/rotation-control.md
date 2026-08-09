# Rotation Control

按显示器查询与控制旋转状态。包含四个命令，均通过通用响应 [TYPE 100](README.md#type-100---generic-response) 回复：

| ID   | 名称                  | 说明                                       |
| ---- | --------------------- | ------------------------------------------ |
| 211  | Get Rotation          | 查询指定显示器当前旋转角度                 |
| 212  | Freeze Rotation       | 将指定显示器冻结到指定旋转角度             |
| 213  | Thaw Rotation         | 解冻指定显示器的旋转                       |
| 214  | Is Rotation Frozen    | 查询指定显示器旋转是否已冻结               |

所有命令均接受 `displayId`：主屏 `0` 始终允许；其它 id 必须是 daemon 管理的虚拟显示器，否则返回失败。

## 通用响应约定

| 命令               | status_code=0 时 display_id 字段 | status_code=0 时 msg 字段 |
| ------------------ | -------------------------------- | ------------------------- |
| Get Rotation       | 请求的 displayId                 | 旋转值字符串 `"0".."3"`   |
| Freeze Rotation    | 请求的 displayId                 | `"OK"`                    |
| Thaw Rotation      | 请求的 displayId                 | `"OK"`                    |
| Is Rotation Frozen | 请求的 displayId                 | `"0"` 或 `"1"`            |

失败时 `status_code=-1`，`msg` 为错误描述。

---

## TYPE 211 — Get Rotation

### Request

| Field      | Type   | Size    | Description          |
| ---------- | ------ | ------- | -------------------- |
| type       | uint8  | 1 byte  | `211`                |
| sequence   | int64  | 8 bytes | 请求序列号           |
| display_id | int32  | 4 bytes | 目标显示器 ID        |

### Response (TYPE 100)

```
status_code: 0
display_id: 2
msg: "0"          # 旋转值 0-3
```

---

## TYPE 212 — Freeze Rotation

将指定显示器冻结到给定旋转角度。底层调用 `WindowManager.freezeDisplayRotation(displayId, rotation)`（多版本签名兜底）。

### Request

| Field      | Type   | Size    | Description                            |
| ---------- | ------ | ------- | -------------------------------------- |
| type       | uint8  | 1 byte  | `212`                                  |
| sequence   | int64  | 8 bytes | 请求序列号                             |
| display_id | int32  | 4 bytes | 目标显示器 ID                          |
| rotation   | int32  | 4 bytes | 目标旋转 `0/1/2/3`（ROTATION_0/90/180/270） |

### Response (TYPE 100)

```
status_code: 0
display_id: 2
msg: "OK"
```

### Error Cases

- `display_id` 不存在 → `status_code=-1`，msg 含 "Display not found"
- `rotation` 不在 0-3 → `status_code=-1`，msg 含 "Invalid rotation"

---

## TYPE 213 — Thaw Rotation

解冻指定显示器旋转。底层调用 `WindowManager.thawDisplayRotation(displayId)`。

### Request

| Field      | Type   | Size    | Description      |
| ---------- | ------ | ------- | ---------------- |
| type       | uint8  | 1 byte  | `213`            |
| sequence   | int64  | 8 bytes | 请求序列号       |
| display_id | int32  | 4 bytes | 目标显示器 ID    |

### Response (TYPE 100)

```
status_code: 0
display_id: 2
msg: "OK"
```

---

## TYPE 214 — Is Rotation Frozen

### Request

| Field      | Type   | Size    | Description      |
| ---------- | ------ | ------- | ---------------- |
| type       | uint8  | 1 byte  | `214`            |
| sequence   | int64  | 8 bytes | 请求序列号       |
| display_id | int32  | 4 bytes | 目标显示器 ID    |

### Response (TYPE 100)

```
status_code: 0
display_id: 2
msg: "1"          # "1" 已冻结，"0" 未冻结
```

---

## Kotlin Example

```kotlin
val rot = controlApi.getRotation(displayId).getOrThrow()           // Int 0-3
controlApi.freezeRotation(displayId, 1).getOrThrow()
val frozen = controlApi.isRotationFrozen(displayId).getOrThrow()   // Boolean
controlApi.thawRotation(displayId).getOrThrow()
```

## Python Example

```python
r = client.get_rotation(did);            rot = int(r["msg"])
client.freeze_rotation(did, 1)
f = client.is_rotation_frozen(did);      frozen = int(f["msg"]) == 1
client.thaw_rotation(did)
```

## Notes

- **与虚拟显示器内部策略的交互**：`VirtualDisplayRegistry` 在创建/调整大小（resize）时会自动把虚拟显示器冻结到 `ROTATION_0`，在释放时解冻。因此客户端发起的 freeze/thaw 与该内部策略叠加：
  - 创建后 `is_rotation_frozen` 通常为 `1`，`get_rotation` 为 `0`。
  - 客户端 `freeze_rotation(did, 1)` 后，`get_rotation` 应返回 `1`。
  - 随后若发生 `resize`，内部会再次冻结到 `ROTATION_0`，覆盖客户端设置的旋转。
- **创建时冻结的竞态处理**：新建虚拟显示器后立即调用 `freezeRotation` 可能因 WindowManager 尚未注册该显示器而静默失败。`VirtualDisplayRegistry` 内部通过 `freezeRotationWithRetry()` 进行最多 5 次重试验证（调用 `isRotationFrozen` 确认），以桥接此注册竞态。
- 虚拟显示器无传感器，`thaw` 之后实际旋转通常保持不变；`thaw` 主要语义是解除“冻结”状态标志（`is_rotation_frozen` 变为 `0`）。
- 本期**未实现**旋转变化的推送通知（需要新增设备消息类型与监听器生命周期）。如需感知旋转变化，客户端可定期调用 `get_rotation`，或结合 [Get Active Display Infos](get-active-display-infos.md)（TYPE 215）轮询 `rotation` 字段。

## Source

- [DaemonControlMessages.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonControlMessages.java) — 常量 `TYPE_GET_ROTATION=211` / `TYPE_FREEZE_ROTATION=212` / `TYPE_THAW_ROTATION=213` / `TYPE_IS_ROTATION_FROZEN=214`
- [DaemonControlMessageReader.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonControlMessageReader.java) — 211-214 解析
- [DaemonCommandHandler.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/control/DaemonCommandHandler.java) — 211-214 handler
- [RotationController.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/daemon/display/RotationController.java) — 旋转逻辑隔离
- [WindowManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/wrappers/WindowManager.java) — `freezeRotation` / `thawRotation` / `isRotationFrozen`
- [DisplayManager.java](file:///home/ynk/AndroidStudioProjects/VirtualDisplay/scrcpy/server/src/main/java/com/genymobile/scrcpy/wrappers/DisplayManager.java) — `getDisplayInfo(displayId).rotation`
