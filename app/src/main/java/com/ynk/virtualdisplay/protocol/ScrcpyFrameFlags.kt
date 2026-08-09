package com.ynk.virtualdisplay.protocol

internal object ScrcpyFrameFlags {
    const val FLAG_SESSION: Long = 1L shl 63
    const val FLAG_CONFIG: Long = 1L shl 62
    const val FLAG_KEY_FRAME: Long = 1L shl 61
    const val PTS_MASK: Long = 0x3FFF_FFFF_FFFF_FFFFL
}
