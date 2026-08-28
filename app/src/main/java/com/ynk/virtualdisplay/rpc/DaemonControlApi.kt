package com.ynk.virtualdisplay.rpc

import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.ynk.virtualdisplay.protocol.ControlMessage
import com.ynk.virtualdisplay.protocol.DeviceMessage
import com.ynk.virtualdisplay.protocol.ScrcpyControlEncoder
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface DaemonControlApi {
    suspend fun createDisplay(name: String, w: Int, h: Int, dpi: Int, flags: Int, mirrorDisplayId: Int = -1): Result<Int>
    suspend fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean = true): Result<Unit>
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

    companion object {
        private const val TAG = "DaemonControlApi"
        /** 默认 RPC 响应超时 */
        private const val DEFAULT_TIMEOUT_MS = 5_000L
        /** listApps 在慢设备上逐个 binder 查询，需要更宽松的超时 */
        private const val LIST_APPS_TIMEOUT_MS = 15_000L
    }

    // ====================== 统一 RPC 响应处理 ======================

    /**
     * 发送 [msg] 并等待响应，统一封装：未连上 / 超时 → 失败；类型校验与
     * 提取失败 → 失败；正常 → 成功。CancellationException 正常向上传播。
     */
    private suspend fun <T> sendAndTransform(
        msg: ControlMessage,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        transform: (DeviceMessage) -> T,
    ): Result<T> {
        val resp = rpc.sendAndAwait(msg, timeoutMs)
            ?: return Result.failure(IOException("Connection error or timeout"))
        return try {
            Result.success(transform(resp))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 将响应强制解释为 [DeviceMessage.GenericResponse]，否则视为协议错误。 */
    private fun DeviceMessage.asGenericResponse(): DeviceMessage.GenericResponse =
        this as? DeviceMessage.GenericResponse
            ?: throw IllegalStateException("Unexpected response type: $this")

    /** 校验 statusCode，满足则返回自身；否则抛出带服务端 message 的异常。 */
    private fun DeviceMessage.GenericResponse.requireSuccess(
        isSuccess: (Int) -> Boolean = { it == 0 },
    ): DeviceMessage.GenericResponse {
        if (!isSuccess(statusCode)) {
            throw IllegalStateException(message ?: "Failed code: $statusCode")
        }
        return this
    }

    // ====================== 显示器管理 ======================

    override suspend fun createDisplay(name: String, w: Int, h: Int, dpi: Int, flags: Int, mirrorDisplayId: Int): Result<Int> =
        sendAndTransform(ControlMessage.CreateVirtualDisplay(name, w, h, dpi, flags, mirrorDisplayId)) { resp ->
            resp.asGenericResponse().requireSuccess().displayId
        }

    override suspend fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean): Result<Unit> =
        sendAndTransform(ControlMessage.ReleaseVirtualDisplay(displayId, moveTasksToDefaultDisplay)) { resp ->
            resp.asGenericResponse().requireSuccess()
            Unit
        }

    override suspend fun resizeDisplay(displayId: Int, w: Int, h: Int, dpi: Int): Result<Unit> =
        sendAndTransform(ControlMessage.ResizeVirtualDisplay(displayId, w, h, dpi)) { resp ->
            resp.asGenericResponse().requireSuccess()
            Unit
        }

    override suspend fun startActivity(packageName: String, displayId: Int): Result<Int> =
        sendAndTransform(ControlMessage.StartActivity(packageName, displayId)) { resp ->
            // 服务端把启动结果编码在 statusCode（>=0 视为成功）
            resp.asGenericResponse().requireSuccess { it >= 0 }.statusCode
        }

    override suspend fun launchHome(displayId: Int): Result<Int> =
        sendAndTransform(ControlMessage.LaunchHome(displayId)) { resp ->
            resp.asGenericResponse().requireSuccess().displayId
        }

    // ====================== 应用列表 ======================

    override suspend fun listApps(): Result<List<DeviceMessage.AppEntry>> =
        sendAndTransform(ControlMessage.ListApps, timeoutMs = LIST_APPS_TIMEOUT_MS) { resp ->
            val appsResp = resp as? DeviceMessage.AppsListResponse
                ?: throw IllegalStateException("Unexpected response type: $resp")
            appsResp.apps
        }

    // ====================== 显示器查询 ======================

    override suspend fun getActiveDisplayIds(): Result<IntArray> =
        sendAndTransform(ControlMessage.GetActiveDisplayIds) { resp ->
            val listResp = resp as? DeviceMessage.ActiveDisplaysResponse
                ?: throw IllegalStateException("Unexpected response type: $resp")
            listResp.displayIds
        }

    override suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>> =
        sendAndTransform(ControlMessage.GetActiveDisplayInfos) { resp ->
            val listResp = resp as? DeviceMessage.ActiveDisplayInfosResponse
                ?: throw IllegalStateException("Unexpected response type: $resp")
            listResp.displays
        }

    // ====================== 输入注入 (scrcpy-native via ROLE_CONTROL socket) ======================

    override suspend fun injectInput(displayId: Int, event: InputEvent, screenWidth: Int, screenHeight: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        val out = transport.controlOutputStream()
        if (out == null) {
            Log.e(TAG, "injectInput: ROLE_CONTROL socket not connected (displayId=$displayId). Start video streaming first.")
            return@withContext Result.failure(IllegalStateException("ROLE_CONTROL socket not connected — video must be streaming before injecting input"))
        }

        try {
            when (event) {
                is KeyEvent -> {
                    val ok = transport.writeControlMessage { stream ->
                        ScrcpyControlEncoder.encodeKeyCode(stream, event)
                    }
                    if (!ok) {
                        Log.e(TAG, "injectInput: failed to write KEYCODE to ROLE_CONTROL socket (displayId=$displayId)")
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
                                Log.e(TAG, "injectInput: failed to write SCROLL to ROLE_CONTROL socket")
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
                                Log.e(TAG, "injectInput: failed to write TOUCH to ROLE_CONTROL socket (displayId=$displayId)")
                                Result.failure(IOException("Failed to write touch event to ROLE_CONTROL socket"))
                            } else {
                                Result.success(true)
                            }
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "injectInput: unsupported event type ${event.javaClass.simpleName}")
                    Result.failure(IllegalArgumentException("Unsupported InputEvent type: ${event.javaClass.simpleName}"))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "injectInput failed (displayId=$displayId)", t)
            Result.failure(t)
        }
    }

    // ====================== 守护进程生命周期 ======================

    override suspend fun exitDaemon(): Result<Unit> =
        sendAndTransform(ControlMessage.ExitDaemon) { resp ->
            resp.asGenericResponse().requireSuccess()
            Unit
        }

    // ====================== 旋转控制 ======================

    override suspend fun getRotation(displayId: Int): Result<Int> =
        sendAndTransform(ControlMessage.GetRotation(displayId)) { resp ->
            val gr = resp.asGenericResponse().requireSuccess()
            gr.message?.toIntOrNull()
                ?: throw IllegalStateException("Missing rotation value in response: ${gr.message}")
        }

    override suspend fun freezeRotation(displayId: Int, rotation: Int): Result<Unit> =
        sendAndTransform(ControlMessage.FreezeRotation(displayId, rotation)) { resp ->
            resp.asGenericResponse().requireSuccess()
            Unit
        }

    override suspend fun thawRotation(displayId: Int): Result<Unit> =
        sendAndTransform(ControlMessage.ThawRotation(displayId)) { resp ->
            resp.asGenericResponse().requireSuccess()
            Unit
        }

    override suspend fun isRotationFrozen(displayId: Int): Result<Boolean> =
        sendAndTransform(ControlMessage.IsRotationFrozen(displayId)) { resp ->
            val gr = resp.asGenericResponse().requireSuccess()
            val frozen = gr.message?.toIntOrNull()
                ?: throw IllegalStateException("Missing frozen value in response: ${gr.message}")
            frozen != 0
        }

    // ====================== 心跳 ======================

    override suspend fun ping(): Result<Long> {
        val start = System.currentTimeMillis()
        return sendAndTransform(ControlMessage.Ping) { resp ->
            resp.asGenericResponse().requireSuccess()
            System.currentTimeMillis() - start
        }
    }
}
