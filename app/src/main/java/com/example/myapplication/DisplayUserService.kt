package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import androidx.annotation.Keep
import com.genymobile.scrcpy.Workarounds
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.wrappers.ServiceManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class DisplayUserService @Keep constructor(context: Context) : IDisplayService.Stub() {

    companion object {
        private const val TAG = "DisplayUserService"
        private val activeDisplays = mutableMapOf<Int, VirtualDisplay>()
    }

    init {
        Log.d(TAG, "DisplayUserService init in process: ${getCurrentProcessName()}")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        Workarounds.apply()
        Log.d(TAG, "Workarounds applied successfully")
    }

    private fun getCurrentProcessName(): String {
        return if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() ?: "unknown" else "unknown"
    }

    override fun setVirtualDisplaySurface(displayId: Int, surface: Surface?) {
        Log.d(TAG, "setVirtualDisplaySurface: displayId=$displayId, surface=$surface")
        val vd = activeDisplays[displayId]
        if (vd == null) {
            Log.e(TAG, "setVirtualDisplaySurface: Display $displayId not found in activeDisplays")
            return
        }
        vd.surface = surface
    }

    override fun createVirtualDisplay(name: String?, width: Int, height: Int, dpi: Int, surface: Surface?, flags: Int): Int {
        Log.d(TAG, "createVirtualDisplay: name=$name, size=${width}x$height, dpi=$dpi, flags=0x${Integer.toHexString(flags)}")
        val displayManager = ServiceManager.getDisplayManager()
        val vd = displayManager.createNewVirtualDisplay(name ?: "vd", width, height, dpi, surface, flags)
        val displayId = vd.display.displayId
        activeDisplays[displayId] = vd
        Log.d(TAG, "Created virtual display: id=$displayId")
        return displayId
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        Log.d(TAG, "releaseVirtualDisplay: displayId=$displayId")
        val vd = activeDisplays.remove(displayId)
        if (vd != null) {
            vd.release()
            Log.d(TAG, "Display $displayId released successfully")
        } else {
            Log.w(TAG, "releaseVirtualDisplay: Display $displayId not found in activeDisplays map. It might be a 'zombie' display or already released.")
            // 注意：如果没有 VirtualDisplay 引用，在 Android 中很难通过 IDisplayManager 远程释放一个 VirtualDisplay，
            // 除非进程退出导致系统自动回收。
        }
    }

    override fun injectInputEvent(event: InputEvent, mode: Int): Boolean {
        Log.v(TAG, "injectInputEvent: $event, mode=$mode")
        return try {
            ServiceManager.getInputManager().injectInputEvent(event, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input event", e)
            false
        }
    }

    /**
     * 复用 scrcpy Device.injectKeyEvent：在特权进程内构造 KeyEvent，
     * 通过 InputEvent.setDisplayId() 绑定目标屏幕后再注入，保证路由到正确 display。
     */
    override fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean {
        Log.v(TAG, "injectKeyEvent: action=$action, keyCode=$keyCode, displayId=$displayId, mode=$mode")
        return try {
            Device.injectKeyEvent(action, keyCode, repeat, metaState, displayId, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject key event", e)
            false
        }
    }

    /**
     * 复用 scrcpy Device.injectEvent：在特权进程内对 InputEvent（含 MotionEvent）
     * 调用 setDisplayId，避免 app 进程反射失效导致触摸路由到默认屏幕。
     */
    override fun injectInputEventWithDisplayId(event: InputEvent, displayId: Int, mode: Int): Boolean {
        Log.v(TAG, "injectInputEventWithDisplayId: displayId=$displayId, mode=$mode, event=$event")
        return try {
            Device.injectEvent(event, displayId, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input event with displayId", e)
            false
        }
    }

    override fun startActivity(intent: Intent, options: Bundle?): Int {
        Log.d(TAG, "startActivity: intent=$intent")
        return try {
            val result = ServiceManager.getActivityManager().startActivity(intent, options)
            Log.d(TAG, "startActivity result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity", e)
            -1
        }
    }

    override fun getActiveDisplayIds(): IntArray {
        val ids = activeDisplays.keys.toIntArray()
        Log.v(TAG, "getActiveDisplayIds: ${ids.contentToString()}")
        return ids
    }

    override fun destroy() {
        Log.d(TAG, "DisplayUserService destroy: releasing ${activeDisplays.size} displays")
        val iterator = activeDisplays.values.iterator()
        while (iterator.hasNext()) {
            val vd = iterator.next()
            try {
                vd.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing display during service destroy", e)
            }
            iterator.remove()
        }
        activeDisplays.clear()
    }
}
