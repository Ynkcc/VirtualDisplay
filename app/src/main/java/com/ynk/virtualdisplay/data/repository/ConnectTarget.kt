package com.ynk.virtualdisplay.data.repository

/**
 * 建立连接 / 拉起进程时读取一次的目标快照。
 *
 * 连接存续期间修改监听配置不影响当前连接，也不会触发重连。
 */
internal data class ConnectTarget(
    val host: String,
    val port: Int,
    val password: String
)
