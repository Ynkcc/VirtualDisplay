package com.ynk.virtualdisplay.video

import android.util.Log
import android.view.Surface
import com.ynk.virtualdisplay.net.DaemonTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class VideoStreamController(
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
        Log.i(TAG, "Starting video stream for display $displayId... (surface=$surface valid=${surface?.isValid} dims=${w}x${h})")

        // 1. 如果已有正在运行的本地解码器，先将其彻底关闭并清理
        val decoderExisted = decoder != null
        if (decoderExisted) {
            decoder?.stop()
            decoder = null
            tracker = null
        }

        // 2. 物理断开之前残留的 Scrcpy 子通道 (视频 + 控制) 以保干净
        //    注意：b7aef962 之后 TYPE_STOP_VIDEO_STREAM (210) 已不再发，
        //    服务端在 video socket 关闭时自动停止编码器、释放 VD 引用。
        //    但 reconnectVideoSocket() 必须在 decoder.stop() 之前执行：
        //    关闭旧 video socket 会使 decoder input worker 的 read() 抛
        //    SocketException 退出，这样 stop() 里的 join 就不会超时 500ms
        //    （旧 worker 仍持有旧 codec DirectByteBuffer 引用会在 rebuildCodec
        //    触发 release 后导致 use-after-free native crash）。
        if (decoderExisted) {
            transport.reconnectVideoSocket()
        } else {
            // 首次 start，先断开残留
            transport.disconnectScrcpyChannels()
        }

        // 3. (start 阶段) 不再发送 TYPE_START_VIDEO_STREAM (209) —— 新协议下
        //    绑定 ROLE_VIDEO socket 时服务端自动启动编码器推流。
        Log.i(TAG, "Pre-start hook OK (post-b7aef962: no 209 command, socket bind auto-starts stream). Connecting Scrcpy channels...")

        // 4. 打开 ROLE_CONTROL + ROLE_VIDEO socket，携带 displayId 路由，
        //    等服务端 displayId ack 后返回。socket 建立即服务端开始推流。
        val channelsConnected = transport.connectScrcpyChannels(displayId)
        if (!channelsConnected) {
            Log.e(TAG, "Failed to connect Scrcpy channels (video and control sockets) with displayId=$displayId")
            return@withLock Result.failure(IOException("Scrcpy channels connection failed (displayId=$displayId)"))
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
        Log.i(TAG, "Video input stream acquired, creating decoder (surface=$surface valid=${surface?.isValid} dims=${w}x${h})")

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
            d.ultraLowLatency = com.ynk.virtualdisplay.data.AppSettings.getUltraLowLatencySync()
            surface?.let { d.setDisplaySurface(it) }
            d.start()
            decoder = d
        }
        decoderResult.onFailure {
            Log.e(TAG, "Failed to create/start H264StreamDecoder", it)
            transport.disconnectScrcpyChannels()
            return@withLock Result.failure(it)
        }

        Log.i(TAG, "VideoStreamController started successfully for display $displayId (surface bound=${surface != null && surface.isValid})")
        Result.success(Unit)
    }

    suspend fun stop(force: Boolean = false) = mutex.withLock {
        stopInternal(force)
    }

    private suspend fun stopInternal(force: Boolean = false) {
        Log.i(TAG, "Stopping video stream...")
        if (!force) {
            // Post-b7aef962: stopVideoStream (210) removed. Video server stops
            // automatically when the ROLE_VIDEO socket closes. We proactively reconnect
            // the video socket so the input worker read() unblocks via SocketException
            // BEFORE the decoder.stop() joins on the worker thread — otherwise join(500)
            // times out and rebuildCodec (triggered by resizeDisplay /
            // updateResolution on the main thread) releases the old codec while
            // the worker still holds DirectByteBuffer refs, causing a native
            // Data Abort (use-after-free).
            val reconnectResult = runCatching { transport.reconnectVideoSocket() }
            if (reconnectResult.isFailure) {
                Log.w(TAG, "reconnectVideoSocket failed in stopInternal (proceeding with decoder stop)", reconnectResult.exceptionOrNull())
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
