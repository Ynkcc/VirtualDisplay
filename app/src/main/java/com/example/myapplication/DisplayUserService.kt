package com.example.myapplication

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import android.content.Intent
import android.os.Bundle
import androidx.annotation.Keep
import com.genymobile.scrcpy.Workarounds
import com.genymobile.scrcpy.wrappers.ServiceManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Shizuku UserService 运行在特权进程中。
 * 必须提供一个带有 Context 参数的构造函数。
 */
class DisplayUserService @Keep constructor(context: Context) : IDisplayService.Stub() {

    companion object {
        private const val tag = "DisplayUserService"
        private val activeDisplays = mutableMapOf<Int, VirtualDisplay>()
    }

    init {
        Log.d(tag, "DisplayUserService initializing in process: ${getCurrentProcessName()}")
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        } catch (t: Throwable) {
            Log.e(tag, "Failed to bypass hidden api", t)
        }

        try {
            // Apply scrcpy's Android environment workarounds for bare Java process wrappers
            Workarounds.apply()
            Log.d(tag, "Scrcpy workarounds applied successfully")
        } catch (t: Throwable) {
            Log.e(tag, "Failed to apply Scrcpy workarounds", t)
        }
    }

    private fun getCurrentProcessName(): String {
        return if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() ?: "unknown" else "unknown"
    }

    override fun setVirtualDisplaySurface(displayId: Int, surface: Surface?) {
        val vd = activeDisplays[displayId]
        if (vd != null) {
            try {
                vd.surface = surface
                Log.d(tag, "Successfully set surface for VirtualDisplay id=$displayId")
            } catch (e: Exception) {
                Log.e(tag, "Failed to set surface for VirtualDisplay id=$displayId", e)
            }
        } else {
            Log.w(tag, "setVirtualDisplaySurface: VirtualDisplay id=$displayId not found in this service instance (orphan?)")
        }
    }

    override fun createVirtualDisplay(
        name: String?,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
        flags: Int
    ): Int {
        Log.d(tag, "createVirtualDisplay: name=$name, size=${width}x${height}, dpi=$dpi, hasSurface=${surface != null}, flags=$flags")
        return try {
            val displayManager = ServiceManager.getDisplayManager()
            // 使用 scrcpy wrapper 创建虚拟显示器
            val vd = displayManager.createNewVirtualDisplay(name ?: "vd", width, height, dpi, surface, flags)
            val displayId = vd.display.displayId
            activeDisplays[displayId] = vd
            Log.d(tag, "Successfully created VirtualDisplay id=$displayId, total managed=${activeDisplays.size}")
            displayId
        } catch (e: Throwable) {
            Log.e(tag, "Failed to create VirtualDisplay using scrcpy wrappers", e)
            -1
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        val vd = activeDisplays.remove(displayId)
        if (vd != null) {
            try {
                vd.release()
                Log.d(tag, "Released VirtualDisplay id=$displayId, remaining=${activeDisplays.size}")
            } catch (e: Exception) {
                Log.e(tag, "Failed to release VirtualDisplay id=$displayId", e)
            }
        } else {
            // 孤儿显示器：本 service 实例不持有该 VirtualDisplay 的句柄。
            // 这通常是因为产生了 service 进程重建（App 重启导致旧的 activeDisplays 丢失）。
            // 在这种情况下无法通过 Java API 销毁，系统侧的 VirtualDisplay 会在其宿主进程死亡时自动清理。
            Log.w(tag, "releaseVirtualDisplay: displayId=$displayId not found in this service instance. " +
                "This is an orphan display from a previous service lifecycle. " +
                "It cannot be released without its original VirtualDisplay handle.")
        }
    }

    override fun injectInputEvent(event: InputEvent?, mode: Int): Boolean {
        if (event == null) return false
        return try {
            ServiceManager.getInputManager().injectInputEvent(event, mode)
        } catch (e: Exception) {
            Log.e(tag, "Failed to inject input event", e)
            false
        }
    }

    override fun startActivity(intent: Intent?, options: Bundle?): Int {
        if (intent == null) return 0
        return try {
            ServiceManager.getActivityManager().startActivity(intent, options)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start activity", e)
            0
        }
    }

    /**
     * 返回当前 service 实例实际持有句柄的 displayId 列表。
     * 调用方可用此列表与系统 DisplayManager.getDisplays() 对比，
     * 找出"孤儿显示器"（系统中存在但本实例无法管理的显示器）。
     */
    override fun getActiveDisplayIds(): IntArray {
        return activeDisplays.keys.toIntArray().also {
            Log.d(tag, "getActiveDisplayIds: returning ${it.size} ids: ${it.toList()}")
        }
    }

    override fun destroy() {
        Log.d(tag, "destroy() called, releasing all displays")
        // 先逐一释放，确保系统侧 VirtualDisplay 得到清理
        val ids = activeDisplays.keys.toList()
        ids.forEach { id ->
            val vd = activeDisplays.remove(id)
            try {
                vd?.release()
                Log.d(tag, "destroy: Released VirtualDisplay id=$id")
            } catch (_: Exception) {}
        }
        activeDisplays.clear()
        // daemon 模式：不调用 System.exit(0)。
        // 进程的生命周期由 Shizuku 管理（unbindUserService(destroy=true) 时会终止进程）。
        // 如果上层通过 ShizukuDisplayBridge.destroyService() 调用，
        // Shizuku 会在 unbind 后自动杀死本进程。
        Log.d(tag, "destroy: All displays released, daemon process remains alive for reuse")
    }
}
