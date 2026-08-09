package com.ynk.virtualdisplay.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

class DaemonTransport {
    companion object {
        private const val TAG = "DaemonTransport"
        // StartDaemon already polls the TCP port to readiness, so the first attempt
        // normally succeeds. The retry loop only kicks in for the rare race where the
        // daemon dies between probe and connect; a smaller base delay recovers faster
        // on localhost (connection-refused returns in ~10ms, not the 1s connect timeout).
        private const val RETRY_BASE_DELAY_MS = 50L
    }

    // Accessed across coroutines on Dispatchers.IO: connect()/disconnect()
    // write them while DaemonRpc's message loop and VideoStreamController read
    // them. @Volatile guarantees readers see the latest reference instead of a
    // stale cached null. Callers already tolerate a null return (and IOExceptions
    // from a concurrently-closed socket).
    @Volatile private var controlSocket: Socket? = null
    @Volatile private var videoSocket: Socket? = null
    @Volatile internal var controlIn: DataInputStream? = null
    @Volatile internal var controlOut: DataOutputStream? = null

    @Volatile
    var session: DaemonSession? = null
        private set

    fun controlInputStream(): DataInputStream? = controlIn
    fun controlOutputStream(): DataOutputStream? = controlOut
    fun videoInputStream(): InputStream? = videoSocket?.inputStream

    suspend fun connect(host: String, port: Int, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var lastException: IOException? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                disconnectInternal()

                val control = Socket()
                control.connect(InetSocketAddress(host, port), 1000)
                controlSocket = control

                // 1. Control Socket 握手
                val out = control.getOutputStream()
                val input = control.getInputStream()

                // 写 ROLE_CONTROL
                DaemonHandshake.writeRole(out, DaemonSocketRole.ROLE_CONTROL)

                // 读 sessionId
                val sessionId = DaemonHandshake.readSessionId(input)

                // 读 deviceMeta
                val deviceName = DaemonHandshake.readDeviceMeta(input)

                session = DaemonSession(sessionId, deviceName)
                Log.i(TAG, "Control socket handshake successful: sessionId=$sessionId, deviceName=$deviceName")

                controlIn = DataInputStream(input)
                controlOut = DataOutputStream(out)

                // 2. Video Socket 连接与握手
                val video = Socket()
                video.connect(InetSocketAddress(host, port), 1000)
                videoSocket = video

                val videoOut = video.getOutputStream()
                // 写 ROLE_VIDEO
                DaemonHandshake.writeRole(videoOut, DaemonSocketRole.ROLE_VIDEO)
                // 写 sessionId
                DaemonHandshake.writeSessionId(videoOut, sessionId)

                Log.i(TAG, "Video socket connected and bound to session $sessionId successfully")
                return@withContext true
            } catch (e: IOException) {
                lastException = e
                disconnectInternal()
                attempt++
                val backoff = RETRY_BASE_DELAY_MS * (1 shl (attempt - 1)).coerceAtMost(8)
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                delay(backoff.coerceAtMost(remaining))
            }
        }

        Log.e(TAG, "Failed to connect and handshake with daemon TCP $host:$port after ${timeoutMs}ms", lastException)
        false
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
        Log.i(TAG, "Disconnected")
    }

    private fun disconnectInternal() {
        runCatching { controlIn?.close() }
        runCatching { controlOut?.close() }
        runCatching { controlSocket?.close() }
        runCatching { videoSocket?.close() }
        controlIn = null
        controlOut = null
        controlSocket = null
        videoSocket = null
        session = null
    }
}
