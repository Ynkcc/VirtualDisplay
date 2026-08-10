package com.ynk.virtualdisplay.video

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

    @Volatile var actualWidth: Int = 1920
    @Volatile var actualHeight: Int = 1080

    private var bytesReceivedWindow: Long = 0
    private var windowStartMs: Long = 0
    private var receivedFramesWindow: Long = 0
    @Volatile private var currentBitrateMbps: Double = 0.0
    @Volatile private var currentReceivedFps: Double = 0.0

    @Volatile private var rttEwmaMs: Double = 0.0
    @Volatile private var networkLatencyMs: Double = 0.0
    @Volatile private var queueLatencyEwmaMs: Double = 0.0
    private val frameReceiveTimeNsByPtsUs = ConcurrentHashMap<Long, Long>()

    var onPerformanceStats: ((String) -> Unit)? = null

    private fun updateEwma(current: Double, sample: Double, alpha: Double = 0.2): Double =
        if (current == 0.0) sample else (current * (1.0 - alpha)) + (sample * alpha)

    fun recordRtt(rttMs: Double) {
        rttEwmaMs = updateEwma(rttEwmaMs, rttMs, 0.1)
        // Estimate network latency as half RTT
        networkLatencyMs = rttEwmaMs / 2.0
    }

    fun recordFrameReceived(ptsUs: Long) {
        frameReceiveTimeNsByPtsUs[ptsUs] = System.nanoTime()
    }

    fun recordEnqueue(ptsUs: Long) {
        inputEnqueueNsByPtsUs[ptsUs] = System.nanoTime()
        val receiveNs = frameReceiveTimeNsByPtsUs.get(ptsUs)
        if (receiveNs != null) {
            val queueLatency = (System.nanoTime() - receiveNs) / 1_000_000.0
            queueLatencyEwmaMs = updateEwma(queueLatencyEwmaMs, queueLatency, 0.1)
        }
    }

    fun recordDecode(ptsUs: Long) {
        val enqueueNs = inputEnqueueNsByPtsUs.remove(ptsUs)
        frameReceiveTimeNsByPtsUs.remove(ptsUs) // cleanup
        if (enqueueNs != null) {
            val latencyMs = (System.nanoTime() - enqueueNs) / 1_000_000.0
            decodeLatencyEwmaMs = updateEwma(decodeLatencyEwmaMs, latencyMs.coerceIn(0.0, 500.0), 0.1)
        }
    }

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

    fun incrementDroppedFrames() {
        droppedOutputFrames.incrementAndGet()
    }

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

    fun getStatsString(): String {
        val totalLatency = networkLatencyMs + queueLatencyEwmaMs + decodeLatencyEwmaMs + renderLatencyEwmaMs
        val congestion = (inputEnqueueNsByPtsUs.size).coerceAtLeast(0)
        return buildString {
            append("分辨率: ${actualWidth}x${actualHeight} | 帧率: %.1f FPS".format(currentFps)).appendLine()
            append("码率: %.2f Mbps".format(currentBitrateMbps)).appendLine()
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
