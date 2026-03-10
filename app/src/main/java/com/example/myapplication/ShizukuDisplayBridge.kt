package com.example.myapplication

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import rikka.shizuku.Shizuku
import android.view.InputEvent
import android.content.Intent
import android.os.Bundle

object ShizukuDisplayBridge {
    private const val TAG = "ShizukuDisplayBridge"
    const val REQUEST_CODE = 20260

    // Flags from scrcpy
    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 shl 1
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    // ---- UserService 相关 ----

    @Volatile
    private var displayService: IDisplayService? = null

    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    /**
     * 等待 UserService 就绪后执行的回调队列。
     * 解决 bindUserService 异步完成与调用方立即使用之间的竞态条件。
     */
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                displayService = IDisplayService.Stub.asInterface(binder)
                Log.d(TAG, "DisplayUserService connected")
                // 执行所有等待就绪的回调
                val callbacks: List<() -> Unit>
                synchronized(pendingCallbacks) {
                    callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                }
                callbacks.forEach { callback ->
                    try {
                        callback()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing pending callback", e)
                    }
                }
            } else {
                Log.e(TAG, "Invalid binder for $name received")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            displayService = null
            // 清空待执行队列，避免在旧服务断开后执行过时的操作
            synchronized(pendingCallbacks) {
                if (pendingCallbacks.isNotEmpty()) {
                    Log.w(TAG, "ServiceDisconnected: discarding ${pendingCallbacks.size} pending callbacks")
                    pendingCallbacks.clear()
                }
            }
            Log.d(TAG, "DisplayUserService disconnected")
        }
    }

    /**
     * 绑定 Shizuku UserService。
     * 必须在调用 [createVirtualDisplay] 前绑定并等待 [onServiceConnected]。
     */
    fun bindUserService(context: Context) {
        if (!isShizukuAvailable()) {
            Log.e(TAG, "bindUserService: Shizuku not available")
            return
        }

        if (displayService != null) {
            Log.d(TAG, "bindUserService: already connected")
            return
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "bindUserService: Shizuku permission not granted yet")
            return
        }

        try {
            if (userServiceArgs == null) {
                userServiceArgs = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, DisplayUserService::class.java.name)
                )
                    .daemon(true)
                    .processNameSuffix("display_service")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE)
            }
            Log.d(TAG, "Calling Shizuku.bindUserService for ${context.packageName}")
            Shizuku.bindUserService(userServiceArgs!!, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed", e)
        }
    }

    /**
     * 解绑 Shizuku UserService。
     */
    /**
     * 断开与 daemon UserService 的连接，但**不销毁**远端进程。
     * daemon 进程和其中的 VirtualDisplay 将继续存活，
     * 下次 bindUserService 可以重新连回。
     */
    fun unbindUserService() {
        val args = userServiceArgs ?: return
        try {
            // destroy = false → 只断开连接，不杀死 daemon 进程
            Shizuku.unbindUserService(args, serviceConnection, false)
            displayService = null
            synchronized(pendingCallbacks) { pendingCallbacks.clear() }
            Log.d(TAG, "unbindUserService called (daemon kept alive)")
        } catch (e: Exception) {
            Log.e(TAG, "unbindUserService failed", e)
        }
    }

    /**
     * 显式销毁 daemon UserService 进程。
     * 这会释放所有 VirtualDisplay 并终止远端进程。
     * 仅在用户明确要求"停止服务"时调用。
     */
    fun destroyService() {
        val args = userServiceArgs ?: return
        try {
            // 先通知远端释放所有显示器
            try { displayService?.destroy() } catch (_: Exception) {}
            // destroy = true → 销毁 daemon 进程
            Shizuku.unbindUserService(args, serviceConnection, true)
            displayService = null
            synchronized(pendingCallbacks) { pendingCallbacks.clear() }
            Log.d(TAG, "destroyService: daemon process destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "destroyService failed", e)
        }
    }

    /**
     * 判断 UserService 是否已就绪。
     */
    fun isUserServiceReady(): Boolean = displayService != null

    /**
     * 在 UserService 就绪时执行 [callback]。
     * - 若已就绪，立即在当前线程执行。
     * - 若未就绪，加入待执行队列，等 [onServiceConnected] 触发后批量执行。
     *
     * 解决的竞态：调用方先 bindUserService（异步）再立即使用 service 时，
     * service 尚未连接导致操作静默失败。
     */
    fun whenReady(callback: () -> Unit) {
        if (displayService != null) {
            callback()
        } else {
            synchronized(pendingCallbacks) {
                // 二次检查，避免在 synchronized 外和内之间 service 完成连接
                if (displayService != null) {
                    callback()
                } else {
                    pendingCallbacks.add(callback)
                    Log.d(TAG, "whenReady: service not ready, queued callback (total=${pendingCallbacks.size})")
                }
            }
        }
    }

    // ---- 结果类型 ----

    sealed class CreateResult {
        data class Success(val displayId: Int) : CreateResult()
        data class Error(val message: String) : CreateResult()
        data object PermissionRequested : CreateResult()
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    // ---- 通过 UserService 创建（含 Surface）----

    /**
     * 通过 Shizuku UserService，在特权进程中反射 DisplayManager 构造函数，
     * 创建携带 [surface] 的虚拟显示器。
     */
    fun createVirtualDisplayWithSurface(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int = buildDefaultFlags()
    ): CreateResult {
        if (!isShizukuAvailable()) return CreateResult.Error("Shizuku 未运行")
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return CreateResult.PermissionRequested
        }

        val svc = displayService ?: return CreateResult.Error("UserService 未就绪，请稍后重试")

        return try {
            Log.d(TAG, "createVirtualDisplayWithSurface: $name ${width}x${height} dpi=$dpi flags=$flags")
            val displayId = svc.createVirtualDisplay(name, width, height, dpi, surface, flags)
            if (displayId != -1) {
                CreateResult.Success(displayId)
            } else {
                CreateResult.Error("UserService 返回 displayId=-1，创建失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplayWithSurface failed", e)
            CreateResult.Error("执行失败: ${e.message}")
        }
    }

    /**
     * 为已有的虚拟显示器设置 Surface。
     */
    fun setVirtualDisplaySurface(displayId: Int, surface: Surface?): Boolean {
        if (!isShizukuAvailable()) return false
        val svc = displayService ?: return false

        return try {
            Log.d(TAG, "setVirtualDisplaySurface: displayId=$displayId hasSurface=${surface != null}")
            svc.setVirtualDisplaySurface(displayId, surface)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setVirtualDisplaySurface failed", e)
            false
        }
    }

    /**
     * 通过 UserService 释放指定 displayId 的虚拟显示器。
     */
    fun releaseVirtualDisplayViaService(displayId: Int) {
        try {
            displayService?.releaseVirtualDisplay(displayId)
            Log.d(TAG, "Requested release of displayId=$displayId via UserService")
        } catch (e: Exception) {
            Log.e(TAG, "releaseVirtualDisplayViaService failed", e)
        }
    }

    /**
     * 通过 UserService 注入输入事件。
     */
    fun injectInputEvent(event: InputEvent, mode: Int = 0): Boolean {
        return try {
            displayService?.injectInputEvent(event, mode) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed", e)
            false
        }
    }

    // ---- 原有 IDisplayManager Binder 方式（创建无 Surface 的显示）----

    /**
     * 通过 UserService 启动 Activity。
     */
    fun startActivity(intent: Intent, options: Bundle?): Int {
        return try {
            displayService?.startActivity(intent, options) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "startActivity failed", e)
            0
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int
    ): CreateResult {
        if (!isShizukuAvailable()) {
            return CreateResult.Error("Shizuku 未运行")
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return CreateResult.PermissionRequested
        }

        val svc = displayService ?: return CreateResult.Error("UserService 未就绪，请稍后重试 (若刚授予权限，可能需等待几秒)")

        return try {
            val flags = buildDefaultFlags()
            Log.d(TAG, "Calling createVirtualDisplay via UserService with flags: $flags")
            val displayId = svc.createVirtualDisplay(name, width, height, dpi, null, flags)

            if (displayId != -1) {
                CreateResult.Success(displayId)
            } else {
                CreateResult.Error("系统返回无效 Display ID (-1)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "UserService call failed", e)
            CreateResult.Error("执行失败: ${e.message}")
        }
    }

    @SuppressLint("PrivateApi")
    fun releaseVirtualDisplay(displayId: Int) {
        releaseVirtualDisplayViaService(displayId)
    }

    /**
     * 获取当前 UserService 实例实际管理的 displayId 列表。
     * 返回空列表说明 service 未就绪或查询失败。
     */
    fun getServiceManagedDisplayIds(): Set<Int> {
        return try {
            displayService?.activeDisplayIds?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "getActiveDisplayIds failed", e)
            emptySet()
        }
    }

    // ---- 工具函数 ----

    fun buildDefaultFlags(): Int {
        var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or
                VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
                VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
                VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS

        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                    VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                    VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                        VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
            }
        }
        return flags
    }
}
