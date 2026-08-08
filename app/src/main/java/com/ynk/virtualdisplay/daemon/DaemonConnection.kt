package com.ynk.virtualdisplay.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.ynk.virtualdisplay.protocol.CustomDeviceMessage
import com.ynk.virtualdisplay.protocol.CustomDeviceMessageReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
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
import java.util.concurrent.ConcurrentHashMap

class ClientDaemonConnection {

    companion object {
        private const val TAG = "DaemonConnection"
        private const val SOCKET_NAME = "scrcpy"
        private const val RETRY_BASE_DELAY_MS = 100L
    }

    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var videoSocket: LocalSocket? = null
    private var controlSocket: LocalSocket? = null
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

    suspend fun connect(timeoutMs: Long = 3000): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        var lastException: IOException? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                closeQuietly()

                val video = LocalSocket()
                val control = LocalSocket()

                video.connect(LocalSocketAddress(SOCKET_NAME))
                control.connect(LocalSocketAddress(SOCKET_NAME))

                videoSocket = video
                controlSocket = control
                controlReader = DataInputStream(control.inputStream)
                controlWriter = DataOutputStream(control.outputStream)

                startMessageLoopInternal()

                connected = true
                Log.i(TAG, "Connected to daemon successfully")
                return@withContext true
            } catch (e: IOException) {
                lastException = e
                closeQuietly()
                attempt++
                val backoff = RETRY_BASE_DELAY_MS * (1 shl (attempt - 1)).coerceAtMost(8)
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                delay(backoff.coerceAtMost(remaining))
            }
        }

        Log.e(TAG, "Failed to connect to daemon after ${timeoutMs}ms", lastException)
        false
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        messageLoopRunning = false
        connected = false
        messageJob?.cancel()
        messageJob = null

        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()

        connectionScope.coroutineContext.cancelChildren()

        closeQuietly()

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
        connectionScope.launch {
            disconnect()
        }
    }

    private fun closeQuietly() {
        try {
            controlReader?.close()
        } catch (_: IOException) {
        }
        try {
            controlWriter?.close()
        } catch (_: IOException) {
        }
        try {
            controlSocket?.close()
        } catch (_: IOException) {
        }
        try {
            videoSocket?.close()
        } catch (_: IOException) {
        }
        controlReader = null
        controlWriter = null
        controlSocket = null
        videoSocket = null
    }
}
