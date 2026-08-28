package com.ynk.virtualdisplay.video

/**
 * 视频流相关默认常量。
 *
 * 统一解码器/流控制器/连接槽共享的默认分辨率，避免各组件各自定义
 * `DEFAULT_WIDTH = 1920` / `DEFAULT_HEIGHT = 1080` 造成的不一致。
 */
object VideoDefaults {
    const val DEFAULT_WIDTH = 1920
    const val DEFAULT_HEIGHT = 1080
}
