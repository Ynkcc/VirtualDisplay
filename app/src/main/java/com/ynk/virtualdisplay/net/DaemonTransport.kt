package com.ynk.virtualdisplay.net

import android.util.Log
import com.ynk.virtualdisplay.protocol.ControlMessage
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.protocol.DeviceMessageCodec
import com.ynk.virtualdisplay.protocol.SequenceGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 与守护进程之间的 TCP 传输层，负责建立/维护协商、控制、视频三类 socket，
 * 并封装握手与 scrcpy 原生通道的建立。
 */
class DaemonTransport {
    companion object {
        private const val TAG = "DaemonTransport"
        // Socket.connect() 的连接超时（毫秒）。加大以保证跨设备稳定性。
        private const val CONNECT_TIMEOUT_MS = 3000
        // socket 输入流的读超时（毫秒）。
        private const val SOCKET_READ_TIMEOUT_MS = 30_000
        // 新建角色 socket（ROLE_CONTROL / ROLE_VIDEO）后读取 displayId ack 的超时（毫秒）。
        // 防止服务端 accept 循环繁忙/卡住而不回应——若无此超时，半开 socket 会
        // 永久阻塞该协程，导致"操控"卡死。
        private const val HANDSHAKE_ACK_TIMEOUT_MS = 10_000
        // 重试的初始退避延迟。
        private const val RETRY_BASE_DELAY_MS = 100L
    }

    // 各 socket/流在 Dispatchers.IO 上的多个协程间共享：connect()/disconnect()
    // 写入，而 DaemonRpc 的消息循环与 VideoStreamController 读取。@Volatile 保证
    // 读取方能看到最新引用而非过期的缓存 null；调用方已容忍 null 返回（以及
    // 并发关闭 socket 产生的 IOException）。
    @Volatile private var negotiationSocket: Socket? = null
    @Volatile private var controlSocket: Socket? = null
    @Volatile private var videoSocket: Socket? = null
    @Volatile internal var negotiationIn: DataInputStream? = null
    @Volatile internal var negotiationOut: DataOutputStream? = null
    @Volatile internal var controlIn: DataInputStream? = null
    @Volatile internal var controlOut: DataOutputStream? = null

    // connect() 时保存，供 connectScrcpyChannels() 打开新角色 socket 而无需重新握手协商通道。
    @Volatile private var host: String = ""
    @Volatile private var port: Int = 0
    // daemon_secret_token 认证。null 表示不发送认证。
    @Volatile private var secretToken: String? = null

    @Volatile
    var session: DaemonSession? = null
        private set

    /** 协商通道的输入流，未连接时为 null。 */
    fun negotiationInputStream(): DataInputStream? = negotiationIn
    /** 协商通道的输出流，未连接时为 null。 */
    fun negotiationOutputStream(): DataOutputStream? = negotiationOut
    /** 控制通道的输入流，未连接时为 null。 */
    fun controlInputStream(): DataInputStream? = controlIn
    /** 控制通道的输出流，未连接时为 null。 */
    fun controlOutputStream(): DataOutputStream? = controlOut
    /** 视频通道的输入流，未连接时为 null。 */
    fun videoInputStream(): InputStream? = videoSocket?.inputStream

    // 同步对 controlOut 的写入，避免 RPC 消息循环（自身线程读 controlIn）与
    // 输入注入路径（InputController 协程写 controlOut）在同一流上交错字节。
    private val controlWriteLock = Any()

    /**
     * 线程安全地向 ROLE_CONTROL socket 输出流写入数据。
     *
     * @return false 表示控制 socket 未连接（视频未在流式传输）
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
     * 建立 scrcpy 原生 ROLE_CONTROL 与 ROLE_VIDEO 两个 socket。
     *
     * 先断开旧通道，再依次打开控制、视频 socket，各自写入角色/会话/显示器
     * 并读取 displayId ack 以确认服务端路由正确。
     *
     * @param displayId 视频与控制 socket 应路由到的显示器 ID (b7aef962)
     * @return true 表示两个通道均建立成功
     */
    suspend fun connectScrcpyChannels(displayId: Int): Boolean = withContext(Dispatchers.IO) {
        val currentSession = session ?: return@withContext false
        try {
            disconnectScrcpyChannels()

            val ctrl = Socket()
            ctrl.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            controlSocket = ctrl

            val ctrlOut = ctrl.getOutputStream()
            DaemonHandshake.writeRole(ctrlOut, DaemonSocketRole.ROLE_CONTROL)
            DaemonHandshake.writeSessionId(ctrlOut, currentSession.sessionId)
            DaemonHandshake.writeDisplayId(ctrlOut, displayId)
            // 用有界超时守护 ack 读取：若服务端角色分发繁忙/卡住而不回应，
            // 无此超时半开 socket 会永久阻塞"操控"。之后恢复 0（无超时），
            // 以免影响长生命周期控制读循环的服务端空闲保活。
            ctrl.soTimeout = HANDSHAKE_ACK_TIMEOUT_MS
            val ctrlAck = try {
                DaemonHandshake.readInt32(ctrl.inputStream)
            } finally {
                ctrl.soTimeout = 0
            }
            if (ctrlAck != displayId) {
                throw IOException("Control socket displayId ack mismatch: expected $displayId, server ack=$ctrlAck")
            }

            controlIn = DataInputStream(ctrl.inputStream)
            controlOut = DataOutputStream(ctrlOut)
            Log.i(TAG, "Scrcpy control channel connected (session ${currentSession.sessionId}, displayId=$displayId, ack=$ctrlAck)")

            val video = Socket()
            video.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            videoSocket = video

            val videoOut = video.getOutputStream()
            DaemonHandshake.writeRole(videoOut, DaemonSocketRole.ROLE_VIDEO)
            DaemonHandshake.writeSessionId(videoOut, currentSession.sessionId)
            DaemonHandshake.writeDisplayId(videoOut, displayId)
            // 与控制 socket 相同的有限 ack 守护；视频通道在 ack 后保持 0 超时，
            // 因为解码器会阻塞等待它。
            video.soTimeout = HANDSHAKE_ACK_TIMEOUT_MS
            val videoAck = try {
                DaemonHandshake.readInt32(video.inputStream)
            } finally {
                video.soTimeout = 0
            }
            if (videoAck != displayId) {
                throw IOException("Video socket displayId ack mismatch: expected $displayId, server ack=$videoAck")
            }

            Log.i(TAG, "Video channel connected (session ${currentSession.sessionId}, displayId=$displayId, ack=$videoAck). Service auto-starts stream on bind.")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect Scrcpy channels", e)
            disconnectScrcpyChannels()
            false
        }
    }

