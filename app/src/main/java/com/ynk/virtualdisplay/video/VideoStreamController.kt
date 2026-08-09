package com.ynk.virtualdisplay.video

import android.util.Log
import android.view.Surface
import com.ynk.virtualdisplay.net.DaemonTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VideoStreamController(
    private val rpc: VideoStreamRpc,
    private val transport: DaemonTransport,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VideoStreamController"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
    }

    private val mutex = Mutex()
    private var decoder: H264StreamDecoder? = null
    private var tracker: PerformanceTracker? = null

    var onVideoConfig: ((codecLabel: String, width: Int, height: Int) -> Unit)? = null
    var onPerformanceStats: ((String) -> Unit)? = null

    suspend fun start(displayId: Int, surface: Surface?, w: Int, h: Int) = mutex.withLock {
        Log.i(TAG, "Starting video stream for display $displayId...")

        // 若已有解码器在运行，先停止以避免资源泄漏（调用者可能未显式 stop）
        if (decoder != null) {
            Log.w(TAG, "Existing decoder found, stopping it before starting new stream")
            stopInternal()
        }

        // 修复断层 B: 先向服务端发送 startVideoStream 信号 (209)
        val result = rpc.startVideoStream(displayId)
        result.onFailure {
            Log.e(TAG, "Failed to send startVideoStream(209) to daemon", it)
            throw it
        }

        // 成功建立信号后，获取视频流读取端
        val videoIn = transport.videoInputStream() ?: throw IllegalStateException("Video stream input is null after handshake")

        val frameReader = ScrcpyFrameReader(videoIn)
        val perfTracker = PerformanceTracker().apply {
            onPerformanceStats = { stats ->
                this@VideoStreamController.onPerformanceStats?.invoke(stats)
            }
        }
        tracker = perfTracker

        val d = H264StreamDecoder(
            videoStream = videoIn,
            width = w.takeIf { it > 0 } ?: DEFAULT_WIDTH,
            height = h.takeIf { it > 0 } ?: DEFAULT_HEIGHT,
            frameReader = frameReader,
            tracker = perfTracker
        )

        d.onVideoConfig = onVideoConfig
        surface?.let { d.setDisplaySurface(it) }
        d.start()
        decoder = d
        Log.i(TAG, "VideoStreamController started successfully for display $displayId")
    }

    suspend fun stop(force: Boolean = false) = mutex.withLock {
        stopInternal(force)
    }

    private suspend fun stopInternal(force: Boolean = false) {
        Log.i(TAG, "Stopping video stream...")
        if (!force) {
            // 保持极其严谨的停止时序：先发 210 给服务端让其干净结束 SurfaceEncoder，再停客户端 decoder
            val result = rpc.stopVideoStream()
            result.onFailure {
                Log.w(TAG, "Failed to send stopVideoStream(210) to daemon, proceeding with decoder cleanup", it)
            }
        }

        decoder?.stop()
        decoder = null
        tracker = null
        Log.i(TAG, "VideoStreamController stopped")
    }

    fun setSurface(surface: Surface?) {
        decoder?.setDisplaySurface(surface)
    }

    fun updateResolution(w: Int, h: Int) {
        decoder?.updateResolution(w, h)
    }
}
