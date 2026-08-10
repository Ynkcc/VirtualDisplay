package com.ynk.virtualdisplay.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.Choreographer
import android.os.HandlerThread
import android.os.Handler
import com.ynk.virtualdisplay.decoder.VideoDecoderTuning
import com.ynk.virtualdisplay.util.ExceptionUtils
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264StreamDecoder(
    private val videoStream: InputStream,
    width: Int,
    height: Int,
    private val frameReader: ScrcpyFrameReader,
    private val tracker: PerformanceTracker
) {
    companion object {
        private const val TAG = "H264StreamDecoder"
        private const val VIDEO_MIME = "video/avc"
    }

    @Volatile private var width: Int = width
    @Volatile private var height: Int = height
    @Volatile private var targetSurface: Surface? = null
    @Volatile private var codec: MediaCodec? = null

    @Volatile private var dummySurfaceTexture: SurfaceTexture? = null
    @Volatile private var dummySurface: Surface? = null
    @Volatile private var isUsingDummySurface: Boolean = false

    private var inputWorker: Thread? = null
    private var outputWorker: Thread? = null
    private val running = AtomicBoolean(false)
    private val codecLock = Any()

    var onVideoConfig: ((codecLabel: String, width: Int, height: Int) -> Unit)? = null
    var ultraLowLatency: Boolean = false

    @Volatile private var renderScheduler: SurfaceRenderScheduler? = null

    init {
        tracker.actualWidth = width
        tracker.actualHeight = height
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

    fun setDisplaySurface(surface: Surface?) {
        val isDummy = surface == null || !surface.isValid
        isUsingDummySurface = isDummy
        val target = if (!isDummy) surface else getDummySurface()
        Log.i(TAG, "setDisplaySurface: surface=$surface valid=${surface?.isValid} isDummy=$isDummy codec=${codec != null}")
        synchronized(codecLock) {
            targetSurface = target
            val activeCodec = codec
            if (activeCodec != null) {
                try {
                    activeCodec.setOutputSurface(target)
                    Log.i(TAG, "setOutputSurface OK: switched to new surface")
                } catch (e: Exception) {
                    Log.w(TAG, "setOutputSurface failed, rebuilding codec", e)
                    rebuildCodec()
                }
            }
        }
    }

    fun updateResolution(newWidth: Int, newHeight: Int) {
        if (width == newWidth && height == newHeight) return
        Log.i(TAG, "updateResolution: ${width}x${height} -> ${newWidth}x${newHeight} (running=${running.get()})")
        width = newWidth
        height = newHeight
        tracker.actualWidth = newWidth
        tracker.actualHeight = newHeight
        rebuildCodec()
    }

    private fun rebuildCodec() {
        synchronized(codecLock) {
            if (!running.get()) return
            // 先停 Scheduler，并**同步等待**其 doFrame/releaseFrame 全部退出 —— 防止 Scheduler
            // 仍持旧 codec 引用在 handlerThread 上执行 releaseOutputBuffer 时，我们这边
            // 已 releaseCodecInternal → native codec buffer 已被回收（use-after-free）
            renderScheduler?.stop()
            renderScheduler = null
            releaseCodecInternal()
            createCodecInternal()
            val activeCodec = codec
            if (activeCodec != null && !ultraLowLatency) {
                renderScheduler = SurfaceRenderScheduler(activeCodec).apply { start() }
            }
        }
    }

    private fun createCodecInternal() {
        synchronized(codecLock) {
            try {
                val activeSurface = targetSurface ?: getDummySurface()
                val isDummy = (targetSurface == null)
                Log.i(TAG, "createCodecInternal: ${width}x${height} surface=$activeSurface valid=${activeSurface.isValid} isDummy=$isDummy")
                val result = VideoDecoderTuning.createConfiguredDecoder(VIDEO_MIME, width, height, activeSurface)
                codec = result.codec
                Log.i(TAG, "Codec configured and started: ${width}x${height} via ${result.decoderName} [${result.appliedOptions.joinToString()}]")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create/start MediaCodec", e)
            }
        }
    }

    private fun releaseCodecInternal() {
        synchronized(codecLock) {
            val activeCodec = codec
            if (activeCodec != null) {
                // 先 codec.stop()：立刻让 input/output worker 的所有 dequeue/queue 操作失败返回，
                // 保证持 codecLock 的 worker 尽快退出临界区，不再使用 codec 实例。
                // 再 codec.release()：回收 native DirectBuffer。
                // 顺序严格 stop → release 不可调换，否则 inputWorker 正在 put 时
                // native memory 已被释放会直接 SIGSEGV（Data Abort use-after-free）。
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
    }

    fun start() {
        if (running.getAndSet(true)) return
        tracker.reset()
        createCodecInternal()
        val activeCodec = codec
        if (activeCodec == null) {
            Log.e(TAG, "Codec creation failed, aborting start")
            running.set(false)
            return
        }

        if (!ultraLowLatency) {
            renderScheduler = SurfaceRenderScheduler(activeCodec).apply { start() }
        }

        inputWorker = Thread({ runInputLoop() }, "scrcpy-input-decoder").apply { start() }
        outputWorker = Thread({ runOutputLoop() }, "scrcpy-output-decoder").apply { start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        renderScheduler?.stop()
        renderScheduler = null

        inputWorker?.interrupt()
        outputWorker?.interrupt()

        runCatching { inputWorker?.join(500) }
        runCatching { outputWorker?.join(500) }

        inputWorker = null
        outputWorker = null

        releaseCodecInternal()
        dummySurface?.release()
        dummySurface = null
        dummySurfaceTexture?.release()
        dummySurfaceTexture = null
    }

    private fun runInputLoop() {
        var framesRead = 0
        var firstFrameLogged = false
        var lastStatsLogMs = System.currentTimeMillis()
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val frame = frameReader.readNextFrame() ?: break
                if (Thread.currentThread().isInterrupted) break

                framesRead++
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Log.i(TAG, "First frame received: size=${frame.size} pts=${frame.ptsUs} isConfig=${frame.isConfig} isKeyFrame=${frame.isKeyFrame}")
                }
                tracker.recordReceived(frame.size)

                // Periodic stats log every 3s
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastStatsLogMs >= 3000) {
                    Log.i(TAG, "Input loop stats: framesRead=$framesRead in ${nowMs - lastStatsLogMs}ms, last frame size=${frame.size} isConfig=${frame.isConfig}")
                    lastStatsLogMs = nowMs
                }

                var dispatched = false
                var abort = false
                var attempts = 0
                while (!dispatched && !abort && running.get() && !Thread.currentThread().isInterrupted && attempts < 50) {
                    attempts++
                    synchronized(codecLock) {
                        val activeCodec = codec
                        if (activeCodec == null) {
                            Log.w(TAG, "Codec is null, waiting...")
                            return@synchronized
                        }

                        val inputIndex = try {
                            activeCodec.dequeueInputBuffer(0) // 非阻塞：绝不持锁等待，避免饿死 rebuildCodec
                        } catch (e: IllegalStateException) {
                            if (running.get()) Log.w(TAG, "dequeueInputBuffer failed", e)
                            abort = true
                            return@synchronized
                        }
                        if (inputIndex < 0) {
                            return@synchronized // 暂无可用缓冲，锁外退避后重试
                        }

                        val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            Log.w(TAG, "getInputBuffer returned null, codec may be in error state. Returning empty buffer and breaking.")
                            runCatching { activeCodec.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0) }
                            abort = true
                            return@synchronized
                        }

                        // 临界区：持锁期间 rebuildCodec 无法释放 codec，对 DirectByteBuffer 的 put 安全
                        inputBuffer.clear()
                        if (frame.size > inputBuffer.capacity()) {
                            Log.w(TAG, "Frame ${frame.size}B exceeds input buffer capacity ${inputBuffer.capacity()}B, discarding")
                            runCatching { activeCodec.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0) }
                            dispatched = true
                            return@synchronized
                        }
                        inputBuffer.put(frame.data, 0, frame.size)

                        if (!frame.isConfig && frame.ptsUs >= 0L) {
                            tracker.recordEnqueue(frame.ptsUs)
                        }
                        val flags = if (frame.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                        try {
                            activeCodec.queueInputBuffer(inputIndex, 0, frame.size, frame.ptsUs, flags)
                            dispatched = true
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "queueInputBuffer failed", e)
                            abort = true
                        }
                    }
                    if (!dispatched && !abort) Thread.sleep(2) // 锁外退避，避免饿死 rebuildCodec
                }
                if (!dispatched && !abort && running.get()) {
                    Log.d(TAG, "Could not acquire input buffer, discarding frame")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "IO error in input loop, stream likely closed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in input loop", e)
            ExceptionUtils.rethrowInDebug(e)
        } finally {
            Log.i(TAG, "Input loop exiting")
        }
    }

    private fun runOutputLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO) }
        val bufferInfo = MediaCodec.BufferInfo()
        var framesDecoded = 0
        var framesRendered = 0
        var firstDecodeLogged = false
        var lastStatsLogMs = System.currentTimeMillis()
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                var tryAgain = false
                var eos = false
                synchronized(codecLock) {
                    val activeCodec = codec
                    if (activeCodec == null) {
                        tryAgain = true
                        return@synchronized
                    }
                    val outputIndex = try {
                        activeCodec.dequeueOutputBuffer(bufferInfo, 0) // 非阻塞：避免持锁等待饿死 rebuildCodec
                    } catch (e: IllegalStateException) {
                        if (running.get()) Log.w(TAG, "dequeueOutputBuffer failed", e)
                        return@synchronized
                    }
                    val dequeuedAtNs = System.nanoTime()
                    when {
                        outputIndex >= 0 -> {
                            val size = bufferInfo.size
                            if (size <= 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                                runCatching { activeCodec.releaseOutputBuffer(outputIndex, false) }
                                return@synchronized
                            }

                            framesDecoded++
                            if (!firstDecodeLogged) {
                                firstDecodeLogged = true
                                Log.i(TAG, "First frame decoded: size=$size pts=${bufferInfo.presentationTimeUs} flags=${bufferInfo.flags}")
                            }

                            // update decode latency stats
                            tracker.recordDecode(bufferInfo.presentationTimeUs)

                            if (targetSurface != null && !isUsingDummySurface) {
                                framesRendered++
                                if (ultraLowLatency) {
                                    runCatching { activeCodec.releaseOutputBuffer(outputIndex, System.nanoTime()) }
                                    tracker.recordRender(dequeuedAtNs)
                                } else {
                                    renderScheduler?.offer(
                                        DecodedOutputFrame(
                                            bufferIndex = outputIndex,
                                            presentationTimeUs = bufferInfo.presentationTimeUs,
                                            flags = bufferInfo.flags,
                                            size = bufferInfo.size,
                                            dequeuedAtNs = dequeuedAtNs
                                        )
                                    )
                                }
                            } else {
                                runCatching { activeCodec.releaseOutputBuffer(outputIndex, false) }
                            }

                            // Periodic stats log every 3s
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastStatsLogMs >= 3000) {
                                Log.i(TAG, "Output loop stats: decoded=$framesDecoded rendered=$framesRendered in ${nowMs - lastStatsLogMs}ms, isDummy=$isUsingDummySurface")
                                lastStatsLogMs = nowMs
                            }

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                eos = true
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
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
                            // CRITICAL: update local width/height to the actual codec
                            // dimensions so that a subsequent updateResolution() call
                            // (e.g. from a stale resize job) becomes a no-op instead of
                            // triggering rebuildCodec(). Rebuilding the codec after the
                            // config frame (SPS/PPS) has already been consumed leaves the
                            // new codec without any codec-config → it can never decode
                            // subsequent P-frames → permanent black screen.
                            width = visibleWidth
                            height = visibleHeight
                            Log.i(TAG, "Output format changed: coded=${w}x${h} visible=${visibleWidth}x${visibleHeight}")
                            onVideoConfig?.invoke("H.264", visibleWidth, visibleHeight)
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            tryAgain = true
                        }
                    }
                }
                if (eos) {
                    Log.i(TAG, "Output EOS")
                    break
                }
                if (tryAgain) Thread.sleep(2)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Output loop error", e)
            }
        }
        Log.i(TAG, "Output loop exiting (decoded=$framesDecoded rendered=$framesRendered)")
    }

    private data class DecodedOutputFrame(
        val bufferIndex: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        val size: Int,
        val dequeuedAtNs: Long
    )

    private inner class SurfaceRenderScheduler(
        private val activeCodec: MediaCodec
    ) : Choreographer.FrameCallback {
        private val pendingFrames = java.util.ArrayDeque<DecodedOutputFrame>()
        private val queueLock = Any()
        private val handlerThread = HandlerThread(
            "scrcpy-video-vsync",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        )

        @Volatile private var handler: Handler? = null
        @Volatile private var choreographer: Choreographer? = null
        @Volatile private var stopped = false
        private var isVSyncScheduled = false
        private var lastRenderedFrameTimeNs = 0L
        private val expectedFrameDeltaNs = ((1_000_000_000L / 60f) * 8L) / 10L

        // 序列化所有 releaseOutputBuffer 调用；stop() 持此锁等待当前 releaseFrame 彻底返回后再放行，
        // 避免 rebuildCodec 流程中 stop → releaseCodecInternal 在 releaseFrame 中途
        // 直接 release native codec → releaseOutputBuffer 的 JNI 读已释放内存产生 SIGSEGV。
        private val releaseLock = Any()

        fun start() {
            val ready = java.util.concurrent.CountDownLatch(1)
            handlerThread.start()
            handler = Handler(handlerThread.looper)
            handler?.post {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                choreographer = Choreographer.getInstance()
                ready.countDown()
            }
            val initialized = ready.await(1, java.util.concurrent.TimeUnit.SECONDS)
            if (!initialized) {
                Log.e(TAG, "SurfaceRenderScheduler.start() timed out waiting for handlerThread")
            }
        }

        fun offer(frame: DecodedOutputFrame) {
            val droppedFrames = mutableListOf<DecodedOutputFrame>()
            synchronized(queueLock) {
                if (stopped) {
                    // 已 stopped：所有新入帧直接丢弃，避免 stop 后 releaseFrame 还在继续执行
                    droppedFrames += frame
                } else {
                    pendingFrames.addLast(frame)
                    while (pendingFrames.size > 2) {
                        droppedFrames += pendingFrames.removeFirst()
                    }
                    if (!isVSyncScheduled) {
                        isVSyncScheduled = true
                        handler?.post {
                            choreographer?.postFrameCallback(this)
                        }
                    }
                }
            }
            droppedFrames.forEach {
                tracker.incrementDroppedFrames()
                releaseFrame(it, render = false, frameTimeNanos = 0L)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (stopped || !running.get()) {
                isVSyncScheduled = false
                return
            }

            val actualFrameDeltaNs = frameTimeNanos - lastRenderedFrameTimeNs
            if (actualFrameDeltaNs < expectedFrameDeltaNs) {
                choreographer?.postFrameCallback(this)
                return
            }

            var nextFrame: DecodedOutputFrame? = null
            var hasMoreFrames = false

            synchronized(queueLock) {
                if (pendingFrames.isNotEmpty()) {
                    nextFrame = pendingFrames.removeFirst()
                }
                hasMoreFrames = pendingFrames.isNotEmpty()
            }

            if (nextFrame != null) {
                releaseFrame(nextFrame, render = true, frameTimeNanos = frameTimeNanos)
                lastRenderedFrameTimeNs = frameTimeNanos
            }

            synchronized(queueLock) {
                if (hasMoreFrames && !stopped) {
                    choreographer?.postFrameCallback(this)
                } else {
                    isVSyncScheduled = false
                }
            }
        }

        fun stop() {
            stopped = true
            val localHandler = handler
            if (localHandler == null) {
                runCatching { handlerThread.quitSafely() }
                // 即使未启动也要持 releaseLock 一次——保证任何已进入 releaseFrame 的调用已退出
                synchronized(releaseLock) { /* empty barrier */ }
                return
            }

            // 先 removeFrameCallback，避免停止流程中还有新的 doFrame 进来
            val removed = java.util.concurrent.CountDownLatch(1)
            localHandler.post {
                choreographer?.removeFrameCallback(this)
                removed.countDown()
            }
            removed.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)

            val finished = java.util.concurrent.CountDownLatch(1)
            localHandler.post {
                choreographer?.removeFrameCallback(this)
                val leftovers = synchronized(queueLock) {
                    isVSyncScheduled = false
                    val drained = mutableListOf<DecodedOutputFrame>()
                    while (pendingFrames.isNotEmpty()) {
                        drained += pendingFrames.removeFirst()
                    }
                    drained
                }
                leftovers.forEach {
                    tracker.incrementDroppedFrames()
                    releaseFrame(it, render = false, frameTimeNanos = 0L)
                }
                finished.countDown()
                handlerThread.quitSafely()
            }
            finished.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            handlerThread.join(500)

            // 最终屏障：同步等待所有正在执行的 releaseFrame 调用返回
            // （可能发生在 stopped=true 之前已进入 doFrame 分支但尚未退出 releaseFrame 的情况）
            synchronized(releaseLock) { /* empty barrier */ }
        }

        private fun releaseFrame(
            frame: DecodedOutputFrame,
            render: Boolean,
            frameTimeNanos: Long
        ) {
            // 持 releaseLock：保证 stop() 的 synchronized(releaseLock) 屏障能阻塞到当前调用完成
            synchronized(releaseLock) {
                if (stopped && !render) {
                    // stop 路径丢弃帧：仅做保护性记录，不与 codec 交互（可能已被 release）
                    // 但由于 stop → releaseCodecInternal 会先拿 codecLock stop() codec，
                    // releaseOutputBuffer 本身在已停止 codec 上也是安全返回错误状态（不 native crash）；
                    // 这里加 stopped 判断是双保险。
                    return
                }
                try {
                    if (render) {
                        runCatching {
                            activeCodec.releaseOutputBuffer(frame.bufferIndex, frameTimeNanos)
                        }.getOrElse {
                            runCatching {
                                activeCodec.releaseOutputBuffer(frame.bufferIndex, true)
                            }
                        }
                        tracker.recordRender(frame.dequeuedAtNs)
                    } else {
                        runCatching {
                            activeCodec.releaseOutputBuffer(frame.bufferIndex, false)
                        }
                    }
                } catch (e: Exception) {
                    // 只记录不 rethrow：异常（包括 codec 已释放）不会传播成线程未捕获崩溃
                    Log.w(TAG, "releaseFrame failed for buffer index: ${frame.bufferIndex}", e)
                }
            }
        }
    }
}
