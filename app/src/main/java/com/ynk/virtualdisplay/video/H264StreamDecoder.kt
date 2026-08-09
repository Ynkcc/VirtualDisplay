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
        synchronized(codecLock) {
            targetSurface = target
            val activeCodec = codec
            if (activeCodec != null) {
                try {
                    activeCodec.setOutputSurface(target)
                } catch (e: Exception) {
                    Log.w(TAG, "setOutputSurface failed, rebuilding codec", e)
                    rebuildCodec()
                }
            }
        }
    }

    fun updateResolution(newWidth: Int, newHeight: Int) {
        if (width == newWidth && height == newHeight) return
        width = newWidth
        height = newHeight
        tracker.actualWidth = newWidth
        tracker.actualHeight = newHeight
        rebuildCodec()
    }

    private fun rebuildCodec() {
        synchronized(codecLock) {
            if (!running.get()) return
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
                try {
                    activeCodec.stop()
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    try {
                        activeCodec.release()
                    } catch (e: Exception) {
                        // Ignore
                    }
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
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val frame = frameReader.readNextFrame() ?: break
                if (Thread.currentThread().isInterrupted) break

                tracker.recordReceived(frame.size)

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

                val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                if (inputBuffer == null) {
                    Log.w(TAG, "getInputBuffer returned null, codec may be in error state. Returning empty buffer and breaking.")
                    runCatching { activeCodec.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0) }
                    break
                }
                inputBuffer.clear()
                inputBuffer.put(frame.data, 0, frame.size)

                if (!frame.isConfig && frame.ptsUs >= 0L) {
                    tracker.recordEnqueue(frame.ptsUs)
                }

                val flags = if (frame.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

                try {
                    activeCodec.queueInputBuffer(inputIndex, 0, frame.size, frame.ptsUs, flags)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "queueInputBuffer failed", e)
                    break
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "IO error in input loop, stream likely closed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in input loop", e)
        } finally {
            Log.i(TAG, "Input loop exiting")
        }
    }

    private fun runOutputLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO) }
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val activeCodec = codec
            if (activeCodec == null) {
                Thread.sleep(10)
                continue
            }
            try {
                val dequeueStart = System.nanoTime()
                val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)

                when {
                    outputIndex >= 0 -> {
                        val size = bufferInfo.size
                        if (size <= 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                            runCatching { activeCodec.releaseOutputBuffer(outputIndex, false) }
                            continue
                        }

                        // update decode latency stats
                        tracker.recordDecode(bufferInfo.presentationTimeUs)

                        if (targetSurface != null && !isUsingDummySurface) {
                            if (ultraLowLatency) {
                                runCatching { activeCodec.releaseOutputBuffer(outputIndex, System.nanoTime()) }
                                tracker.recordRender(System.nanoTime())
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
                        
                        tracker.actualWidth = visibleWidth
                        tracker.actualHeight = visibleHeight
                        Log.i(TAG, "Output format changed: coded=${w}x${h} visible=${visibleWidth}x${visibleHeight}")
                        onVideoConfig?.invoke("H.264", visibleWidth, visibleHeight)
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Thread.sleep(2)
                    }
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
                    tracker.incrementDroppedFrames()
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
                        activeCodec.releaseOutputBuffer(frame.bufferIndex, frameTimeNanos)
                    }.getOrElse {
                        activeCodec.releaseOutputBuffer(frame.bufferIndex, true)
                    }
                    tracker.recordRender(frame.dequeuedAtNs)
                } else {
                    activeCodec.releaseOutputBuffer(frame.bufferIndex, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "releaseFrame failed for buffer index: ${frame.bufferIndex}", e)
            }
        }
    }
}
