package com.ynk.virtualdisplay.protocol

import java.io.DataInputStream
import java.nio.charset.StandardCharsets

/**
 * 守护进程返回给客户端的响应消息（协商通道），由 [DeviceMessageCodec] 解码。
 */
sealed class DeviceMessage {

    /**
     * 通用响应。
     *
     * @param sequence 对应请求的序号
     * @param statusCode 状态码（0 表示成功）
     * @param displayId 关联的显示器 ID（无关时为 -1）
     * @param message 服务端返回的说明文本，无则为 null
     */
    data class GenericResponse(
        val sequence: Long,
        val statusCode: Int,
        val displayId: Int,
        val message: String?
    ) : DeviceMessage()

    /** 活跃显示器 ID 列表响应（TYPE 101）。 */
    data class ActiveDisplaysResponse(
        val sequence: Long,
        val displayIds: IntArray
    ) : DeviceMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActiveDisplaysResponse) return false
            return sequence == other.sequence && displayIds.contentEquals(other.displayIds)
        }

        override fun hashCode(): Int = 31 * sequence.hashCode() + displayIds.contentHashCode()
    }

    /**
     * [ActiveDisplayInfosResponse] 中单个显示器的元数据条目。
     *
     * @param displayId 显示器 ID
     * @param width/height 分辨率
     * @param dpi 密度
     * @param rotation 旋转角度
     * @param mirrorDisplayId 镜像的目标显示器 ID，无则为 -1
     * @param isOwned 是否由本客户端拥有
     * @param ownerUid 拥有者 UID
     * @param ownerPackage 拥有者包名，无则为 null
     */
    data class DisplayInfoEntry(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val rotation: Int,
        val mirrorDisplayId: Int = -1,
        val isOwned: Boolean = false,
        val ownerUid: Int = 0,
        val ownerPackage: String? = null
    )

    /** 活跃显示器详细信息响应（TYPE 102）。 */
    data class ActiveDisplayInfosResponse(
        val sequence: Long,
        val displays: List<DisplayInfoEntry>
    ) : DeviceMessage()

    /** [AppsListResponse] 中单个应用的条目。 */
    data class AppEntry(
        val packageName: String,
        val name: String,
        val isSystem: Boolean
    )

    /** 应用列表响应（TYPE 103）。 */
    data class AppsListResponse(
        val sequence: Long,
        val apps: List<AppEntry>
    ) : DeviceMessage()
}
