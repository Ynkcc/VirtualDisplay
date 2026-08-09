package com.ynk.virtualdisplay.daemon

import android.util.Log
import com.ynk.virtualdisplay.protocol.CustomDeviceMessage
import com.ynk.virtualdisplay.protocol.CustomDeviceMessageReader
import com.ynk.virtualdisplay.util.ExceptionUtils
import com.ynk.virtualdisplay.util.closeQuietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class ClientDaemonConnection {

    companion object {
        private const val TAG = "DaemonConnection"
        private const val RETRY_BASE_DELAY_MS = 100L
    }

    private val exceptionHandler = ExceptionUtils.coroutineExceptionHandler(TAG)
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private var videoSocket: Socket? = null
    private var controlSocket: Socket? = null
    private var controlReader: DataInputStream? = null
    private var controlWriter: DataOutputStream? = null

    @Volatile
    private var messageLoopRunning = false

    @Volatile
    private var connected = false

    private val _deviceMessages = MutableSharedFlow<CustomDeviceMessage>(extraBufferCapacity = 64)
    val deviceMessages: SharedFlow<CustomDeviceMessage> = _deviceMessages

    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<CustomDeviceMessage>>()

    private var messageJob: Job? = null

    fun isConnected(): Boolean = connected

    fun getVideoInputStream(): java.io.InputStream? = videoSocket?.inputStream

    suspend fun connect(port: Int, host: String = "127.0.0.1", timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var lastException: IOException? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                closeQuietlyAll()

                val video = Socket()
                val control = Socket()

                video.connect(InetSocketAddress(host, port), 1000)
                control.connect(InetSocketAddress(host, port), 1000)

                videoSocket = video
                controlSocket = control
                controlReader = DataInputStream(control.inputStream)
                controlWriter = DataOutputStream(control.outputStream)

                startMessageLoopInternal()

                connected = true
                Log.i(TAG, "Connected to daemon TCP $host:$port successfully")
                return@withContext true
            } catch (e: IOException) {
                lastException = e
                closeQuietlyAll()
                attempt++
                val backoff = RETRY_BASE_DELAY_MS * (1 shl (attempt - 1)).coerceAtMost(8)
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                delay(backoff.coerceAtMost(remaining))
            }
        }

        Log.e(TAG, "Failed to connect to daemon TCP $host:$port after ${timeoutMs}ms", lastException)
        false
    }

    suspend fun disconnect() = withContext(Dispatchers.IO + NonCancellable) {
        messageLoopRunning = false
        connected = false
        messageJob?.cancel()
        messageJob = null

        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()

        connectionScope.coroutineContext.cancelChildren()

        closeQuietlyAll()

        Log.i(TAG, "Disconnected from daemon")
    }

    suspend fun sendControlMessage(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val writer = controlWriter ?: return@withContext false
            writer.write(bytes)
            writer.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "sendControlMessage failed", e)
            handleDisconnection()
            false
        }
    }

    fun startMessageLoop() {
        startMessageLoopInternal()
    }

    private fun startMessageLoopInternal() {
        if (messageLoopRunning) return
        val reader = controlReader ?: return

        messageLoopRunning = true
        messageJob = connectionScope.launch {
            try {
                while (messageLoopRunning) {
                    try {
                        val message = CustomDeviceMessageReader.read(reader)
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
                        break
                    }
                }
            } finally {
                messageLoopRunning = false
            }
        }
    }

    private fun handleMessage(message: CustomDeviceMessage) {
        val sequence = when (message) {
            is CustomDeviceMessage.GenericResponse -> message.sequence
            is CustomDeviceMessage.ActiveDisplaysResponse -> message.sequence
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
            when (message) {
                is CustomDeviceMessage.GenericResponse -> {
                    _deviceMessages.tryEmit(message)
                }
                is CustomDeviceMessage.ActiveDisplaysResponse -> {
                    _deviceMessages.tryEmit(message)
                }
            }
        }
    }

    suspend fun sendAndAwait(bytes: ByteArray, expectedSequence: Long, timeoutMs: Long = 5000): CustomDeviceMessage? {
        val deferred = CompletableDeferred<CustomDeviceMessage>()
        pendingRequests[expectedSequence] = deferred

        val sent = sendControlMessage(bytes)
        if (!sent) {
            pendingRequests.remove(expectedSequence)
            return null
        }

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(expectedSequence)
            Log.w(TAG, "sendAndAwait timed out after ${timeoutMs}ms (sequence=$expectedSequence)")
            null
        } catch (e: CancellationException) {
            pendingRequests.remove(expectedSequence)
            throw e
        } catch (e: Exception) {
            pendingRequests.remove(expectedSequence)
            Log.e(TAG, "sendAndAwait failed", e)
            null
        }
    }

    private fun handleDisconnection() {
        GlobalScope.launch(Dispatchers.IO + exceptionHandler + NonCancellable) {
            disconnect()
        }
    }

    private fun closeQuietlyAll() {
        controlReader.closeQuietly()
        controlWriter.closeQuietly()
        controlSocket.closeQuietly()
        videoSocket.closeQuietly()
        controlReader = null
        controlWriter = null
        controlSocket = null
        videoSocket = null
    }
}
