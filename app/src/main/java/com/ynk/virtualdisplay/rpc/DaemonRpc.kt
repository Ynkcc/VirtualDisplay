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
    IDLE,
    RUNNING,
    LOOP_EXITED_ABNORMALLY,
    STOPPED,
}

class DaemonRpc(
    private val transport: DaemonTransport,
    private val sequenceGen: SequenceGenerator = SequenceGenerator()
) {
    companion object {
        private const val TAG = "DaemonRpc"
    }

    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<DeviceMessage>>()
    private val _deviceMessages = MutableSharedFlow<DeviceMessage>(extraBufferCapacity = 64)
    val deviceMessages: SharedFlow<DeviceMessage> = _deviceMessages

    private val _connectionState = MutableStateFlow(DaemonRpcState.IDLE)
    val connectionState: StateFlow<DaemonRpcState> = _connectionState.asStateFlow()

    @Volatile
    private var messageLoopRunning = false
    @Volatile
    private var explicitStop = false
    private var messageJob: Job? = null

    fun nextSequence(): Long = sequenceGen.next()

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
                        // SO_TIMEOUT on the negotiation socket fired. This is
                        // expected for idle long-lived connections — the server
                        // may go minutes without sending a message. Keep the
                        // loop alive; the next read() will block again.
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
                // If the loop exited due to end-of-stream / decode error / socket
                // close (not an explicit stopMessageLoop call), cancel any pending
                // requests so callers do not hang waiting until sendAndAwait's
                // (potentially much longer) timeout fires, and notify listeners
                // so they can auto-reconnect.
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

    suspend fun sendAndAwait(msg: ControlMessage, timeoutMs: Long = 5000): DeviceMessage? = withContext(Dispatchers.IO) {
        // Fast path: if we are not connected (transport handshake never succeeded,
        // message loop never started, or the negotiation socket died and a
        // reconnect has not yet happened), fail immediately instead of waiting
        // for write() to throw or for the 5s timeout to fire. This cuts the
        // perceived latency on "stale socket" errors from 5s to ~1ms.
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
