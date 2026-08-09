package com.ynk.virtualdisplay.rpc

import com.ynk.virtualdisplay.protocol.ControlMessage
import com.ynk.virtualdisplay.protocol.DeviceMessage
import java.io.IOException

interface DaemonControlApi {
    suspend fun createDisplay(name: String, w: Int, h: Int, dpi: Int, flags: Int): Result<Int>
    suspend fun releaseDisplay(displayId: Int): Result<Unit>
    suspend fun resizeDisplay(displayId: Int, w: Int, h: Int, dpi: Int): Result<Unit>
    suspend fun startActivity(packageName: String, displayId: Int): Result<Int>
    suspend fun getActiveDisplayIds(): Result<IntArray>
    suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>>
    suspend fun injectInput(displayId: Int, isKey: Boolean, parcelBytes: ByteArray): Result<Boolean>
    suspend fun switchDisplay(displayId: Int): Result<Unit>
    suspend fun exitDaemon(): Result<Unit>
    suspend fun startVideoStream(displayId: Int): Result<Unit>
    suspend fun stopVideoStream(): Result<Unit>
    suspend fun getRotation(displayId: Int): Result<Int>
    suspend fun freezeRotation(displayId: Int, rotation: Int): Result<Unit>
    suspend fun thawRotation(displayId: Int): Result<Unit>
    suspend fun isRotationFrozen(displayId: Int): Result<Boolean>
}

class DaemonControlApiImpl(
    private val rpc: DaemonRpc,
    private val transport: com.ynk.virtualdisplay.net.DaemonTransport
) : DaemonControlApi, com.ynk.virtualdisplay.video.VideoStreamRpc {

    override suspend fun createDisplay(name: String, w: Int, h: Int, dpi: Int, flags: Int): Result<Int> {
        val msg = ControlMessage.CreateVirtualDisplay(name, w, h, dpi, flags)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) {
                Result.success(resp.displayId)
            } else {
                Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
            }
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun releaseDisplay(displayId: Int): Result<Unit> {
        val msg = ControlMessage.ReleaseVirtualDisplay(displayId)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
            }
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun resizeDisplay(displayId: Int, w: Int, h: Int, dpi: Int): Result<Unit> {
        val msg = ControlMessage.ResizeVirtualDisplay(displayId, w, h, dpi)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
            }
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun startActivity(packageName: String, displayId: Int): Result<Int> {
        val msg = ControlMessage.StartActivity(packageName, displayId)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode >= 0) {
                Result.success(resp.statusCode)
            } else {
                Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
            }
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun getActiveDisplayIds(): Result<IntArray> {
        val msg = ControlMessage.GetActiveDisplayIds
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.ActiveDisplaysResponse) {
            Result.success(resp.displayIds)
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>> {
        val msg = ControlMessage.GetActiveDisplayInfos
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.ActiveDisplayInfosResponse) {
            Result.success(resp.displays)
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun injectInput(displayId: Int, isKey: Boolean, parcelBytes: ByteArray): Result<Boolean> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val writer = transport.controlOutputStream() ?: return@withContext Result.failure(IOException("Control channel not connected"))
        val msg = ControlMessage.InjectInputEvent(displayId, isKey, parcelBytes)
        val seq = rpc.nextSequence()
        val bytes = msg.encode(seq)
        return@withContext try {
            writer.write(bytes)
            writer.flush()
            Result.success(true)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    override suspend fun switchDisplay(displayId: Int): Result<Unit> {
        val msg = ControlMessage.SwitchDisplay(displayId)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
            }
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun exitDaemon(): Result<Unit> {
        val msg = ControlMessage.ExitDaemon
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
            }
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun startVideoStream(displayId: Int): Result<Unit> {
        val msg = ControlMessage.StartVideoStream(displayId)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
            }
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun stopVideoStream(): Result<Unit> {
        val msg = ControlMessage.StopVideoStream
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
            }
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun getRotation(displayId: Int): Result<Int> {
        val msg = ControlMessage.GetRotation(displayId)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse && resp.statusCode == 0) {
            val rotation = resp.message?.toIntOrNull()
                ?: return Result.failure(IllegalStateException("Missing rotation value in response: ${resp.message}"))
            Result.success(rotation)
        } else {
            Result.failure(IllegalStateException((resp as? DeviceMessage.GenericResponse)?.message ?: "Unexpected response: $resp"))
        }
    }

    override suspend fun freezeRotation(displayId: Int, rotation: Int): Result<Unit> {
        val msg = ControlMessage.FreezeRotation(displayId, rotation)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) Result.success(Unit)
            else Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun thawRotation(displayId: Int): Result<Unit> {
        val msg = ControlMessage.ThawRotation(displayId)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) Result.success(Unit)
            else Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun isRotationFrozen(displayId: Int): Result<Boolean> {
        val msg = ControlMessage.IsRotationFrozen(displayId)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse && resp.statusCode == 0) {
            val frozen = resp.message?.toIntOrNull()
                ?: return Result.failure(IllegalStateException("Missing frozen value in response: ${resp.message}"))
            Result.success(frozen != 0)
        } else {
            Result.failure(IllegalStateException((resp as? DeviceMessage.GenericResponse)?.message ?: "Unexpected response: $resp"))
        }
    }
}
