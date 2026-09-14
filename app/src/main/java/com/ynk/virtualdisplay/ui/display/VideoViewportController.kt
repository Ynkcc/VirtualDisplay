package com.ynk.virtualdisplay.ui.display

import android.content.pm.ActivityInfo
import android.util.Log
import android.view.View

/**
 * 视频视口控制器：统一管理视频尺寸、客户端旋转角与 Activity 朝向。
 *
 * 视频在服务端始终以原始方向渲染（VD 冻结在 ROTATION_0），是否需要在客户端坐标系中
 * 旋转完全取决于「视频宽高比」与「当前 view 宽高比」是否一致。旋转角必须在两处时机
 * 重新计算：
 *  1. [applyVideoDimensions] —— 视频尺寸变化（如应用切到横屏），此时 view 可能还是旧方向；
 *  2. [updateContentRect] —— 布局变化（Activity 旋转，configChanges 不重建）后 view 已换
 *     方向，但视频尺寸未再变化。若只在时机 1 计算，rotation 会卡在旧值导致触摸坐标永久错乱。
 */
internal class VideoViewportController(
    private val inputController: InputController,
    private val videoSurfaceView: VideoSurfaceView,
    private val rootLayout: View,
    private val requestOrientation: (Int) -> Unit
) {
    companion object {
        private const val TAG = "VideoViewport"
    }

    var width: Int = 0
        private set
    var height: Int = 0
        private set
    private var currentRotation: Int = 0

    /** 应用视频尺寸；返回尺寸是否发生了变化。 */
    fun applyVideoDimensions(width: Int, height: Int): Boolean {
        if (this.width == width && this.height == height) return false
        this.width = width
        this.height = height
        inputController.updateVideoSize(width, height)
        videoSurfaceView.setVideoSize(width, height)
        recalculateRotation()
        applyOrientation(width, height)
        return true
    }

    /** 重新评估视频旋转角度并同步到输入控制器。 */
    fun recalculateRotation() {
        if (width <= 0 || height <= 0) return
        val viewW = rootLayout.width
        val viewH = rootLayout.height
        if (viewW <= 0 || viewH <= 0) return

        val needRotate = (width > height) != (viewW > viewH)
        val newRotation = if (needRotate) 90 else 0
        if (newRotation != currentRotation) {
            Log.i(TAG, "recalculateVideoRotation: view=${viewW}x${viewH} video=${width}x${height} -> rotation=$newRotation")
            currentRotation = newRotation
            inputController.setVideoRotation(newRotation)
            inputController.getContentRect(rootLayout)
        }
    }

    /** 布局变化时调用：先重算旋转，再刷新内容区域。 */
    fun updateContentRect() {
        recalculateRotation()
        inputController.getContentRect(rootLayout)
    }

    private fun applyOrientation(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val targetOrientation = when {
            width > height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            height > width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        requestOrientation(targetOrientation)
    }
}
