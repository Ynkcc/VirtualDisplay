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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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

    @Volatile
    private var messageLoopRunning = false
    private var messageJob: Job? = null

    fun nextSequence(): Long = sequenceGen.next()

    fun startMessageLoop(scope: CoroutineScope) {
        if (messageLoopRunning) return
        val reader = transport.negotiationInputStream() ?: return

        messageLoopRunning = true
        messageJob = scope.launch(Dispatchers.IO) {
            try {
                while (messageLoopRunning) {
                    try {
                        val message = DeviceMessageCodec.read(reader)
                        handleMessage(message)
                    } catch (e: IOException) {
                        if (messageLoopRunning) {
                            Log.e(TAG, "IOException in message loop", e)
                        }
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (messageLoopRunning) {
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
                // (potentially much longer) timeout fires.
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
            }
        }
    }

    fun stopMessageLoop() {
        messageLoopRunning = false
        messageJob?.cancel()
        messageJob = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
    }

    private fun handleMessage(message: DeviceMessage) {
        val sequence = when (message) {
            is DeviceMessage.GenericResponse -> message.sequence
            is DeviceMessage.ActiveDisplaysResponse -> message.sequence
            is DeviceMessage.ActiveDisplayInfosResponse -> message.sequence
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
        val writer = transport.negotiationOutputStream() ?: return@withContext null
        val seq = nextSequence()
        val bytes = msg.encode(seq)

        val deferred = CompletableDeferred<DeviceMessage>()
        pendingRequests[seq] = deferred

        try {
            writer.write(bytes)
            writer.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write RPC message", e)
            pendingRequests.remove(seq)
            return@withContext null
        }

        return@withContext try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(seq)
            Log.w(TAG, "sendAndAwait timed out after ${timeoutMs}ms (sequence=$seq)")
            null
        } catch (e: CancellationException) {
            pendingRequests.remove(seq)
            throw e
        } catch (e: Exception) {
            pendingRequests.remove(seq)
            Log.e(TAG, "sendAndAwait failed", e)
            ExceptionUtils.rethrowInDebug(e)
            null
        }
    }
}
