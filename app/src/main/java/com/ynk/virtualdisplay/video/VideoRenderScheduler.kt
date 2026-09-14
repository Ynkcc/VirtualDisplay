package com.ynk.virtualdisplay.video

import android.media.MediaCodec
import android.util.Log
import android.view.Choreographer
import java.util.ArrayDeque

/**
 * 解码输出帧的渲染调度器（vsync 节流 + 队列管理）。
 *
 * ### 线程契约
 * 本类**不是**线程安全的：全部方法只允许在 [H264StreamDecoder] 的解码 looper 上调用
 * （`offer` / `stopAndDrain` / `attachChoreographer` 均由外部 post 到该 looper），
 * 唯一的例外是 [renderImmediately]——它同样由解码 looper 调用，只是跳过 vsync 队列。
 * 因此内部队列与 vsync 标志无需任何同步。
 *
 * 输出缓冲的实际释放通过 [codecProvider] 委托给 [H264StreamDecoder]，保证
 * 「release 与所有 codec 操作串行于同一 looper」，从结构上杜绝 use-after-free。
 */
internal class VideoRenderScheduler(
    private val codecProvider: () -> MediaCodec?,
    private val tracker: PerformanceTracker,
    private val isActive: () -> Boolean
) {
    companion object {
        private const val TAG = "VideoRenderScheduler"
        private const val MAX_RENDER_QUEUE = 2
    }

    private data class DecodedOutputFrame(
        val bufferIndex: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        val size: Int,
        val dequeuedAtNs: Long
    )

    private val renderQueue = ArrayDeque<DecodedOutputFrame>()
    private var vsyncScheduled = false
    private var lastRenderedFrameTimeNs = 0L
    private val expectedFrameDeltaNs = ((1_000_000_000L / 60f) * 8L) / 10L
    private var choreographer: Choreographer? = null

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        if (!isActive()) {
            vsyncScheduled = false
            return@FrameCallback
        }
        val actualFrameDeltaNs = frameTimeNanos - lastRenderedFrameTimeNs
        if (actualFrameDeltaNs < expectedFrameDeltaNs) {
            choreographer?.postFrameCallback(frameCallback) // 节流：还没到下一帧节奏
            return@FrameCallback
        }

        val next = if (renderQueue.isNotEmpty()) renderQueue.removeFirst() else null
        val hasMore = renderQueue.isNotEmpty()
        if (next != null) {
            release(next, render = true, frameTimeNanos = frameTimeNanos)
            lastRenderedFrameTimeNs = frameTimeNanos
        }
        if (hasMore && isActive()) {
            choreographer?.postFrameCallback(frameCallback)
        } else {
            vsyncScheduled = false
        }
    }

    /** 在解码 looper 上创建 Choreographer（其 frame callback 随后在同一 looper 回调）。 */
    fun attachChoreographer() {
        choreographer = Choreographer.getInstance()
    }

    /** 入队一帧等待 vsync 渲染；超过 [MAX_RENDER_QUEUE] 帧则丢最旧帧。 */
    fun offer(
        bufferIndex: Int,
        presentationTimeUs: Long,
        flags: Int,
        size: Int,
        dequeuedAtNs: Long
    ) {
        val frame = DecodedOutputFrame(bufferIndex, presentationTimeUs, flags, size, dequeuedAtNs)
        if (!isActive()) {
            release(frame, render = false, frameTimeNanos = 0L)
            return
        }
        renderQueue.addLast(frame)
        while (renderQueue.size > MAX_RENDER_QUEUE) {
            val dropped = renderQueue.removeFirst()
            tracker.incrementDroppedFrames()
            release(dropped, render = false, frameTimeNanos = 0L)
        }
        if (!vsyncScheduled) {
            vsyncScheduled = true
            choreographer?.postFrameCallback(frameCallback)
        }
    }

    /** 立即以当前时间戳释放输出缓冲（超低延迟模式，不经 vsync 队列）。 */
    fun renderImmediately(bufferIndex: Int, dequeuedAtNs: Long) {
        runCatching { codecProvider()?.releaseOutputBuffer(bufferIndex, System.nanoTime()) }
        tracker.recordRender(dequeuedAtNs)
    }

    /** 停止 vsync 调度并清空队列（停止 / 重建 codec 前调用，释放旧 codec 的在途 buffer）。 */
    fun stopAndDrain() {
        vsyncScheduled = false
        choreographer?.removeFrameCallback(frameCallback)
        while (renderQueue.isNotEmpty()) {
            val f = renderQueue.removeFirst()
            tracker.incrementDroppedFrames()
            release(f, render = false, frameTimeNanos = 0L)
        }
    }

    /** release 一个输出缓冲；异常只记录不抛出（含 codec 已释放的情况）。 */
    private fun release(frame: DecodedOutputFrame, render: Boolean, frameTimeNanos: Long) {
        try {
            if (render) {
                runCatching {
                    codecProvider()?.releaseOutputBuffer(frame.bufferIndex, frameTimeNanos)
                }.getOrElse {
                    runCatching {
                        codecProvider()?.releaseOutputBuffer(frame.bufferIndex, true)
                    }
                }
                tracker.recordRender(frame.dequeuedAtNs)
            } else {
                runCatching {
                    codecProvider()?.releaseOutputBuffer(frame.bufferIndex, false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseFrame failed for buffer index: ${frame.bufferIndex}", e)
        }
    }
}
