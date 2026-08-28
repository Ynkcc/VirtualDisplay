package com.ynk.virtualdisplay.video

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 视频流性能指标跟踪器。使用滑动时间窗统计帧率/码率，用 EWMA 平滑各段延迟
 * （网络、排队、解码、渲染），供诊断覆盖层展示。所有记录方法均为线程安全累加。
 */
class PerformanceTracker {
    private val frameCount = AtomicLong(0)
    private val droppedOutputFrames = AtomicLong(0)

    @Volatile private var decodeLatencyEwmaMs: Double = 0.0
    @Volatile private var dequeueWaitEwmaMs: Double = 0.0
    private val inputEnqueueNsByPtsUs = ConcurrentHashMap<Long, Long>()

    @Volatile private var renderLatencyEwmaMs = 0.0
    @Volatile private var presentIntervalEwmaMs = 0.0
    @Volatile private var pacingVarianceEwmaMs = 0.0
    @Volatile private var lastPresentNs = 0L
    @Volatile private var renderWindowStartMs = 0L
    @Volatile private var renderedFramesWindow = 0
    @Volatile private var currentFps = 0.0

    /** 当前实际渲染宽度（可由解码器输出格式回调更新）。 */
    @Volatile var actualWidth: Int = 1920

    /** 当前实际渲染高度（可由解码器输出格式回调更新）。 */
    @Volatile var actualHeight: Int = 1080

    /** 当前解码模式标签（如「硬件」/「软件」），由解码器创建后写入，用于质量诊断。 */
    @Volatile var decodeMode: String = "未知"

    private var bytesReceivedWindow: Long = 0
    private var windowStartMs: Long = 0
    private var receivedFramesWindow: Long = 0
    @Volatile private var currentBitrateMbps: Double = 0.0
    @Volatile private var currentReceivedFps: Double = 0.0

    @Volatile private var rttEwmaMs: Double = 0.0
    @Volatile private var networkLatencyMs: Double = 0.0
    @Volatile private var queueLatencyEwmaMs: Double = 0.0
    private val frameReceiveTimeNsByPtsUs = ConcurrentHashMap<Long, Long>()

    /** 周期性上报格式化统计字符串的回调。 */
    var onPerformanceStats: ((String) -> Unit)? = null

    private fun updateEwma(current: Double, sample: Double, alpha: Double = 0.2): Double =
        if (current == 0.0) sample else (current * (1.0 - alpha)) + (sample * alpha)

    /**
     * 记录一次 RTT 采样，并以 RTT 一半估算网络延迟。
     *
     * @param rttMs 往返时延（毫秒）。
     */
    fun recordRtt(rttMs: Double) {
        rttEwmaMs = updateEwma(rttEwmaMs, rttMs, 0.1)
        // 以 RTT 一半估算网络延迟
        networkLatencyMs = rttEwmaMs / 2.0
    }

    /**
     * 记录一帧到达解码器的时间点，用于后续计算排队延迟。
     *
     * @param ptsUs 帧的呈现时间戳（微秒）。
     */
    fun recordFrameReceived(ptsUs: Long) {
        frameReceiveTimeNsByPtsUs[ptsUs] = System.nanoTime()
    }

    /**
     * 记录一帧入队解码的时刻，并根据接收时间点估算排队延迟。
     *
     * @param ptsUs 帧的呈现时间戳（微秒）。
     */
    fun recordEnqueue(ptsUs: Long) {
        inputEnqueueNsByPtsUs[ptsUs] = System.nanoTime()
        val receiveNs = frameReceiveTimeNsByPtsUs.get(ptsUs)
        if (receiveNs != null) {
            val queueLatency = (System.nanoTime() - receiveNs) / 1_000_000.0
            queueLatencyEwmaMs = updateEwma(queueLatencyEwmaMs, queueLatency, 0.1)
        }
    }

    /**
     * 记录一帧解码完成，据此计算解码延迟，并清理该帧的入队/接收缓存。
     *
     * @param ptsUs 帧的呈现时间戳（微秒）。
     */
    fun recordDecode(ptsUs: Long) {
        val enqueueNs = inputEnqueueNsByPtsUs.remove(ptsUs)
        frameReceiveTimeNsByPtsUs.remove(ptsUs) // cleanup
        if (enqueueNs != null) {
            val latencyMs = (System.nanoTime() - enqueueNs) / 1_000_000.0
            decodeLatencyEwmaMs = updateEwma(decodeLatencyEwmaMs, latencyMs.coerceIn(0.0, 500.0), 0.1)
        }
    }

