package com.ynk.virtualdisplay.net

/**
 * 一次守护进程会话的信息，由协商握手阶段获得。
 *
 * @param sessionId 服务端分配的会话 ID（4 字节 Big-Endian）
 * @param deviceName 设备名称（64 字节定长元数据，UTF-8）
 */
data class DaemonSession(val sessionId: Int, val deviceName: String)
