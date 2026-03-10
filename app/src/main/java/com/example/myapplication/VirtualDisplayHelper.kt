package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Surface

object VirtualDisplayHelper {
    private const val TAG = "VirtualDisplayHelper"

    /**
     * 为给定的远端 VirtualDisplay 设置渲染 Surface。
     * 当 SurfaceView 准备好时传入 surface，当销毁时传入 null。
     */
    fun setDisplaySurface(displayId: Int, surface: Surface?) {
        ShizukuDisplayBridge.setVirtualDisplaySurface(displayId, surface)
    }

    /**
     * 释放通过 [mirrorDisplay] 创建的远端虚拟显示器。
     */
    fun releaseRemoteDisplay(displayId: Int) {
        ShizukuDisplayBridge.releaseVirtualDisplayViaService(displayId)
    }

    data class SimpleDisplayInfo(val displayId: Int, val name: String, val width: Int, val height: Int)

    fun getDisplayInfo(context: Context, displayId: Int): SimpleDisplayInfo? {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(displayId) ?: return null
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            SimpleDisplayInfo(displayId, display.name, size.x, size.y)
        } catch (e: Exception) {
            null
        }
    }

    fun launchAppOnDisplay(context: Context, packageName: String, displayId: Int): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val options = android.app.ActivityOptions.makeBasic()
                val method = options.javaClass.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                method.invoke(options, displayId)
                val result = ShizukuDisplayBridge.startActivity(intent, options.toBundle())
                if (result == 0) {
                    Log.d(TAG, "Successfully launched app $packageName on remote display $displayId")
                    true
                } else {
                    Log.e(TAG, "Failed to launch app $packageName: status $result")
                    false
                }
            } else {
                Log.e(TAG, "Launch intent not found for $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app $packageName on display $displayId", e)
            false
        }
    }

    fun releaseAll() {
        Log.d(TAG, "releaseAll called (remote displays released via ShizukuDisplayBridge.unbindUserService)")
    }
}
