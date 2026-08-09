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
    suspend fun injectInput(displayId: Int, isKey: Boolean, parcelBytes: ByteArray): Result<Boolean>
    suspend fun switchDisplay(displayId: Int): Result<Unit>
    suspend fun exitDaemon(): Result<Unit>
    suspend fun startVideoStream(displayId: Int): Result<Unit>
    suspend fun stopVideoStream(): Result<Unit>
}

class DaemonControlApiImpl(private val rpc: DaemonRpc) : DaemonControlApi, com.ynk.virtualdisplay.video.VideoStreamRpc {

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

    override suspend fun injectInput(displayId: Int, isKey: Boolean, parcelBytes: ByteArray): Result<Boolean> {
        val msg = ControlMessage.InjectInputEvent(displayId, isKey, parcelBytes)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            Result.success(resp.statusCode == 0)
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
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
}
