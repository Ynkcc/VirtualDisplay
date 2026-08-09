package com.ynk.virtualdisplay.video

import android.util.Log
import android.view.Surface
import com.ynk.virtualdisplay.net.DaemonTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

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

    suspend fun start(displayId: Int, surface: Surface?, w: Int, h: Int): Result<Unit> = mutex.withLock {
        Log.i(TAG, "Starting video stream for display $displayId...")

        // 1. 如果已有正在运行的本地解码器，先将其彻底关闭并清理
        if (decoder != null) {
            decoder?.stop()
            decoder = null
            tracker = null
        }

        // 2. 物理断开之前残留的 Scrcpy 子通道 (视频 + 控制) 以保干净
        transport.disconnectScrcpyChannels()

        // 3. 发送 startVideoStream 信令，二阶段协商
        val startResult = rpc.startVideoStream(displayId)
        startResult.onFailure {
            Log.e(TAG, "Failed to send startVideoStream(209) to daemon", it)
            return@withLock Result.failure(it)
        }

        // 4. 协商 Success，此时物理连接 ROLE_CONTROL 和 ROLE_VIDEO 通道并绑定
        val channelsConnected = transport.connectScrcpyChannels()
        if (!channelsConnected) {
            Log.e(TAG, "Failed to connect Scrcpy channels (video and control sockets)")
            return@withLock Result.failure(IOException("Scrcpy channels connection failed"))
        }

        // 5. 成功建立物理连接后，获取视频流读取端
        val videoIn = try {
            transport.videoInputStream()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to get videoInputStream from transport", t)
            return@withLock Result.failure(IllegalStateException("Video stream input unavailable", t))
        }
        if (videoIn == null) {
            val msg = "Video stream input is null after handshake"
            Log.e(TAG, msg)
            return@withLock Result.failure(IllegalStateException(msg))
        }

        val decoderResult = runCatching {
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
        }
        decoderResult.onFailure {
            Log.e(TAG, "Failed to create/start H264StreamDecoder", it)
            transport.disconnectScrcpyChannels()
            return@withLock Result.failure(it)
        }

        Log.i(TAG, "VideoStreamController started successfully for display $displayId")
        Result.success(Unit)
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

        // 物理断开 Scrcpy 控制与视频子信道，保持协商通道依然活跃
        transport.disconnectScrcpyChannels()
        Log.i(TAG, "VideoStreamController stopped")
    }

    fun setSurface(surface: Surface?) {
        decoder?.setDisplaySurface(surface)
    }

    fun updateResolution(w: Int, h: Int) {
        decoder?.updateResolution(w, h)
    }
}
