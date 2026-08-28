package com.ynk.virtualdisplay.net

/**
 * 守护进程 socket 角色定义。
 *
 * 每个 TCP 连接建立后，客户端需先写出一个角色字节，服务端据此把连接
 * 路由到对应的处理通道。
 */
object DaemonSocketRole {
    /** 视频流通道 */
    const val ROLE_VIDEO = 0
    /** 音频流通道 */
    const val ROLE_AUDIO = 1
    /** 控制通道（scrcpy 原生控制协议，注入输入事件） */
    const val ROLE_CONTROL = 2
    /** 协商通道（RPC 消息 + 会话建立） */
    const val ROLE_NEGOTIATION = 3
}
