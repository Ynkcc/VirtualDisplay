package com.ynk.virtualdisplay.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.Choreographer
import android.os.HandlerThread
import android.os.Handler
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264StreamDecoder(
    private val videoStream: InputStream,
    width: Int,
    height: Int
) {

    companion object {
        private const val TAG = "H264StreamDecoder"
        private const val VIDEO_MIME = "video/avc"
        private const val MAX_FRAME_SIZE = 8 * 1024 * 1024

        private const val SCRCPY_PACKET_FLAG_CONFIG = Long.MIN_VALUE
        private const val SCRCPY_PACKET_PTS_MASK = 0x3FFF_FFFF_FFFF_FFFFL
    }

    @Volatile
    private var width: Int = width

    @Volatile
    private var height: Int = height

    @Volatile
    private var targetSurface: Surface? = null

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var dummySurfaceTexture: SurfaceTexture? = null

    @Volatile
    private var dummySurface: Surface? = null

    private var inputWorker: Thread? = null
    private var outputWorker: Thread? = null
    private val running = AtomicBoolean(false)
    private val codecLock = Any()

    // Public properties/callbacks added for rendering stats and dynamic configuration
    var onVideoConfig: ((codecLabel: String, width: Int, height: Int) -> Unit)? = null
    var onPerformanceStats: ((String) -> Unit)? = null
    var ultraLowLatency: Boolean = false

    @Volatile
    private var renderScheduler: SurfaceRenderScheduler? = null

    // Performance statistics variables
    private var frameCount: Long = 0
    private var droppedOutputFrames: Long = 0
    private var decodeLatencyEwmaMs: Double = 0.0
    private var dequeueWaitEwmaMs: Double = 0.0
    private val inputEnqueueNsByPtsUs = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private var renderLatencyEwmaMs = 0.0
    private var presentIntervalEwmaMs = 0.0
    private var pacingVarianceEwmaMs = 0.0
    private var lastPresentNs = 0L
    private var renderWindowStartMs = 0L
    private var renderedFramesWindow = 0
    private var currentFps = 0.0

    private var actualWidth: Int = width
    private var actualHeight: Int = height
    private var bytesReceivedWindow: Long = 0
    private var windowStartMs: Long = 0
    private var receivedFramesWindow: Long = 0
    private var currentBitrateMbps: Double = 0.0
    private var currentReceivedFps: Double = 0.0

    private fun updateEwma(current: Double, sample: Double, alpha: Double = 0.2): Double =
        if (current == 0.0) sample else (current * (1.0 - alpha)) + (sample * alpha)

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
        val target = if (surface != null && surface.isValid) surface else getDummySurface()
        targetSurface = target
        val activeCodec = codec
        if (activeCodec != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    activeCodec.setOutputSurface(target)
                }.onFailure { e ->
                    Log.w(TAG, "setOutputSurface failed, will rebuild codec", e)
                    synchronized(codecLock) {
                        if (codec === activeCodec) {
                            rebuildCodec()
                        }
                    }
                }
            } else {
                Log.w(TAG, "API < 23, rebuilding codec on surface change")
                synchronized(codecLock) {
                    if (codec === activeCodec) {
                        rebuildCodec()
                    }
                }
            }
        }
    }

    fun updateResolution(width: Int, height: Int) {
        synchronized(codecLock) {
            if (this.width == width && this.height == height) return
            Log.i(TAG, "Resolution updated: ${this.width}x${this.height} -> ${width}x${height}")
            this.width = width
            this.height = height
            rebuildCodec()
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Decoder already running")
            return
        }

        // Reset stats
        frameCount = 0
        droppedOutputFrames = 0
        decodeLatencyEwmaMs = 0.0
        dequeueWaitEwmaMs = 0.0
        inputEnqueueNsByPtsUs.clear()
        renderLatencyEwmaMs = 0.0
        presentIntervalEwmaMs = 0.0
        pacingVarianceEwmaMs = 0.0
        lastPresentNs = 0L
        renderWindowStartMs = 0L
        renderedFramesWindow = 0
        currentFps = 0.0
        bytesReceivedWindow = 0
        windowStartMs = 0
        receivedFramesWindow = 0
        currentBitrateMbps = 0.0
        currentReceivedFps = 0.0

        try {
            val created = createCodecInternal()
            if (!created) {
                Log.e(TAG, "Failed to initialize codec, aborting start")
                running.set(false)
                return
            }
            if (!ultraLowLatency) {
                codec?.let { activeCodec ->
                    renderScheduler = SurfaceRenderScheduler(activeCodec).apply { start() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during codec creation", e)
            running.set(false)
            return
        }

        inputWorker = Thread(::runInputLoop, "h264-decoder-input").apply {
            isDaemon = true
            start()
        }
        outputWorker = Thread(::runOutputLoop, "h264-decoder-output").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "H264 decoder started (${width}x${height})")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            Log.w(TAG, "Decoder not running")
            return
        }
        inputWorker?.interrupt()
        outputWorker?.interrupt()
        renderScheduler?.stop()
        renderScheduler = null
        inputWorker?.join(1500)
        outputWorker?.join(1500)
        inputWorker = null
        outputWorker = null
        releaseCodecInternal()
        dummySurface?.release()
        dummySurface = null
        dummySurfaceTexture?.release()
        dummySurfaceTexture = null
        Log.i(TAG, "H264 decoder stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun createCodecInternal(): Boolean {
        val surface = targetSurface ?: getDummySurface()
        val result = VideoDecoderTuning.createConfiguredDecoder(
            mimeType = VIDEO_MIME,
            width = width,
            height = height,
            surface = surface,
        )
        codec = result.codec
        Log.i(TAG, "Codec ready: name=${result.decoderName} options=${result.appliedOptions.joinToString()}")
        return true
    }

    private fun rebuildCodec() {
        val old = codec
        renderScheduler?.stop()
        renderScheduler = null
        runCatching { old?.stop() }
        runCatching { old?.release() }
        codec = null
        if (!running.get()) return
        runCatching {
            val created = createCodecInternal()
            if (created && !ultraLowLatency) {
                codec?.let { activeCodec ->
                    renderScheduler = SurfaceRenderScheduler(activeCodec).apply { start() }
                }
            }
        }.onFailure {
            Log.e(TAG, "Failed to rebuild codec", it)
        }
    }

    private fun releaseCodecInternal() {
        synchronized(codecLock) {
            val c = codec ?: return
            runCatching { c.stop() }
            runCatching { c.release() }
            codec = null
        }
    }

    private fun runInputLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val headerBuf = ByteArray(12)
        var packetBuf: ByteArray = ByteArray(MAX_FRAME_SIZE.coerceAtMost(1024 * 1024))

        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = readExact(headerBuf, 0, 12)
                if (read != 12) {
                    if (read >= 0) Log.w(TAG, "Failed to read frame header: expected=12, got=$read")
                    break
                }

                val header = ByteBuffer.wrap(headerBuf).apply { order(java.nio.ByteOrder.BIG_ENDIAN) }
                val ptsAndFlags = header.long
                val packetSize = header.int

                val isConfig = (ptsAndFlags and SCRCPY_PACKET_FLAG_CONFIG) != 0L
                val ptsUs = ptsAndFlags and SCRCPY_PACKET_PTS_MASK

                if (packetSize < 0 || packetSize > MAX_FRAME_SIZE) {
                    Log.w(TAG, "Invalid packet size: $packetSize, skipping")
                    continue
                }
                if (packetSize == 0) continue

                if (packetSize > packetBuf.size) {
                    packetBuf = ByteArray(packetSize)
                }

                val bytesRead = readExact(packetBuf, 0, packetSize)
                if (bytesRead != packetSize) {
                    Log.w(TAG, "Truncated frame: expected=$packetSize, read=$bytesRead")
                    break
                }

                if (Thread.currentThread().isInterrupted) break

                // Update performance statistic window
                val nowMs = System.currentTimeMillis()
                if (windowStartMs == 0L) {
                    windowStartMs = nowMs
                }
                bytesReceivedWindow += packetSize
                receivedFramesWindow++
                if (nowMs - windowStartMs >= 1000) {
                    val durationSeconds = (nowMs - windowStartMs) / 1000.0
                    currentBitrateMbps = (bytesReceivedWindow * 8.0) / (durationSeconds * 1_000_000.0)
                    currentReceivedFps = receivedFramesWindow / durationSeconds
                    bytesReceivedWindow = 0
                    receivedFramesWindow = 0
                    windowStartMs = nowMs
                }

                val activeCodec = codec
                if (activeCodec == null) {
                    Log.w(TAG, "Codec is null, waiting...")
                    Thread.sleep(100)
                    continue
                }

                var inputIndex = -1
                var attempts = 0
                while (inputIndex < 0 && running.get() && !Thread.currentThread().isInterrupted && attempts < 50) {
                    try {
                        inputIndex = activeCodec.dequeueInputBuffer(10_000)
                    } catch (e: IllegalStateException) {
                        if (running.get()) Log.w(TAG, "dequeueInputBuffer failed", e)
                        break
                    }
                    attempts++
                }

                if (inputIndex < 0) {
                    Log.d(TAG, "Could not acquire input buffer, discarding frame")
                    continue
                }

                val inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: continue
                inputBuffer.clear()
                inputBuffer.put(packetBuf, 0, packetSize)

                if (!isConfig && ptsUs >= 0) {
                    inputEnqueueNsByPtsUs[ptsUs] = System.nanoTime()
                }

                val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

                try {
                    activeCodec.queueInputBuffer(inputIndex, 0, packetSize, ptsUs, flags)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "queueInputBuffer failed", e)
                    break
                }
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "Input loop interrupted")
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            Log.w(TAG, "IO error in input loop, stream likely closed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in input loop", e)
        } finally {
            Log.i(TAG, "Input loop exiting")
        }
    }

    private fun runOutputLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
        val bufferInfo = MediaCodec.BufferInfo()

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val activeCodec = codec ?: run {
                Thread.sleep(100)
                continue
            }
            try {
                val dequeueStart = System.nanoTime()
                val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                val waitTimeMs = (System.nanoTime() - dequeueStart) / 1_000_000.0
                dequeueWaitEwmaMs = updateEwma(dequeueWaitEwmaMs, waitTimeMs, 0.05)

                when {
                    outputIndex >= 0 -> {
                        val size = bufferInfo.size
                        if (size <= 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                            runCatching { activeCodec.releaseOutputBuffer(outputIndex, false) }
                            continue
                        }

                        // update decode latency stats
                        val enqueueNs = inputEnqueueNsByPtsUs.remove(bufferInfo.presentationTimeUs)
                        if (enqueueNs != null) {
                            val latencyMs = (System.nanoTime() - enqueueNs) / 1_000_000.0
                            decodeLatencyEwmaMs = updateEwma(decodeLatencyEwmaMs, latencyMs.coerceIn(0.0, 500.0), 0.1)
                        }

                        if (targetSurface != null) {
                            if (ultraLowLatency) {
                                runCatching { activeCodec.releaseOutputBuffer(outputIndex, System.nanoTime()) }
                                val nowNs = System.nanoTime()
                                val nowMs = nowNs / 1_000_000
                                val intervalMs = (nowNs - lastPresentNs) / 1_000_000.0
                                presentIntervalEwmaMs = updateEwma(presentIntervalEwmaMs, intervalMs)
                                pacingVarianceEwmaMs = updateEwma(pacingVarianceEwmaMs, Math.abs(intervalMs - presentIntervalEwmaMs))
                                lastPresentNs = nowNs
                                
                                frameCount++
                                renderedFramesWindow++
                                if (renderWindowStartMs == 0L) renderWindowStartMs = nowMs
                                if (nowMs - renderWindowStartMs >= 1000) {
                                    currentFps = (renderedFramesWindow * 1000.0) / (nowMs - renderWindowStartMs)
                                    renderedFramesWindow = 0
                                    renderWindowStartMs = nowMs
                                }
                                updateRenderStatsDisplay()
                            } else {
                                renderScheduler?.offer(
                                    DecodedOutputFrame(
                                        bufferIndex = outputIndex,
                                        presentationTimeUs = bufferInfo.presentationTimeUs,
                                        flags = bufferInfo.flags,
                                        size = bufferInfo.size,
                                        dequeuedAtNs = System.nanoTime()
                                    )
                                )
                            }
                        } else {
                            runCatching { activeCodec.releaseOutputBuffer(outputIndex, false) }
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "Output EOS")
                            break
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
                        
                        actualWidth = visibleWidth
                        actualHeight = visibleHeight
                        Log.i(TAG, "Output format changed: coded=${w}x${h} visible=${visibleWidth}x${visibleHeight}")
                        onVideoConfig?.invoke("H.264", visibleWidth, visibleHeight)
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Thread.sleep(2)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // old API (< 21), ignore
                    }
                    else -> break
                }
            } catch (e: IllegalStateException) {
                if (running.get()) {
                    Log.w(TAG, "dequeueOutputBuffer failed, codec may need rebuild", e)
                    Thread.sleep(50)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Output loop error", e)
            }
        }
        Log.i(TAG, "Output loop exiting")
    }

    private fun readExact(buffer: ByteArray, offset: Int, length: Int): Int {
        var totalRead = 0
        while (totalRead < length) {
            val read = videoStream.read(buffer, offset + totalRead, length - totalRead)
            if (read < 0) {
                return if (totalRead == 0) -1 else totalRead
            }
            totalRead += read
        }
        return totalRead
    }

    private fun updateRenderStatsDisplay() {
        if (frameCount % 60L == 0L) {
            val statsStr = buildString {
                append("分辨率: ${actualWidth}x${actualHeight} | 帧率: %.1f FPS".format(currentFps)).appendLine()
                append("码率: %.2f Mbps".format(currentBitrateMbps)).appendLine()
                append("解码延迟: %.1f ms".format(decodeLatencyEwmaMs)).appendLine()
                append("渲染延迟: %.1f ms".format(renderLatencyEwmaMs)).appendLine()
                append("丢帧数量: $droppedOutputFrames").appendLine()
                append("抖动: %.1f ms".format(pacingVarianceEwmaMs))
            }
            onPerformanceStats?.invoke(statsStr.trimEnd())
        }
    }

    private data class DecodedOutputFrame(
        val bufferIndex: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        val size: Int,
        val dequeuedAtNs: Long
    )

    private inner class SurfaceRenderScheduler(
        private val codec: MediaCodec
    ) : Choreographer.FrameCallback {
        private val pendingFrames = java.util.ArrayDeque<DecodedOutputFrame>()
        private val queueLock = Any()
        private val handlerThread = android.os.HandlerThread(
            "scrcpy-video-vsync",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        )

        @Volatile
        private var handler: android.os.Handler? = null

        @Volatile
        private var choreographer: Choreographer? = null

        @Volatile
        private var stopped = false
        private var isVSyncScheduled = false
        private var lastRenderedFrameTimeNs = 0L
        private val expectedFrameDeltaNs = ((1_000_000_000L / 60f) * 8L) / 10L

        fun start() {
            val ready = java.util.concurrent.CountDownLatch(1)
            handlerThread.start()
            handler = android.os.Handler(handlerThread.looper)
            handler?.post {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                choreographer = Choreographer.getInstance()
                ready.countDown()
            }
            ready.await(1, java.util.concurrent.TimeUnit.SECONDS)
        }

        fun offer(frame: DecodedOutputFrame) {
            val droppedFrames = mutableListOf<DecodedOutputFrame>()
            synchronized(queueLock) {
                pendingFrames.addLast(frame)
                while (pendingFrames.size > 2) {
                    droppedFrames += pendingFrames.removeFirst()
                }
                if (!isVSyncScheduled && !stopped) {
                    isVSyncScheduled = true
                    handler?.post {
                        choreographer?.postFrameCallback(this)
                    }
                }
            }
            droppedFrames.forEach {
                droppedOutputFrames++
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
                releaseFrame(nextFrame!!, render = true, frameTimeNanos = frameTimeNanos)
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
                return
            }

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
                    droppedOutputFrames++
                    releaseFrame(it, render = false, frameTimeNanos = 0L)
                }
                finished.countDown()
                handlerThread.quitSafely()
            }
            finished.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            handlerThread.join(500)
        }

        private fun releaseFrame(
            frame: DecodedOutputFrame,
            render: Boolean,
            frameTimeNanos: Long
        ) {
            try {
                if (render) {
                    runCatching {
                        codec.releaseOutputBuffer(frame.bufferIndex, frameTimeNanos)
                    }.getOrElse {
                        codec.releaseOutputBuffer(frame.bufferIndex, true)
                    }
                    val nowNs = System.nanoTime()
                    val renderLatencyMs = (nowNs - frame.dequeuedAtNs) / 1_000_000.0
                    renderLatencyEwmaMs = updateEwma(renderLatencyEwmaMs, renderLatencyMs)
                    val intervalMs = (nowNs - lastPresentNs) / 1_000_000.0
                    presentIntervalEwmaMs = updateEwma(presentIntervalEwmaMs, intervalMs)
                    pacingVarianceEwmaMs = updateEwma(pacingVarianceEwmaMs, Math.abs(intervalMs - presentIntervalEwmaMs))
                    lastPresentNs = nowNs
                    
                    frameCount++
                    renderedFramesWindow++
                    val nowMs = nowNs / 1_000_000
                    if (renderWindowStartMs == 0L) renderWindowStartMs = nowMs
                    if (nowMs - renderWindowStartMs >= 1000) {
                        currentFps = (renderedFramesWindow * 1000.0) / (nowMs - renderWindowStartMs)
                        renderedFramesWindow = 0
                        renderWindowStartMs = nowMs
                    }
                    updateRenderStatsDisplay()
                } else {
                    codec.releaseOutputBuffer(frame.bufferIndex, false)
                }
            } catch (error: Exception) {
                if (running.get()) {
                    Log.w(TAG, "releaseFrame failed render=$render index=${frame.bufferIndex}", error)
                }
            }
        }
    }
}
