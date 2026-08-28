package com.ynk.virtualdisplay.protocol

/**
 * scrcpy 视频帧头 8 字节的高位 flag 位定义。
 *
 * 帧头最高位 3 bit 用作标志（session/config/key-frame），其余 61 bit 是 PTS 时间戳。
 */
internal object ScrcpyFrameFlags {
    /** 会话标志位 */
    const val FLAG_SESSION: Long = 1L shl 63
    /** 配置标志位 */
    const val FLAG_CONFIG: Long = 1L shl 62
    /** 关键帧标志位 */
    const val FLAG_KEY_FRAME: Long = 1L shl 61
    /** 低 61 位掩码，用于取出 PTS */
    const val PTS_MASK: Long = 0x3FFF_FFFF_FFFF_FFFFL
}