    /**
     * 记录一帧渲染完成，更新渲染延迟、呈现间隔、抖动以及滑动窗口 FPS。
     *
     * @param dequeuedAtNs 该帧从 codec 输出队列取出的纳秒时间。
     */
    fun recordRender(dequeuedAtNs: Long) {
        val nowNs = System.nanoTime()
        val renderLatencyMs = (nowNs - dequeuedAtNs) / 1_000_000.0
        renderLatencyEwmaMs = updateEwma(renderLatencyEwmaMs, renderLatencyMs)
        if (lastPresentNs != 0L) {
            val intervalMs = (nowNs - lastPresentNs) / 1_000_000.0
            presentIntervalEwmaMs = updateEwma(presentIntervalEwmaMs, intervalMs)
            pacingVarianceEwmaMs = updateEwma(pacingVarianceEwmaMs, Math.abs(intervalMs - presentIntervalEwmaMs))
        }
        lastPresentNs = nowNs

        frameCount.incrementAndGet()
        renderedFramesWindow++
        val nowMs = nowNs / 1_000_000
        if (renderWindowStartMs == 0L) renderWindowStartMs = nowMs
        if (nowMs - renderWindowStartMs >= 1000) {
            currentFps = (renderedFramesWindow * 1000.0) / (nowMs - renderWindowStartMs)
            renderedFramesWindow = 0
            renderWindowStartMs = nowMs
        }
        updateRenderStatsDisplay()
    }

    /**
     * 记录一次网络接收，累计字节数与帧数，以秒级窗口统计码率与接收帧率。
     *
     * @param bytes 本次接收的字节数。
     */
    fun recordReceived(bytes: Int) {
        val nowMs = System.currentTimeMillis()
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
        }
        bytesReceivedWindow += bytes
        receivedFramesWindow++
        if (nowMs - windowStartMs >= 1000) {
            val durationSeconds = (nowMs - windowStartMs) / 1000.0
            currentBitrateMbps = (bytesReceivedWindow * 8.0) / (durationSeconds * 1_000_000.0)
            currentReceivedFps = receivedFramesWindow / durationSeconds
            bytesReceivedWindow = 0
            receivedFramesWindow = 0
            windowStartMs = nowMs
        }
    }

    /** 递增丢弃帧计数（渲染队列溢出或停止时丢弃的输出帧）。 */
    fun incrementDroppedFrames() {
        droppedOutputFrames.incrementAndGet()
    }

    /** 重置所有统计指标与中间状态。 */
    fun reset() {
        frameCount.set(0)
        droppedOutputFrames.set(0)
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
        windowStartMs = 0L
        receivedFramesWindow = 0
        currentBitrateMbps = 0.0
        currentReceivedFps = 0.0
    }

    /**
     * 生成一行格式化统计摘要（分辨率/帧率/码率/各段延迟/丢帧等）。
     *
     * @return 供覆盖层直接展示的统计字符串。
     */
    fun getStatsString(): String {
        val totalLatency = networkLatencyMs + queueLatencyEwmaMs + decodeLatencyEwmaMs + renderLatencyEwmaMs
        val congestion = (inputEnqueueNsByPtsUs.size).coerceAtLeast(0)
        return buildString {
            append("解码模式: $decodeMode | 分辨率: ${actualWidth}x${actualHeight}").appendLine()
            append("帧率: %.1f FPS".format(currentFps) + " | 码率: %.2f Mbps".format(currentBitrateMbps)).appendLine()
            append("总延迟: %.1f ms (网络: %.1f ms)".format(totalLatency, networkLatencyMs)).appendLine()
            append("队列/解码: %.1f / %.1f ms".format(queueLatencyEwmaMs, decodeLatencyEwmaMs)).appendLine()
            append("渲染延迟: %.1f ms | 抖动: %.1f ms".format(renderLatencyEwmaMs, pacingVarianceEwmaMs)).appendLine()
            append("阻塞窗口: $congestion 帧 | 丢帧: ${droppedOutputFrames.get()}").appendLine()
        }
    }

    private fun updateRenderStatsDisplay() {
        if (frameCount.get() % 60L == 0L) {
            onPerformanceStats?.invoke(getStatsString())
        }
    }

    /**
     * Periodic stats push for the case where frames are received but NOT rendered
     * (e.g. dummy surface, codec error, or render scheduler stalled). Without this,
     * the stats overlay stays blank during a black-screen episode, giving the user
     * no diagnostic signal.
     */
    fun pushStatsIfStale() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - (renderWindowStartMs.takeIf { it > 0 } ?: nowMs) >= 1000) {
            onPerformanceStats?.invoke(getStatsString())
        }
    }
}
