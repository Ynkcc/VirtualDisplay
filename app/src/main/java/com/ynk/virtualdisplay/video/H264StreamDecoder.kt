package com.ynk.virtualdisplay.video

import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264 流解码器 —— 状态机 + 主循环。
 *
 * 职责边界：
 * - 本类持有唯一的解码状态机 [DecoderState]、主循环（[handleTick]），以及只读 socket 的
 *   reader 线程（[startReader]）；
 * - codec 的创建 / 释放 / surface 切换委托给 [CodecSession]；
 * - 输出帧的 vsync 节流与队列管理委托给 [VideoRenderScheduler]。
 *
 * # 为什么要这样分（历史崩溃根因）
 * 旧实现用手写 Thread(inputWorker + outputWorker) + 内部独立 HandlerThread(SurfaceRenderScheduler)
 * + 三把锁(codecLock / releaseLock / queueLock) + Choreographer 并发调度。三线程并发热切换
 * codec，极易在「releaseOutputBuffer 仍在执行时 release native codec」产生 native Data Abort
 * （use-after-free）→ SIGSEGV。防崩溃完全依赖调用方自觉「先断 socket 再 stop」。
 *
 * # 本版本的线程模型
 * - [codecThread]：唯一解码 HandlerThread。**所有 MediaCodec 交互（create/configure/start/
 *   dequeueInputBuffer/queueInputBuffer/dequeueOutputBuffer/releaseOutputBuffer/release）以及
 *   渲染调度（Choreographer doFrame）全部 post 到这一个 looper 上串行执行**。因此任意时刻
 *   至多一个线程持有 codec 引用并在其上做 native 操作，release 与所有 buffer 操作天然互斥。
 * - [readerThread]：一个**只读 socket 的 IO 线程**。它仅调用 [ScrcpyFrameReader.readNextFrame]
 *   把原始帧塞进 [frameQueue]，**绝不触碰任何 codec / native buffer**。socket read() 阻塞无法被
 *   interrupt 打断，只能靠「断开 socket」使其抛异常退出——这正是「先断 socket」手段的意义；
 *   把它与解码分离，是为了让解码 looper 永不被阻塞 read 卡死，保证 stop() 永远能及时执行。
 *   读到的 ByteArray 数据在 queue 里与 codec 生命周期完全解耦，因此读线程的存在不引入
 *   任何 use-after-free 风险。
 *
 * # 状态机（单状态机集中管理，禁止裸 AtomicBoolean 拼状态）
 *   IDLE → CONFIGURED → STARTED → STOPPED
 *
 * 状态转换表（状态写入统一经 [stateLock] + transitionToLocked，任何状态读取用 @Volatile）：
 *   - IDLE      --createCodec 成功--> CONFIGURED   （start()）
 *   - IDLE      --createCodec 失败--> IDLE          （start() abort，上层感知失败）
 *   - CONFIGURED --start() 启动 reader+tick--> STARTED
 *   - STARTED   --stop() / EOF / 内部错误--> STOPPING → STOPPED
 *   - CONFIGURED --stop()--> STOPPING → STOPPED
 *   - STARTED   --rebuild(切换surface失败/尺寸变化)--> STARTED   （STARTED 内部动作，不退出状态）
 *   - 任意非终态 --stop()--> STOPPING → STOPPED     （终止态 STOPPED 不可逆）
 *   其中 STOPPING 是内部过渡态：进入后 reader / tick 立即停止产生任何 codec 交互，
 *   release codec 只在同一解码 looper 上、于 STOPPING→STOPPED 的收敛步内执行。
 */
