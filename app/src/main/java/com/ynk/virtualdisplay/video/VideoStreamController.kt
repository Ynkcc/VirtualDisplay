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

        // Always try to stop any existing server-side video stream first.
        // This handles three scenarios:
        //  1. Same-instance stop→start (decoder != null): the previous decoder
        //     is being replaced; the server's encoder must be stopped.
        //  2. Activity recreation (decoder == null): the previous Activity
        //     instance may not have called stop(), leaving the server's encoder
        //     running with videoStarted=true. Without this probe, the server
        //     would reject 209 with "already started".
        //  3. Previous stream failed on the client (e.g., Invalid packet size)
        //     but the server's encoder is still running.
        val serverHadVideo = runCatching { rpc.stopVideoStream() }
            .getOrNull()?.isSuccess == true

        // If the server had a running video stream OR we have a local decoder,
        // reconnect the video socket. Closing the old socket unblocks any stuck
        // input worker from the previous decoder (socket reads are not
        // interruptible by Thread.interrupt()), and the new socket provides a
        // clean byte stream free of stale partial-frame data.
        //
        // Reconnect BEFORE decoder.stop() so the socket close unblocks the
        // input worker first — then decoder.stop()'s join succeeds quickly
        // instead of timing out after 500ms.
        if (serverHadVideo || decoder != null) {
            Log.i(TAG, "Reconnecting video socket (serverHadVideo=$serverHadVideo, hasDecoder=${decoder != null})")
            transport.reconnectVideoSocket()?.let {
                // Brief delay to let the server's accept loop process the new
                // video socket (read role + sessionId, call bindVideoSocket)
                // before we send 209, which triggers startVideoStream to use
                // the new FD. In practice the accept loop processes in μs, but
                // a small guard delay eliminates the rare race.
                delay(50)
            } ?: run {
                Log.e(TAG, "Failed to reconnect video socket")
                return@withLock Result.failure(IOException("Video socket reconnection failed"))
            }
        }

        if (decoder != null) {
            // Stop the local decoder now that the old video socket is closed.
            // The input worker has already exited (SocketException from the
            // closed socket), so join succeeds quickly.
            decoder?.stop()
            decoder = null
            tracker = null
        }

        // 修复断层 B: 先向服务端发送 startVideoStream 信号 (209)
        // 失败作为 Result 返回，绝不 throw——避免协程未捕获异常直接崩溃进程
        val startResult = rpc.startVideoStream(displayId)
        startResult.onFailure {
            Log.e(TAG, "Failed to send startVideoStream(209) to daemon", it)
            return@withLock Result.failure(it)
        }

        // 成功建立信号后，获取视频流读取端
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
        Log.i(TAG, "VideoStreamController stopped")
    }

    fun setSurface(surface: Surface?) {
        decoder?.setDisplaySurface(surface)
    }

    fun updateResolution(w: Int, h: Int) {
        decoder?.updateResolution(w, h)
    }
}
