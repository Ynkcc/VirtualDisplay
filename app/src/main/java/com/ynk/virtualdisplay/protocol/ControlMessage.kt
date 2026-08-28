package com.ynk.virtualdisplay.protocol
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

sealed class ControlMessage {
    abstract val type: Int
    abstract fun encode(sequence: Long): ByteArray

    data class CreateVirtualDisplay(
        val name: String,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val flags: Int,
        // Fixed explicit field: always serialized (default -1 means "allocate new").
        val displayId: Int = -1
    ) : ControlMessage() {
        override val type: Int = TYPE_CREATE_VIRTUAL_DISPLAY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeString(name)
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
            writeInt(flags)
            writeInt(displayId)
        }
    }

    data class ReleaseVirtualDisplay(
        val displayId: Int,
        val moveTasksToDefaultDisplay: Boolean = true
    ) : ControlMessage() {
        override val type: Int = TYPE_RELEASE_VIRTUAL_DISPLAY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
            writeByte(if (moveTasksToDefaultDisplay) 1 else 0)
        }
    }

    data class ResizeVirtualDisplay(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val dpi: Int
    ) : ControlMessage() {
        override val type: Int = TYPE_RESIZE_VIRTUAL_DISPLAY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
        }
    }

    data class StartActivity(val packageName: String, val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_START_ACTIVITY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeString(packageName)
            writeInt(displayId)
        }
    }

    object GetActiveDisplayIds : ControlMessage() {
        override val type: Int = TYPE_GET_ACTIVE_DISPLAY_IDS
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {}
    }

    object ExitDaemon : ControlMessage() {
        override val type: Int = TYPE_EXIT_DAEMON
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {}
    }

    data class GetRotation(val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_GET_ROTATION
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
        }
    }

    data class FreezeRotation(val displayId: Int, val rotation: Int) : ControlMessage() {
        override val type: Int = TYPE_FREEZE_ROTATION
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
            writeInt(rotation)
        }
    }

    data class ThawRotation(val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_THAW_ROTATION
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
        }
    }

    data class IsRotationFrozen(val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_IS_ROTATION_FROZEN
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
        }
    }

    object GetActiveDisplayInfos : ControlMessage() {
        override val type: Int = TYPE_GET_ACTIVE_DISPLAY_INFOS
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {}
    }

    /**
     * TYPE_CONFIGURE_SESSION (216)：协商阶段会话配置。
     *
     * 客户端在收到 sessionId + deviceMeta 之后、打开任何 ROLE_VIDEO /
     * ROLE_CONTROL socket 之前必须先发送此消息。服务端据此进入 CONFIGURED 阶段，
     * 否则所有 role socket 会在等待 2s 后被拒绝。
     *
     * 线格式 (紧跟 1 字节 type + 8 字节 sequence 之后) — 固定显式布局，服务端不做
     * EOF 探测，所有字段必须完整发送：
     *   int32 optionsKv_len + optionsKv_bytes (UTF-8, 按行分隔的 key=value 覆写)
     *   int32 rolesMask (旧版 3-bit 掩码；0 表示允许任意 role 类型)
     *   int32 entriesCount
     *   entriesCount × (uint8 role + int32 displayId)
     *
     * 默认空 options + rolesMask=0 + entriesCount=0 允许任意 (role, displayId) 组合。
     */
    data class ConfigureSession(
        val optionsKv: String = "",
        val rolesMask: Int = 0,
        /** (role, displayId) 显式声明列表；空列表时回退到 rolesMask 判定 */
        val rolesEntries: List<Pair<Int, Int>> = emptyList()
    ) : ControlMessage() {
        override val type: Int = TYPE_CONFIGURE_SESSION
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeString(optionsKv)
            writeInt(rolesMask)
            writeInt(rolesEntries.size)
            for ((role, displayId) in rolesEntries) {
                writeByte(role and 0xFF)
                writeInt(displayId)
            }
        }
    }

    data class LaunchHome(val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_LAUNCH_HOME
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
        }
    }

    object ListApps : ControlMessage() {
        override val type: Int = TYPE_LIST_APPS
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {}
    }

    object Ping : ControlMessage() {
        override val type: Int = TYPE_PING
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {}
    }

    protected fun buildMessage(type: Int, sequence: Long, block: DataOutputStream.() -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeByte(type)
            dos.writeLong(sequence)
            dos.block()
        }
        return baos.toByteArray()
    }

    companion object {
        const val TYPE_CREATE_VIRTUAL_DISPLAY = 201
        const val TYPE_RELEASE_VIRTUAL_DISPLAY = 202
        const val TYPE_RESIZE_VIRTUAL_DISPLAY = 203
        const val TYPE_START_ACTIVITY = 204
        const val TYPE_GET_ACTIVE_DISPLAY_IDS = 205
        // 注意：TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID (206) 已从服务端移除
        // (commit b7aef962)。注入改为走 ROLE_CONTROL socket 上的 scrcpy 原生
        // ControlMessage 协议。
        // 注意：TYPE_SWITCH_DISPLAY (207) 已从服务端移除 (commit b7aef962)。
        // 多显示器架构下不再需要"切换显示器"——直接为目标 displayId 打开
        // ROLE_VIDEO socket 即可，Socket bind 时服务端自动启动推流。
        const val TYPE_EXIT_DAEMON = 208
        // 注意：TYPE_START_VIDEO_STREAM (209) / TYPE_STOP_VIDEO_STREAM (210)
        // 已从服务端移除 (commit b7aef962)。视频流生命周期现与
        // (ROLE_VIDEO, displayId) socket 绑定：绑定即自动推流，socket 关闭即停止。
        const val TYPE_GET_ROTATION = 211
        const val TYPE_FREEZE_ROTATION = 212
        const val TYPE_THAW_ROTATION = 213
        const val TYPE_IS_ROTATION_FROZEN = 214
        const val TYPE_GET_ACTIVE_DISPLAY_INFOS = 215
        const val TYPE_CONFIGURE_SESSION = 216
        const val TYPE_LAUNCH_HOME = 217
        const val TYPE_LIST_APPS = 218
        const val TYPE_PING = 219
    }
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}
