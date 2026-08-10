package com.ynk.virtualdisplay.rpc

import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.ynk.virtualdisplay.protocol.ControlMessage
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.protocol.ScrcpyControlEncoder
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface DaemonControlApi {
    suspend fun createDisplay(name: String, w: Int, h: Int, dpi: Int, flags: Int, mirrorDisplayId: Int = -1): Result<Int>
    suspend fun releaseDisplay(displayId: Int): Result<Unit>
    suspend fun resizeDisplay(displayId: Int, w: Int, h: Int, dpi: Int): Result<Unit>
    suspend fun startActivity(packageName: String, displayId: Int): Result<Int>
    suspend fun launchHome(displayId: Int): Result<Int>
    suspend fun listApps(): Result<List<DeviceMessage.AppEntry>>
    suspend fun getActiveDisplayIds(): Result<IntArray>
    suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>>
    /**
     * 注入输入事件 —— 通过 ROLE_CONTROL socket 上的 scrcpy 原生控制协议发送。
     *
     * 服务端 commit b7aef962 移除了 TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID (206)，
     * 输入注入必须使用 scrcpy 原生协议：
     *   - KeyEvent → TYPE_INJECT_KEYCODE (0)
     *   - MotionEvent (touch) → TYPE_INJECT_TOUCH_EVENT (2)
     *   - MotionEvent (scroll) → TYPE_INJECT_SCROLL_EVENT (3)
     *
     * ROLE_CONTROL socket 在 connectScrcpyChannels(displayId) 时已路由到
     * 目标显示器，服务端 Controller 会在对应的 displayId 上执行注入。
     *
     * @param displayId 目标显示器 ID (用于日志)
     * @param event     要注入的 KeyEvent 或 MotionEvent
     * @param screenWidth 视频表面宽度 (像素，用于位置编码)
     * @param screenHeight 视频表面高度 (像素，用于位置编码)
     */
    suspend fun injectInput(displayId: Int, event: InputEvent, screenWidth: Int, screenHeight: Int): Result<Boolean>
    suspend fun exitDaemon(): Result<Unit>
    suspend fun getRotation(displayId: Int): Result<Int>
    suspend fun freezeRotation(displayId: Int, rotation: Int): Result<Unit>
    suspend fun thawRotation(displayId: Int): Result<Unit>
    suspend fun isRotationFrozen(displayId: Int): Result<Boolean>
    suspend fun ping(): Result<Long>
}

class DaemonControlApiImpl(
    private val rpc: DaemonRpc,
    private val transport: com.ynk.virtualdisplay.net.DaemonTransport
) : DaemonControlApi {

    override suspend fun createDisplay(name: String, w: Int, h: Int, dpi: Int, flags: Int, mirrorDisplayId: Int): Result<Int> {
        val msg = ControlMessage.CreateVirtualDisplay(name, w, h, dpi, flags, mirrorDisplayId)
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

    override suspend fun launchHome(displayId: Int): Result<Int> {
        val msg = ControlMessage.LaunchHome(displayId)
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.GenericResponse) {
            if (resp.statusCode == 0) Result.success(resp.displayId)
            else Result.failure(IllegalStateException(resp.message ?: "Failed code: ${resp.statusCode}"))
        } else {
            Result.failure(IllegalStateException("Unexpected response type: $resp"))
        }
    }

    override suspend fun listApps(): Result<List<DeviceMessage.AppEntry>> {
        val msg = ControlMessage.ListApps
        // AppLister.listInstalledApps() on a device with hundreds of apps
        // can take several seconds (per-app PackageManager binder calls).
        // Give it 15s instead of the default 5s so the client doesn't
        // timeout spuriously on slow devices.
        val resp = rpc.sendAndAwait(msg, timeoutMs = 15_000) ?: return Result.failure(IOException("Connection error or timeout"))
        return if (resp is DeviceMessage.AppsListResponse) {
            Result.success(resp.apps)
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

    override suspend fun injectInput(displayId: Int, event: InputEvent, screenWidth: Int, screenHeight: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        val out = transport.controlOutputStream()
        if (out == null) {
            Log.e("DaemonControlApi", "injectInput: ROLE_CONTROL socket not connected (displayId=$displayId). Start video streaming first.")
            return@withContext Result.failure(IllegalStateException("ROLE_CONTROL socket not connected — video must be streaming before injecting input"))
        }

        try {
            when (event) {
                is KeyEvent -> {
                    val ok = transport.writeControlMessage { stream ->
                        ScrcpyControlEncoder.encodeKeyCode(stream, event)
                    }
                    if (!ok) {
                        Log.e("DaemonControlApi", "injectInput: failed to write KEYCODE to ROLE_CONTROL socket (displayId=$displayId)")
                        Result.failure(IOException("Failed to write keycode to ROLE_CONTROL socket"))
                    } else {
                        Result.success(true)
                    }
                }
                is MotionEvent -> {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_SCROLL -> {
                            val ok = transport.writeControlMessage { stream ->
                                ScrcpyControlEncoder.encodeScroll(stream, event, screenWidth, screenHeight)
                            }
                            if (!ok) {
                                Log.e("DaemonControlApi", "injectInput: failed to write SCROLL to ROLE_CONTROL socket")
                                Result.failure(IOException("Failed to write scroll to ROLE_CONTROL socket"))
                            } else {
                                Result.success(true)
                            }
                        }
                        else -> {
                            val ok = transport.writeControlMessage { stream ->
                                ScrcpyControlEncoder.encodeTouchEvent(stream, event, screenWidth, screenHeight)
                            }
                            if (!ok) {
                                Log.e("DaemonControlApi", "injectInput: failed to write TOUCH to ROLE_CONTROL socket (displayId=$displayId)")
                                Result.failure(IOException("Failed to write touch event to ROLE_CONTROL socket"))
                            } else {
                                Result.success(true)
                            }
                        }
                    }
                }
                else -> {
                    Log.w("DaemonControlApi", "injectInput: unsupported event type ${event.javaClass.simpleName}")
                    Result.failure(IllegalArgumentException("Unsupported InputEvent type: ${event.javaClass.simpleName}"))
                }
            }
        } catch (t: Throwable) {
            Log.e("DaemonControlApi", "injectInput failed (displayId=$displayId)", t)
            Result.failure(t)
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

    override suspend fun ping(): Result<Long> {
        val start = System.currentTimeMillis()
        val msg = ControlMessage.Ping
        val resp = rpc.sendAndAwait(msg) ?: return Result.failure(IOException("Ping timeout"))
        return if (resp is DeviceMessage.GenericResponse && resp.statusCode == 0) {
            Result.success(System.currentTimeMillis() - start)
        } else {
            Result.failure(IllegalStateException("Ping failed"))
        }
    }
}
