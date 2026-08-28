package com.ynk.virtualdisplay.video

import android.util.Log
import android.view.Surface
import com.ynk.virtualdisplay.net.DaemonTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 视频流控制器：编排解码链路（Scrcpy 帧读取 → H264 解码 → surface 渲染）的启动与停止。
 * 通过 [DaemonTransport] 建立/关闭视频与控制的 Scrcpy 子通道，以 [Mutex] 串行化
 * start/stop 操作，并周期性 ping 更新网络延迟统计。所有状态变更需在调用方协程上下文中执行。
 */
class VideoStreamController(
    private val transport: DaemonTransport,
    private val controlApi: com.ynk.virtualdisplay.rpc.DaemonControlApi,
    private val scope: CoroutineScope,
    private val settingsDataSource: com.ynk.virtualdisplay.data.local.AppSettingsDataSource
) {
    companion object {
        private const val TAG = "VideoStreamController"
        private const val PING_INTERVAL_MS = 2000L
    }

    private val mutex = Mutex()
    private var decoder: H264StreamDecoder? = null
    private var tracker: PerformanceTracker? = null
    private var pingJob: kotlinx.coroutines.Job? = null

    /** codec 输出格式确定时回调，参数为解码器标签与可见宽高。 */
    var onVideoConfig: ((codecLabel: String, width: Int, height: Int) -> Unit)? = null

    /** 周期性上报格式化性能统计字符串的回调。 */
    var onPerformanceStats: ((String) -> Unit)? = null

    /**
     * 启动指定 display 的视频流：关闭残留 Scrcpy 子通道，连接视频/控制 socket，
     * 创建解码器与性能跟踪器并开始渲染。幂等操作，需在协程上下文中调用。
     *
     * @param displayId 目标 display 的 id，用于服务端路由。
     * @param surface   渲染目标 surface；为 null 时内部改用 dummy surface。
     * @param w         期望视频宽度，<=0 时回退到 [VideoDefaults.DEFAULT_WIDTH]。
     * @param h         期望视频高度，<=0 时回退到 [VideoDefaults.DEFAULT_HEIGHT]。
     * @return 成功返回 [Result.success]；通道连接、获取输入流或解码器创建失败返回 [Result.failure]。
     */
    suspend fun start(displayId: Int, surface: Surface?, w: Int, h: Int): Result<Unit> = mutex.withLock {
        Log.i(TAG, "Starting video stream for display $displayId... (surface=$surface valid=${surface?.isValid} dims=${w}x${h})")

        // 1. 先关闭残留 Scrcpy 子通道 (视频 + 控制)：关闭旧 video socket 会使 decoder
        //    input worker 阻塞的 read() 抛 SocketException 退出，从而下面的
        //    decoder.stop() 的 join(500) 快速返回而非超时（旧 worker 仍持旧 codec
        //    DirectByteBuffer 引用，rebuildCodec release 后会 use-after-free）。
        //    disconnectScrcpyChannels 对首启/重启均幂等，统一此路径无需分支。
        //    b7aef962 之后服务端在 video socket 关闭时自动停止编码器、释放 VD 引用。
        transport.disconnectScrcpyChannels()

        // 2. 关闭旧解码器（socket 已断，input worker 已解除阻塞，join 不会超时）
        if (decoder != null) {
            decoder?.stop()
            decoder = null
            tracker = null
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
                width = w.takeIf { it > 0 } ?: VideoDefaults.DEFAULT_WIDTH,
                height = h.takeIf { it > 0 } ?: VideoDefaults.DEFAULT_HEIGHT,
                frameReader = frameReader,
                tracker = perfTracker
            )

            d.onVideoConfig = onVideoConfig
            d.ultraLowLatency = settingsDataSource.getUltraLowLatencySync()
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
        
        startPingLoop()
        Result.success(Unit)
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val result = controlApi.ping()
                result.onSuccess { rtt ->
                    tracker?.recordRtt(rtt.toDouble())
                }
                kotlinx.coroutines.delay(PING_INTERVAL_MS)
            }
        }
    }

    /** 停止视频流：取消 ping、断开 Scrcpy 子通道并停止/释放解码器。幂等，需在协程上下文中调用。 */
    suspend fun stop() = mutex.withLock {
        stopInternal()
    }

    private suspend fun stopInternal() {
        Log.i(TAG, "Stopping video stream...")
        pingJob?.cancel()
        pingJob = null
        // Post-b7aef962: stopVideoStream (210) removed. Video server stops
        // automatically when the ROLE_VIDEO socket closes. 先断开 Scrcpy 子信道使
        // input worker 阻塞的 read() 抛 SocketException 退出，从而下面的
        // decoder.stop() 的 join(500) 快速返回而非超时（否则 rebuildCodec 由
        // resizeDisplay/updateResolution 在主线程触发时会 release 旧 codec，而
        // worker 仍持 DirectByteBuffer 引用 → native Data Abort use-after-free）。
        transport.disconnectScrcpyChannels()

        decoder?.stop()
        decoder = null
        tracker = null

        Log.i(TAG, "VideoStreamController stopped")
    }

    /**
     * 切换渲染 surface，透传给底层解码器。
     *
     * @param surface 目标 surface；为 null 时改用内部 dummy surface。
     */
    fun setSurface(surface: Surface?) {
        decoder?.setDisplaySurface(surface)
    }

    /**
     * 更新解码分辨率，透传给底层解码器（尺寸变化时触发 codec 重建）。
     *
     * @param w 新宽度。
     * @param h 新高度。
     */
    fun updateResolution(w: Int, h: Int) {
        decoder?.updateResolution(w, h)
    }
}