class H264StreamDecoder(
    private val videoStream: InputStream,
    width: Int,
    height: Int,
    private val frameReader: ScrcpyFrameReader,
    private val tracker: PerformanceTracker
) {
    companion object {
        private const val TAG = "H264StreamDecoder"
        private const val MAX_QUEUED_FRAMES = 8
    }

    /**
     * 解码器生命周期状态机。
     * IDLE：初始/未建 codec；CONFIGURED：codec 已建好但未启动解码循环；
     * STARTED：解码循环运行中；STOPPING：停止流程进行中（内部过渡态）；
     * STOPPED：codec 已释放、终止态（不可逆）。
     */
    enum class DecoderState { IDLE, CONFIGURED, STARTED, STOPPING, STOPPED }

    /** 单一状态机实例。所有状态读取用 @Volatile，写入统一经 [transitionToLocked] 在 [stateLock] 内完成。 */
    @Volatile private var state: DecoderState = DecoderState.IDLE
    private val stateLock = Any()

    @Volatile private var width: Int = width
    @Volatile private var height: Int = height

    /** codec 输出格式确定时回调，参数为解码器标签与可见宽高。 */
    var onVideoConfig: ((codecLabel: String, width: Int, height: Int) -> Unit)? = null

    /** 超低延迟模式：输出帧不经 vsync 队列，直接以渲染时间戳 release 到 surface。 */
    var ultraLowLatency: Boolean = false

    private val codecSession = CodecSession(tracker) { label, w, h ->
        onVideoConfig?.invoke(label, w, h)
    }

    private val renderScheduler = VideoRenderScheduler(
        codecProvider = { codecSession.mediaCodec },
        tracker = tracker,
        isActive = { state == DecoderState.STARTED }
    )

    // ---- 单解码线程 ----
    private val codecThread = HandlerThread(
        "scrcpy-video-decoder",
        Process.THREAD_PRIORITY_VIDEO
    )
    private val handler: Handler

    // ---- 只读 socket 的 IO 线程（不碰 codec）----
    private var readerThread: Thread? = null
    private val frameQueue = LinkedBlockingQueue<ScrcpyFrame>(MAX_QUEUED_FRAMES)
    @Volatile private var inputFinished = false

    // ---- 主循环 tick 去重 ----
    private val tickRunnable = Runnable { handleTick() }

    private var firstFrameLogged = false
    private var firstDecodeLogged = false
    private var lastStatsLogMs = 0L

    init {
        tracker.actualWidth = width
        tracker.actualHeight = height
        codecThread.start()
        handler = Handler(codecThread.looper)
    }

    // ============================================================
    // 对外调用契约（对 VideoStreamController 不变）
    // ============================================================

    /**
     * 启动解码：创建并配置 codec，随后启动 reader 线程与解码主循环。
     * 幂等——已在运行或已终止时调用会被忽略。codec 创建失败则保持 IDLE，交由上层感知失败。
     */
    fun start() {
        synchronized(stateLock) {
            if (state != DecoderState.IDLE && state != DecoderState.CONFIGURED) {
                Log.i(TAG, "start() ignored in state=$state")
                return
            }
            tracker.reset()
            val created = runOnCodecThread {
                codecSession.create(width, height)
                codecSession.mediaCodec != null
            }
            if (!created) {
                Log.e(TAG, "Codec creation failed, aborting start")
                return // 保持 IDLE，让上层感知失败
            }
            transitionToLocked(DecoderState.CONFIGURED)
        }
        // 在解码 looper 上创建 Choreographer（其 frame callback 随后在同一 looper 回调）
        runOnCodecThread { renderScheduler.attachChoreographer() }
        startReader()
        synchronized(stateLock) { transitionToLocked(DecoderState.STARTED) }
        postTick(0)
        Log.i(TAG, "Decoder started (${width}x${height})")
    }

    /**
     * 停止解码并释放 codec。在 IDLE/STOPPED 状态下调用会被忽略。
     * 阻塞式：reader 的 socket read 阻塞需由调用方先断开 socket 才能及时返回。
     */
    fun stop() {
        val wasRunning: Boolean
        synchronized(stateLock) {
            when (state) {
                DecoderState.IDLE, DecoderState.STOPPED -> {
                    Log.i(TAG, "stop() ignored in state=$state")
                    return
                }
                else -> {
                    wasRunning = state == DecoderState.STARTED
                    // 先固化「停止中」：阻止 reader / tick 再产生任何 codec 交互
                    transitionToLocked(DecoderState.STOPPING)
                }
            }
        }
        Log.i(TAG, "stop() invoked (wasRunning=$wasRunning)")
        // 打断 reader 阻塞的 put；read 阻塞靠外部「先断 socket」使其抛异常退出
        readerThread?.interrupt()
        // 整个停止序列（含 release codec）在解码 looper 上串行执行：
        //   drain 渲染 → join reader → release codec(stop→release) → 清理 → quit
        runCatching {
            runOnCodecThread {
                stopInternalOnCodecThread()
                synchronized(stateLock) { transitionToLocked(DecoderState.STOPPED) }
                handler.removeCallbacksAndMessages(null)
                codecThread.quitSafely()
            }
        }.onFailure { Log.e(TAG, "stop() failed on codec thread", it) }
    }

    /**
     * 设置/切换渲染 surface。运行中优先直接 setOutputSurface，失败则重建 codec。
     *
     * @param surface 目标 surface；为 null 或已失效时改用内部 dummy surface。
     */
    fun setDisplaySurface(surface: Surface?) {
        if (!handler.post {
                codecSession.updateSurfaceTarget(surface)
                // 运行中且已有 codec：优先直接切换 surface，失败再重建
                if (codecSession.mediaCodec != null &&
                    state != DecoderState.STOPPED && state != DecoderState.STOPPING
                ) {
                    if (!codecSession.trySwitchOutputSurface()) rebuildCodec()
                }
            }) {
            Log.w(TAG, "setDisplaySurface dropped: codec thread already quitting")
        }
    }

    /**
     * 更新解码分辨率。若尺寸未变化则为 no-op；运行中更新会触发 codec 重建。
     *
     * @param newWidth  新宽度。
     * @param newHeight 新高度。
     */
    fun updateResolution(newWidth: Int, newHeight: Int) {
        if (width == newWidth && height == newHeight) return
        Log.i(TAG, "updateResolution: ${width}x${height} -> ${newWidth}x${newHeight} (state=$state)")
        if (!handler.post {
                width = newWidth
                height = newHeight
                tracker.actualWidth = newWidth
                tracker.actualHeight = newHeight
                if (state == DecoderState.STARTED) {
                    rebuildCodec()
                }
            }) {
            Log.w(TAG, "updateResolution dropped: codec thread already quitting")
        }
    }

    // ============================================================
    // 状态机辅助
    // ============================================================

    /** 在 [stateLock] 内执行的状态写入，带过渡日志。调用方须已持有 [stateLock]。 */
    private fun transitionToLocked(newState: DecoderState) {
        val old = state
        state = newState
        Log.d(TAG, "state: $old -> $newState")
    }

    /** 把一个同步代码块 post 到解码 looper 并阻塞等待其完成返回。 */
    private fun <T> runOnCodecThread(block: () -> T): T {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Any?>(1)
        val error = arrayOfNulls<Throwable>(1)
        val posted = handler.post {
            try {
                result[0] = block()
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            throw IllegalStateException("Codec thread already quitting")
        }
        latch.await(5, TimeUnit.SECONDS)
        error[0]?.let { throw RuntimeException("Codec thread task failed", it) }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    // ============================================================
    // IO reader 线程（只读 socket，不碰 codec）
    // ============================================================

    private fun startReader() {
        inputFinished = false
        readerThread = Thread({
            try {
                while (state == DecoderState.STARTED ||
                    state == DecoderState.CONFIGURED ||
                    state == DecoderState.IDLE
                ) {
                    // 阻塞 read：正常情况下由外部「先断 socket」使其抛 IOException 退出，
                    // 这是保证 stop 不卡死的前提（与旧实现一致，且此处已固化在停止序列里）。
                    val frame = frameReader.readNextFrame() ?: break
                    frameQueue.put(frame) // 队列满时阻塞，可被 stop() 的 interrupt 打断
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                Log.w(TAG, "IO error in video reader (stream likely closed)", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in video reader", e)
            } finally {
                inputFinished = true
                Log.i(TAG, "Video reader exiting")
            }
        }, "scrcpy-video-reader").apply { start() }
    }

    // ============================================================
    // 解码主循环（在 codecThread looper 上，消息自调度，天然串行）
    // ============================================================

    private fun postTick(delayMs: Long = 0L) {
        handler.removeCallbacks(tickRunnable)
        if (delayMs > 0) handler.postDelayed(tickRunnable, delayMs) else handler.post(tickRunnable)
    }

    /**
     * 主循环单步。通过 post 到 looper 尾部自调度，使 REBUILD / STOP 等消息能在帧间
     * 公平插入，且与 tick 严格串行（同一 looper）。
     */
    private fun handleTick() {
        if (state != DecoderState.STARTED) return // stop 后不再自调度
        val advanced = processOnce()
        if (state == DecoderState.STARTED) {
            postTick(if (advanced) 0L else 4L) // 无新帧时退避，避免空转
        }
    }

    /** 单步处理一帧输入 + 一次输出 drain。返回是否产生了实质推进。 */
    private fun processOnce(): Boolean {
        val activeCodec = codecSession.mediaCodec
        if (activeCodec == null) return false

        var advanced = false

        // 1) 消费一帧输入
        val frame = frameQueue.poll()
        if (frame != null) {
            if (!frame.isConfig && frame.ptsUs >= 0L) tracker.recordFrameReceived(frame.ptsUs)
            advanced = feedInput(activeCodec, frame) || advanced
        } else if (inputFinished && frameQueue.isEmpty() && state == DecoderState.STARTED) {
            // socket 已断 / EOF：流结束，在 looper 上执行完整停止（release codec 也在此）
            Log.i(TAG, "Video input finished, stopping decoder")
            synchronized(stateLock) { transitionToLocked(DecoderState.STOPPING) }
            stopInternalOnCodecThread()
            synchronized(stateLock) { transitionToLocked(DecoderState.STOPPED) }
            handler.removeCallbacksAndMessages(null)
            codecThread.quitSafely()
            return false
        }

        // 2) drain 输出
        val drained = drainOutput(activeCodec)
        return advanced || drained
    }

    /** 喂一帧到 codec 输入。返回是否成功入队。 */
    private fun feedInput(activeCodec: MediaCodec, frame: ScrcpyFrame): Boolean {
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i(TAG, "First frame received: size=${frame.size} pts=${frame.ptsUs} isConfig=${frame.isConfig} isKeyFrame=${frame.isKeyFrame}")
        }
        val inputIndex = try {
            activeCodec.dequeueInputBuffer(0) // 非阻塞
        } catch (e: IllegalStateException) {
            if (state == DecoderState.STARTED) Log.w(TAG, "dequeueInputBuffer failed", e)
            return false
        }
        if (inputIndex < 0) return false

        val inputBuffer = activeCodec.getInputBuffer(inputIndex)
        if (inputBuffer == null) {
            Log.w(TAG, "getInputBuffer returned null, returning empty buffer")
            runCatching { activeCodec.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0) }
            return true
        }

        inputBuffer.clear()
        if (frame.size > inputBuffer.capacity()) {
            Log.w(TAG, "Frame ${frame.size}B exceeds input buffer capacity ${inputBuffer.capacity()}B, discarding")
            runCatching { activeCodec.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0) }
            return true
        }
        inputBuffer.put(frame.data, 0, frame.size)

        tracker.recordReceived(frame.size)
        if (!frame.isConfig && frame.ptsUs >= 0L) tracker.recordEnqueue(frame.ptsUs)
        val flags = if (frame.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        return try {
            activeCodec.queueInputBuffer(inputIndex, 0, frame.size, frame.ptsUs, flags)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "queueInputBuffer failed", e)
            false
        }
    }

    /** drain 所有可用的输出缓冲。返回是否处理过任一输出。 */
    private fun drainOutput(activeCodec: MediaCodec): Boolean {
        val bufferInfo = MediaCodec.BufferInfo()
        var advanced = false
        var tryAgain = false
        do {
            val dequeuedAtNs = System.nanoTime()
            val outputIndex = try {
                activeCodec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (e: IllegalStateException) {
                if (state == DecoderState.STARTED) Log.w(TAG, "dequeueOutputBuffer failed", e)
                return advanced
            }
            when {
                outputIndex >= 0 -> {
                    advanced = true
                    handleOutputFrame(activeCodec, bufferInfo, outputIndex, dequeuedAtNs)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "Output EOS")
                        return advanced
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleFormatChanged(activeCodec)
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> tryAgain = true
            }
        } while (!tryAgain && state == DecoderState.STARTED)
        maybeLogStats()
        return advanced
    }

    private fun handleOutputFrame(
        activeCodec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        outputIndex: Int,
        dequeuedAtNs: Long
    ) {
        val size = bufferInfo.size
        if (size <= 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
            runCatching { activeCodec.releaseOutputBuffer(outputIndex, false) }
            return
        }
        if (!firstDecodeLogged) {
            firstDecodeLogged = true
            Log.i(TAG, "First frame decoded: size=$size pts=${bufferInfo.presentationTimeUs} flags=${bufferInfo.flags}")
        }
        tracker.recordDecode(bufferInfo.presentationTimeUs)

        if (codecSession.hasOutputSurface && !codecSession.usingDummySurface) {
            if (ultraLowLatency) {
                renderScheduler.renderImmediately(outputIndex, dequeuedAtNs)
            } else {
                renderScheduler.offer(
                    bufferIndex = outputIndex,
                    presentationTimeUs = bufferInfo.presentationTimeUs,
                    flags = bufferInfo.flags,
                    size = bufferInfo.size,
                    dequeuedAtNs = dequeuedAtNs
                )
            }
        } else {
            runCatching { activeCodec.releaseOutputBuffer(outputIndex, false) }
        }
    }

    private fun handleFormatChanged(activeCodec: MediaCodec) {
        val visible = codecSession.readVisibleOutputSize() ?: return
        // CRITICAL: 更新本地 width/height 为 codec 真实输出尺寸，使后续 updateResolution()
        // （来自 stale resize job）变为 no-op 而非触发 rebuildCodec —— 避免在 config 帧
        // (SPS/PPS) 已被消费后重建 codec，导致新 codec 永远解不了 P 帧 → 永久黑屏。
        width = visible.first
        height = visible.second
    }

    private fun maybeLogStats() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastStatsLogMs >= 3000) {
            lastStatsLogMs = nowMs
            Log.i(TAG, "Decoder stats: state=$state queued=${frameQueue.size} isDummy=${codecSession.usingDummySurface}")
        }
    }

    // ============================================================
    // codec 生命周期（全部只在 codecThread looper 上执行）
    // ============================================================

    /**
     * 停止路径（仅 codecThread looper 上执行）。固化顺序：
     *   ① 停止渲染调度并 drain（释放旧 codec 在途 buffer）
     *   ② 等待 reader 退出（read 阻塞靠外部「先断 socket」解除；join 超时兜底）
     *   ③ release codec（stop → release）
     *   ④ 释放 dummy surface
     */
    private fun stopInternalOnCodecThread() {
        // ① 停止渲染 & drain，释放仍在队列的 buffer
        renderScheduler.stopAndDrain()
        // ② join reader（断 socket 后应已退出；此处 join 仅清理线程资源，超时不影响安全性，
        //    因为 reader 永不触碰 codec）
        readerThread?.let { rt ->
            runCatching { rt.interrupt() }
            runCatching { rt.join(500) }
        }
        readerThread = null
        // ③ release codec
        codecSession.release()
        // ④ 释放 dummy surface
        codecSession.releaseDummySurface()
        frameQueue.clear()
    }

    /**
     * 重建 codec（setDisplaySurface 切换失败 / updateResolution 尺寸变化触发）。
     * 只在 codecThread looper 上执行：先 drain 渲染队列释放旧 codec 在途 buffer，
     * 再 stop→release 旧 codec，再 create 新 codec —— 与 tick/doFrame 严格串行。
     */
    private fun rebuildCodec() {
        if (state != DecoderState.STARTED) return
        Log.i(TAG, "rebuildCodec: ${width}x${height}")
        renderScheduler.stopAndDrain()
        codecSession.release()
        codecSession.create(width, height)
    }
}
