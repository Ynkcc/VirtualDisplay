package com.ynk.virtualdisplay.video

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.ynk.virtualdisplay.decoder.VideoDecoderTuning

/**
 * 单个 MediaCodec 会话：负责 codec 的创建 / 释放 / 输出 surface 切换，以及
 * 无有效渲染目标时的 dummy surface 管理。
 *
 * ### 线程契约
 * 全部方法只允许在 [H264StreamDecoder] 的解码 looper 上调用：
 * - codec 创建 / 释放 / 重建必须与所有 dequeue / queue / releaseOutputBuffer 操作串行，
 *   这是杜绝 native use-after-free（Data Abort）的结构性保证；
 * - surface 切换（setOutputSurface）同样只在解码 looper 上执行。
 */
internal class CodecSession(
    private val tracker: PerformanceTracker,
    private val onVideoConfig: (codecLabel: String, width: Int, height: Int) -> Unit
) {
    companion object {
        private const val TAG = "CodecSession"
        private const val VIDEO_MIME = "video/avc"
    }

    @Volatile private var codec: MediaCodec? = null
    private var targetSurface: Surface? = null
    private var isUsingDummySurface: Boolean = false
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    /** 当前活动的 codec，未创建或已释放时为 null。 */
    val mediaCodec: MediaCodec? get() = codec

    /** 当前是否使用内部 dummy surface 作为输出目标。 */
    val usingDummySurface: Boolean get() = isUsingDummySurface

    /** 当前是否存在有效的真实输出 surface。 */
    val hasOutputSurface: Boolean get() = targetSurface != null

    /** 创建并配置 codec；失败时 [mediaCodec] 保持 null，由调用方感知失败。 */
    fun create(width: Int, height: Int) {
        try {
            val activeSurface = targetSurface ?: getDummySurface()
            val isDummy = (targetSurface == null)
            Log.i(TAG, "createCodecInternal: ${width}x${height} surface=$activeSurface valid=${activeSurface.isValid} isDummy=$isDummy")
            val result = VideoDecoderTuning.createConfiguredDecoder(VIDEO_MIME, width, height, activeSurface)
            codec = result.codec
            tracker.decodeMode = if (result.isHardware) "硬件" else "软件"
            Log.i(TAG, "Codec configured and started: ${width}x${height} via ${result.decoderName} [${result.appliedOptions.joinToString()}]")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create/start MediaCodec", e)
        }
    }

    /**
     * release codec。顺序严格 stop → release 不可调换：先 codec.stop() 让所有
     * dequeue/queue 立即失败返回，再 codec.release() 回收 native DirectBuffer。
     */
    fun release() {
        val activeCodec = codec
        if (activeCodec != null) {
            try {
                activeCodec.stop()
            } catch (e: Exception) {
                Log.d(TAG, "codec.stop() failed during release", e)
            }
            try {
                activeCodec.release()
            } catch (e: Exception) {
                Log.d(TAG, "codec.release() failed during release", e)
            }
            codec = null
            Log.i(TAG, "Codec released")
        }
    }

    /** 记录新的渲染 surface 目标（null 或已失效时回退到 dummy surface）。 */
    fun updateSurfaceTarget(surface: Surface?) {
        val isDummy = surface == null || !surface.isValid
        isUsingDummySurface = isDummy
        targetSurface = if (!isDummy) surface else getDummySurface()
        Log.i(TAG, "setDisplaySurface: surface=$surface valid=${surface?.isValid} isDummy=$isDummy")
    }

    /**
     * 尝试把当前活动 codec 的输出切到 [updateSurfaceTarget] 记录的目标。
     * @return true 表示切换成功或当前无活动 codec；false 表示切换失败，调用方应重建 codec。
     */
    fun trySwitchOutputSurface(): Boolean {
        val activeCodec = codec ?: return true
        val target = targetSurface ?: return true
        return try {
            activeCodec.setOutputSurface(target)
            Log.i(TAG, "setOutputSurface OK: switched to new surface")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setOutputSurface failed, rebuilding codec", e)
            false
        }
    }

    /**
     * 读取 codec 输出格式的可见尺寸并上报配置回调。
     * @return 可见 (width, height)；当前无 codec 时返回 null
     */
    fun readVisibleOutputSize(): Pair<Int, Int>? {
        val activeCodec = codec ?: return null
        val format = activeCodec.outputFormat
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)

        val visibleWidth = if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        } else {
            w
        }
        val visibleHeight = if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        } else {
            h
        }

        tracker.actualWidth = visibleWidth
        tracker.actualHeight = visibleHeight
        Log.i(TAG, "Output format changed: coded=${w}x${h} visible=${visibleWidth}x${visibleHeight}")
        onVideoConfig("H.264", visibleWidth, visibleHeight)
        return visibleWidth to visibleHeight
    }

    /** 释放 dummy surface（停止路径调用）。 */
    fun releaseDummySurface() {
        dummySurface?.release()
        dummySurface = null
        dummySurfaceTexture?.release()
        dummySurfaceTexture = null
    }

    private fun getDummySurface(): Surface {
        val ds = dummySurface
        if (ds != null && ds.isValid) {
            return ds
        }
        dummySurfaceTexture?.release()
        val st = SurfaceTexture(0)
        dummySurfaceTexture = st
        val newDs = Surface(st)
        dummySurface = newDs
        return newDs
    }
}
