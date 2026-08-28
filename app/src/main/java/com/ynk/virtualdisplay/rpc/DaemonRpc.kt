package com.ynk.virtualdisplay.rpc

import android.util.Log
import com.ynk.virtualdisplay.net.DaemonTransport
import com.ynk.virtualdisplay.protocol.ControlMessage
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.protocol.DeviceMessageCodec
import com.ynk.virtualdisplay.protocol.SequenceGenerator
import com.ynk.virtualdisplay.util.ExceptionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * DaemonRpc 的连接/运行状态。
 *
 * 用于让上层（如 [com.ynk.virtualdisplay.data.repository.ConnectionSlot]）监听到
 * negotiation socket 被远端关闭（例如 App 切屏切换流导致的 210 失败后紧跟着 EOF），
 * 从而自动重连而不是让 UI 永久处于"所有 RPC 都超时/ Connection error"的状态。
 */
enum class DaemonRpcState {
    /** 尚未建立连接/消息循环 */
    IDLE,
    /** 消息循环正在运行 */
    RUNNING,
    /** 消息循环非正常退出（socket 关闭/解码错误） */
    LOOP_EXITED_ABNORMALLY,
    /** 已主动停止 */
    STOPPED,
}

/**
 * 协商通道上的 RPC 层：维护消息读取循环、按 sequence 关联请求与响应，
 * 并把服务端主动推送的消息暴露为 [deviceMessages] 流。
 */
class DaemonRpc(
    private val transport: DaemonTransport,
    private val sequenceGen: SequenceGenerator = SequenceGenerator()
) {
    companion object {
        private const val TAG = "DaemonRpc"
    }

    // 按 sequence 关联的待响应请求：发送请求时登记，收到对应响应时完成 deferred。
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<DeviceMessage>>()
    private val _deviceMessages = MutableSharedFlow<DeviceMessage>(extraBufferCapacity = 64)
    /** 服务端主动推送的消息流（如显示器创建/销毁通知），缓冲 64 条。 */
    val deviceMessages: SharedFlow<DeviceMessage> = _deviceMessages

    private val _connectionState = MutableStateFlow(DaemonRpcState.IDLE)
    /** 当前 RPC 连接状态。 */
    val connectionState: StateFlow<DaemonRpcState> = _connectionState.asStateFlow()

    @Volatile
    private var messageLoopRunning = false
    @Volatile
    private var explicitStop = false
    private var messageJob: Job? = null

    /** 获取下一个递增的请求序号。 */
    fun nextSequence(): Long = sequenceGen.next()

    /**
     * 在给定作用域中启动协商通道的消息读取循环。
     *
     * @param scope 承载消息循环的协程作用域
     */
    fun startMessageLoop(scope: CoroutineScope) {
        if (messageLoopRunning) return
        val reader = transport.negotiationInputStream() ?: return
        explicitStop = false

        messageLoopRunning = true
        _connectionState.value = DaemonRpcState.RUNNING
        messageJob = scope.launch(Dispatchers.IO) {
            try {
                while (messageLoopRunning) {
                    try {
                        val message = DeviceMessageCodec.read(reader)
                        handleMessage(message)
                    } catch (e: SocketTimeoutException) {
                        // 协商 socket 触发了 SO_TIMEOUT。这对空闲的长连接是预期的——
                        // 服务端可能数分钟不发送消息。保持循环存活，下次 read() 会再次阻塞。
                        continue
                    } catch (e: IOException) {
                        if (messageLoopRunning && !explicitStop) {
                            Log.e(TAG, "IOException in message loop", e)
                        }
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (messageLoopRunning && !explicitStop) {
                            Log.e(TAG, "Error reading message", e)
                        }
                        ExceptionUtils.rethrowInDebug(e)
                        break
                    }
                }
            } finally {
                messageLoopRunning = false
                // 若循环因流结束/解码错误/socket 关闭退出（而非显式 stopMessageLoop），
                // 取消所有待处理请求，避免调用方一直挂到 sendAndAwait 的（可能长得多）
                // 超时才返回，并通知监听方以便自动重连。
                val pending = pendingRequests.values.toList()
                if (pending.isNotEmpty()) {
                    pendingRequests.clear()
                    pending.forEach { deferred ->
                        runCatching {
                            deferred.completeExceptionally(
                                IOException("Message loop terminated before response received")
                            )
                        }
                    }
                }
                _connectionState.value = if (explicitStop) {
                    DaemonRpcState.STOPPED
                } else {
                    DaemonRpcState.LOOP_EXITED_ABNORMALLY
                }
            }
        }
    }

    /** 停止消息读取循环，并标记为主动停止（区别于异常退出）。 */
    fun stopMessageLoop() {
        explicitStop = true
        messageLoopRunning = false
        messageJob?.cancel()
        messageJob = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        _connectionState.value = DaemonRpcState.STOPPED
    }

    private fun handleMessage(message: DeviceMessage) {
        val sequence = when (message) {
            is DeviceMessage.GenericResponse -> message.sequence
            is DeviceMessage.ActiveDisplaysResponse -> message.sequence
            is DeviceMessage.ActiveDisplayInfosResponse -> message.sequence
            is DeviceMessage.AppsListResponse -> message.sequence
        }

        val matched = if (sequence != 0L) {
            val deferred = pendingRequests.remove(sequence)
            if (deferred != null) {
                runCatching { deferred.complete(message) }
                true
            } else {
                false
            }
        } else {
            false
        }

        if (!matched) {
            _deviceMessages.tryEmit(message)
        }
    }

    /**
     * 发送一条控制消息，并等待与之对应的服务端响应。
     *
     * @param msg 要发送的控制消息
     * @param timeoutMs 等待响应的超时（毫秒），默认 5000
     * @return 服务端响应，超时/未连接/序列不匹配时返回 null
     */
    suspend fun sendAndAwait(msg: ControlMessage, timeoutMs: Long = 5000): DeviceMessage? = withContext(Dispatchers.IO) {
        // 快速失败：若未连接（握手未成功、消息循环未启动，或协商 socket 已死尚未
        // 重连），立即返回而非等待 write() 抛错或 5s 超时。这把"陈旧 socket"错误
        // 的感知延迟从 5s 降到约 1ms。
        val writer = transport.negotiationOutputStream()
        if (writer == null || !messageLoopRunning) {
            Log.w(TAG, "sendAndAwait(type=${msg.type}) skipped: negotiation channel not open (writer=${writer != null}, loop=$messageLoopRunning)")
            return@withContext null
        }

        val seq = nextSequence()
        val bytes = msg.encode(seq)

        val deferred = CompletableDeferred<DeviceMessage>()
        pendingRequests[seq] = deferred

        try {
            writer.write(bytes)
            writer.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write RPC message (seq=$seq, type=${msg.type})", e)
            pendingRequests.remove(seq)
            return@withContext null
        }

        return@withContext try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(seq)
            Log.w(TAG, "sendAndAwait timed out after ${timeoutMs}ms (sequence=$seq, type=${msg.type})")
            null
        } catch (e: CancellationException) {
            pendingRequests.remove(seq)
            throw e
        } catch (e: Exception) {
            pendingRequests.remove(seq)
            Log.e(TAG, "sendAndAwait failed (seq=$seq, type=${msg.type})", e)
            ExceptionUtils.rethrowInDebug(e)
            null
        }
    }
}
