package com.ynk.virtualdisplay.data

import com.ynk.virtualdisplay.util.NetUtils

/**
 * 特权模式：决定如何获得 root 权限以拉起本机 daemon。
 * - [SHIZUKU]：通过 Shizuku 服务授权；
 * - [ROOT]：通过 su 二进制；
 * - [NONE]：无特权，仅用于远程连接（不拉 daemon）。
 */
enum class PrivilegeMode {
    SHIZUKU,
    ROOT,
    NONE
}

/**
 * 一台可连接的 scrcpy daemon 服务器节点。
 *
 * @property name 节点显示名（"本机" 为特殊节点名，用于识别 local 节点）
 * @property host 主机地址（"本机" 节点实际连接地址来自全局 serverHost 配置）
 * @property port 服务端口
 * @property password 连接密码，可为空
 */
data class ServerNode(
    val name: String,
    val host: String,
    val port: Int,
    val password: String = ""
) {
    /** 是否为本机节点（本机节点可启动/停止守护进程） */
    val isLocal: Boolean get() = NetUtils.isLocalHost(host)

    /**
     * 生成节点唯一标识（host 中的 "." 替换为 "_" 并拼接端口），用作 per-node 数据隔离的 key 后缀。
     * @return 形如 `host_port` 的唯一 key
     */
    fun uniqueKey(): String {
        return "${host.replace(".", "_")}_$port"
    }

    /** 序列化为 `name|host|port|password` 字符串，用于持久化存储。 */
    fun toSerializedString(): String {
        return "$name|$host|$port|$password"
    }

    companion object {
        /**
         * 从 [toSerializedString] 的结果反序列化节点。
         * @param str 序列化字符串
         * @return 反序列化成功返回节点；字段不足或端口非法时返回 null
         */
        fun fromSerializedString(str: String): ServerNode? {
            val parts = str.split("|")
            if (parts.size < 3) return null
            val name = parts[0]
            val host = parts[1]
            val port = parts[2].toIntOrNull() ?: 27183
            val password = if (parts.size > 3) parts[3] else ""
            return ServerNode(name, host, port, password)
        }
    }
}
