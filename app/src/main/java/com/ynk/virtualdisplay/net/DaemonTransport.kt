package com.ynk.virtualdisplay.net

import android.util.Log
import com.ynk.virtualdisplay.protocol.ControlMessage
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.protocol.DeviceMessageCodec
import com.ynk.virtualdisplay.protocol.SequenceGenerator
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
    @Volatile private var negotiationSocket: Socket? = null
    @Volatile private var controlSocket: Socket? = null
    @Volatile private var videoSocket: Socket? = null
    @Volatile internal var negotiationIn: DataInputStream? = null
    @Volatile internal var negotiationOut: DataOutputStream? = null
    @Volatile internal var controlIn: DataInputStream? = null
    @Volatile internal var controlOut: DataOutputStream? = null

    // Stored during connect() so reconnectVideoSocket() can open a fresh socket
    // without re-handshaking the control channel.
    @Volatile private var host: String = ""
    @Volatile private var port: Int = 0
    // daemon_secret_token 认证。null 表示不发送认证。
    @Volatile private var secretToken: String? = null

    // The displayId currently routed through ROLE_VIDEO socket. b7aef962 引入
    // role socket 携带 displayId 路由：每个 role socket 必须发送 sessionId
    // 之后再发送 4B displayId，并读取服务端返回的 4B displayId ack。
    // App 当前只推一路流，此处缓存该 displayId 给 reconnectVideoSocket()
    // 和 stop→start 重连流程使用。
    @Volatile var streamingDisplayId: Int = 0

    @Volatile
    var session: DaemonSession? = null
        private set

    fun negotiationInputStream(): DataInputStream? = negotiationIn
    fun negotiationOutputStream(): DataOutputStream? = negotiationOut
    fun controlInputStream(): DataInputStream? = controlIn
    fun controlOutputStream(): DataOutputStream? = controlOut
    fun videoInputStream(): InputStream? = videoSocket?.inputStream

    // Synchronizes writes to controlOut so the RPC message loop (which reads controlIn
    // on its own thread) and the input injection path (which writes controlOut from
    // InputController's coroutine) don't interleave bytes on the same stream.
    private val controlWriteLock = Any()

    /**
     * Thread-safe write to the ROLE_CONTROL socket output stream.
     * Returns false if the control socket is not connected (video not streaming).
     */
    fun writeControlMessage(block: (DataOutputStream) -> Unit): Boolean {
        val out = controlOut ?: return false
        synchronized(controlWriteLock) {
            try {
                block(out)
                out.flush()
            } catch (_: IOException) {
                return false
            }
        }
        return true
    }

    /**
     * Close the current video socket and open a fresh one bound to
     * [streamingDisplayId]. Used after VideoStreamController stops the old
     * stream to guarantee a clean byte stream (reconnectVideoSocket prevents
     * a stale H264 parser worker from stealing the new stream's NALUs).
     *
     * b7aef962 requires every role socket transmit: ROLE(1B) + sessionId(4B BE)
     * + displayId(4B BE) and then read a 4B displayId ack from the server.
     * If the new socket's ack displayId mismatches, the operation fails
     * (routing was rejected by the server because displayId was not declared
     * or the session is not in CONFIGURED phase).
     */
    fun reconnectVideoSocket(): InputStream? {
        // Close old video socket — this unblocks any reader stuck in input.read()
        // on the old InputStream, causing it to throw SocketException and exit.
        runCatching { videoSocket?.close() }
        videoSocket = null

        val currentSession = session ?: run {
            Log.e(TAG, "reconnectVideoSocket: no active session")
            return null
        }

        return try {
            val displayId = streamingDisplayId
            val video = Socket()
            video.connect(InetSocketAddress(host, port), 1000)
            videoSocket = video

            val videoOut = video.getOutputStream()
            DaemonHandshake.writeRole(videoOut, DaemonSocketRole.ROLE_VIDEO)
            DaemonHandshake.writeSessionId(videoOut, currentSession.sessionId)
            DaemonHandshake.writeDisplayId(videoOut, displayId)
            val ack = DaemonHandshake.readInt32(video.inputStream)
            if (ack != displayId) {
                throw IOException("Video socket displayId ack mismatch: expected $displayId, server ack=$ack")
            }

            Log.i(TAG, "Video socket reconnected (session ${currentSession.sessionId}, displayId=$displayId, ack=$ack)")
            video.inputStream
        } catch (e: IOException) {
            Log.e(TAG, "reconnectVideoSocket failed", e)
            runCatching { videoSocket?.close() }
            videoSocket = null
            null
        }
    }

    /**
     * Connect the scrcpy-native ROLE_CONTROL and ROLE_VIDEO sockets.
     *
     * @param displayId the displayId the video socket should route to (b7aef962)
     */
    suspend fun connectScrcpyChannels(displayId: Int): Boolean = withContext(Dispatchers.IO) {
        val currentSession = session ?: return@withContext false
        try {
            disconnectScrcpyChannels()

            val ctrl = Socket()
            ctrl.connect(InetSocketAddress(host, port), 1000)
            controlSocket = ctrl

            val ctrlOut = ctrl.getOutputStream()
            DaemonHandshake.writeRole(ctrlOut, DaemonSocketRole.ROLE_CONTROL)
            DaemonHandshake.writeSessionId(ctrlOut, currentSession.sessionId)
            DaemonHandshake.writeDisplayId(ctrlOut, displayId)
            val ctrlAck = DaemonHandshake.readInt32(ctrl.inputStream)
            if (ctrlAck != displayId) {
                throw IOException("Control socket displayId ack mismatch: expected $displayId, server ack=$ctrlAck")
            }

            controlIn = DataInputStream(ctrl.inputStream)
            controlOut = DataOutputStream(ctrlOut)
            Log.i(TAG, "Scrcpy control channel connected (session ${currentSession.sessionId}, displayId=$displayId, ack=$ctrlAck)")

            val video = Socket()
            video.connect(InetSocketAddress(host, port), 1000)
            videoSocket = video

            val videoOut = video.getOutputStream()
            DaemonHandshake.writeRole(videoOut, DaemonSocketRole.ROLE_VIDEO)
            DaemonHandshake.writeSessionId(videoOut, currentSession.sessionId)
            DaemonHandshake.writeDisplayId(videoOut, displayId)
            val videoAck = DaemonHandshake.readInt32(video.inputStream)
            if (videoAck != displayId) {
                throw IOException("Video socket displayId ack mismatch: expected $displayId, server ack=$videoAck")
            }

            streamingDisplayId = displayId
            Log.i(TAG, "Video channel connected (session ${currentSession.sessionId}, displayId=$displayId, ack=$videoAck). Service auto-starts stream on bind.")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect Scrcpy channels", e)
            disconnectScrcpyChannels()
            false
        }
    }

    fun disconnectScrcpyChannels() {
        runCatching { controlIn?.close() }
        runCatching { controlOut?.close() }
        runCatching { controlSocket?.close() }
        runCatching { videoSocket?.close() }
        controlIn = null
        controlOut = null
        controlSocket = null
        videoSocket = null
        Log.i(TAG, "Scrcpy channels disconnected")
    }

    /**
     * Open the negotiation socket, perform the two-phase handshake, and send
     * TYPE_CONFIGURE_SESSION (216) so the server transitions to CONFIGURED
     * phase. Without step (4) the server will reject every ROLE_* socket
     * (waits 2s then closes).
     *
     * @param secretToken optional daemon_secret_token (null = no auth)
     * @param optionsKv optional newline-separated scrcpy key=value overrides
     * @param rolesMask role mask (0 = allow any role type)
     * @param rolesEntries explicit (role, displayId) declarations (empty allows any)
     */
    suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Long,
        secretToken: String? = null,
        optionsKv: String = "",
        rolesMask: Int = 0,
        rolesEntries: List<Pair<Int, Int>> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        this@DaemonTransport.host = host
        this@DaemonTransport.port = port
        this@DaemonTransport.secretToken = secretToken

        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var lastException: IOException? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                disconnectInternal()

                val negotiation = Socket()
                negotiation.connect(InetSocketAddress(host, port), 1000)
                negotiationSocket = negotiation

                // 1. Negotiation Socket 握手
                val out = negotiation.getOutputStream()
                val input = negotiation.getInputStream()

                // Step 1: write ROLE_NEGOTIATION
                DaemonHandshake.writeRole(out, DaemonSocketRole.ROLE_NEGOTIATION)

                // Step 2: read 4B sessionId (Big-Endian)
                val sessionId = DaemonHandshake.readSessionId(input)

                // Step 3: (optional) send daemon_secret_token: 4B BE length + UTF-8 bytes.
                // NOTE: a wrong / missing token will get 127.0.0.1 blacklisted for
                // the daemon's runtime — this path trusts the caller passed the
                // same token used to start the daemon (it is read from settings
                // in DaemonDisplayRepository.bindService / autoReconnect).
                if (secretToken != null && secretToken.isNotEmpty()) {
                    DaemonHandshake.writeToken(out, secretToken)
                }

                // Step 4: read 64-byte device meta
                val deviceName = DaemonHandshake.readDeviceMeta(input)

                session = DaemonSession(sessionId, deviceName)
                negotiationIn = DataInputStream(input)
                negotiationOut = DataOutputStream(out)
                Log.i(TAG, "Negotiation socket handshake successful: sessionId=$sessionId, deviceName=$deviceName, auth=${secretToken?.isNotEmpty() == true}")

                // Step 5: send TYPE_CONFIGURE_SESSION (216) so the server moves to
                // the CONFIGURED phase — otherwise any subsequent ROLE_* socket
                // will wait 2s and then be rejected.
                val cfgSeq = SequenceGenerator().next()
                val cfgBytes = ControlMessage.ConfigureSession(
                    optionsKv = optionsKv,
                    rolesMask = rolesMask,
                    rolesEntries = rolesEntries
                ).encode(cfgSeq)
                try {
                    negotiationOut!!.write(cfgBytes)
                    negotiationOut!!.flush()
                    // Wait for matching GenericResponse. DaemonRpc owns the
                    // long-running message loop which also reads from
                    // negotiationIn, so we must synchronously drain the response
                    // here BEFORE starting the loop (otherwise it'd race
                    // read-ahead and the deferred resolution would be missed).
                    // This mirrors the Python client's handshake: CONFIGURE_SESSION
                    // is part of connect(), not the runtime message flow.
                    val msg = DeviceMessageCodec.read(negotiationIn!!)
                    if (msg !is DeviceMessage.GenericResponse) {
                        throw IOException("CONFIGURE_SESSION responded with unexpected type: $msg")
                    }
                    if (msg.sequence != cfgSeq) {
                        throw IOException("CONFIGURE_SESSION sequence mismatch: sent=$cfgSeq, resp=${msg.sequence}")
                    }
                    if (msg.statusCode != 0) {
                        throw IOException("CONFIGURE_SESSION failed: status=${msg.statusCode}, msg=${msg.message}")
                    }
                    Log.i(TAG, "CONFIGURE_SESSION OK: phase=CONFIGURED, message=${msg.message}")
                } catch (e: IOException) {
                    Log.e(TAG, "CONFIGURE_SESSION failed, aborting connect", e)
                    disconnectInternal()
                    lastException = e
                    return@withContext false
                }

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
        disconnectScrcpyChannels()
        runCatching { negotiationIn?.close() }
        runCatching { negotiationOut?.close() }
        runCatching { negotiationSocket?.close() }
        negotiationIn = null
        negotiationOut = null
        negotiationSocket = null
        session = null
    }
}