    /** 关闭并清空 scrcpy 原生控制/视频通道。 */
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
     * 打开协商 socket，完成两阶段握手，并发送 TYPE_CONFIGURE_SESSION (216) 使
     * 服务端进入 CONFIGURED 阶段。若不发送该消息，服务端会拒绝所有后续
     * ROLE_* socket（等待 2s 后关闭）。支持在 [timeoutMs] 内带退避地重试。
     *
     * @param host 守护进程主机地址
     * @param port 守护进程端口
     * @param timeoutMs 总重试时长上限（毫秒）
     * @param secretToken 可选的 daemon_secret_token（null = 不认证）
     * @param optionsKv 可选的换行分隔 scrcpy key=value 覆盖项
     * @param rolesMask 角色掩码（0 = 允许任意角色类型）
     * @param rolesEntries 显式的 (role, displayId) 声明（空 = 允许任意）
     * @return true 表示连接并完成握手
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
                negotiation.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                negotiation.soTimeout = SOCKET_READ_TIMEOUT_MS
                negotiationSocket = negotiation

                // 1. Negotiation Socket 握手
                val out = negotiation.getOutputStream()
                val input = negotiation.getInputStream()

                // Step 1: 写入 ROLE_NEGOTIATION
                DaemonHandshake.writeRole(out, DaemonSocketRole.ROLE_NEGOTIATION)

                // Step 2: 读取 4 字节 Big-Endian 的 sessionId
                val sessionId = DaemonHandshake.readSessionId(input)

                // Step 3:（可选）发送 daemon_secret_token：4 字节 BE 长度 + UTF-8 字节。
                // 注意：错误/缺失的 token 会使 127.0.0.1 在守护进程运行期间被拉黑——
                // 此处信任调用方传入的是启动守护进程所用的同一 token（由仓储层在
                // bindService / autoReconnect 流程中从设置读取）。
                if (secretToken != null && secretToken.isNotEmpty()) {
                    DaemonHandshake.writeToken(out, secretToken)
                }

                // Step 4: 读取 64 字节定长设备元数据
                val deviceName = DaemonHandshake.readDeviceMeta(input)

                session = DaemonSession(sessionId, deviceName)
                negotiationIn = DataInputStream(input)
                negotiationOut = DataOutputStream(out)
                Log.i(TAG, "Negotiation socket handshake successful: sessionId=$sessionId, deviceName=$deviceName, auth=${secretToken?.isNotEmpty() == true}")

                // Step 5: 发送 TYPE_CONFIGURE_SESSION (216)，使服务端进入 CONFIGURED 阶段——
                // 否则后续任何 ROLE_* socket 都会等待 2s 后被拒绝。
                val cfgSeq = SequenceGenerator().next()
                val cfgBytes = ControlMessage.ConfigureSession(
                    optionsKv = optionsKv,
                    rolesMask = rolesMask,
                    rolesEntries = rolesEntries
                ).encode(cfgSeq)
                try {
                    negotiationOut!!.write(cfgBytes)
                    negotiationOut!!.flush()
                    // 等待匹配的 GenericResponse。DaemonRpc 拥有长生命周期消息循环，
                    // 它也读取 negotiationIn，因此必须在启动循环之前同步地在此排空
                    // 该响应（否则会与预读竞态，导致 deferred 解析被错过）。
                    // 这与 Python 客户端握手一致：CONFIGURE_SESSION 属于 connect()，
                    // 而非运行时消息流。
                    //
                    // withTimeout 保护"发送 CONFIGURE_SESSION 后、收到回声前服务端崩溃"
                    // 的场景——否则 DeviceMessageCodec.read() 会因 SO_TIMEOUT 足够长
                    // （30s）掩盖挂起而永久阻塞在半开 socket 上。
                    val msg = try {
                        withTimeout(5_000) {
                            DeviceMessageCodec.read(negotiationIn!!)
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        throw IOException("CONFIGURE_SESSION response timed out after 5s (server may have crashed)")
                    }
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

    /**
     * 断开与守护进程的全部连接（协商/控制/视频通道），并清空会话信息。
     */
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
