package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.Surface
import androidx.annotation.Keep
import com.genymobile.scrcpy.Workarounds
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.wrappers.ServiceManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.ConcurrentHashMap

class DisplayUserService @Keep constructor(private val context: Context) : IDisplayService.Stub() {

    private data class DisplayRecord(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val flags: Int,
        val createdAtMs: Long,
        var lastSeenAtMs: Long,
        var isOrphan: Boolean,
    )

    companion object {
        private const val TAG = "DisplayUserService"
        private val KNOWN_MANAGED_NAME_PREFIXES = listOf("Shizuku_VD_", "vd")
    }

    private val displayManager by lazy {
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val activeDisplays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val knownDisplays = ConcurrentHashMap<Int, DisplayRecord>()

    init {
        try {
            Log.d(TAG, "DisplayUserService init in process: ${getCurrentProcessName()}")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
            Workarounds.apply()
            Log.d(TAG, "Workarounds applied successfully")
            reconcileKnownDisplaysWithSystem()
        } catch (e: Throwable) {
            Log.e(TAG, "Critical error during service initialization", e)
        }
    }

    private fun getCurrentProcessName(): String {
        return if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() ?: "unknown" else "unknown"
    }

    override fun setVirtualDisplaySurface(displayId: Int, surface: Surface?) {
        Log.d(TAG, "setVirtualDisplaySurface: displayId=$displayId, surface=$surface")
        val vd = activeDisplays[displayId]
        if (vd == null) {
            if (knownDisplays.containsKey(displayId)) {
                Log.w(TAG, "setVirtualDisplaySurface: Display $displayId is known but currently orphaned (no VirtualDisplay handle), skip")
            } else {
                Log.e(TAG, "setVirtualDisplaySurface: Display $displayId not found in registry")
            }
            return
        }
        val safeSurface = if (surface != null && !surface.isValid) {
            Log.w(TAG, "setVirtualDisplaySurface: received invalid Surface for displayId=$displayId, falling back to null")
            null
        } else {
            surface
        }
        try {
            vd.surface = safeSurface
            knownDisplays[displayId]?.apply {
                isOrphan = false
                lastSeenAtMs = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.e(TAG, "setVirtualDisplaySurface failed for displayId=$displayId", e)
            try {
                vd.surface = null
            } catch (_: Exception) {
            }
        }
    }

    override fun createVirtualDisplay(name: String?, width: Int, height: Int, dpi: Int, surface: Surface?, flags: Int): Int {
        Log.d(TAG, "createVirtualDisplay: name=$name, size=${width}x$height, dpi=$dpi, flags=0x${Integer.toHexString(flags)}")
        val displayManager = ServiceManager.getDisplayManager()
        val vd = displayManager.createNewVirtualDisplay(name ?: "vd", width, height, dpi, surface, flags)
        val displayId = vd.display.displayId
        registerDisplay(
            displayId = displayId,
            name = name ?: "vd",
            width = width,
            height = height,
            dpi = dpi,
            flags = flags,
            vd = vd,
        )
        Log.d(TAG, "Created virtual display: id=$displayId")
        return displayId
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        Log.d(TAG, "releaseVirtualDisplay: displayId=$displayId")
        val vd = activeDisplays.remove(displayId)
        if (vd != null) {
            try {
                vd.surface = null
            } catch (_: Exception) {
            }
            try {
                vd.release()
                knownDisplays.remove(displayId)
                Log.d(TAG, "Display $displayId released successfully")
            } catch (e: Exception) {
                Log.e(TAG, "releaseVirtualDisplay failed for active handle: displayId=$displayId", e)
                knownDisplays[displayId]?.isOrphan = true
                knownDisplays[displayId]?.lastSeenAtMs = System.currentTimeMillis()
            }
        } else {
            val known = knownDisplays[displayId]
            if (known == null) {
                Log.w(TAG, "releaseVirtualDisplay: Display $displayId not found in registry")
                return
            }
            val released = bestEffortReleaseOrphan(displayId)
            if (released) {
                knownDisplays.remove(displayId)
                Log.d(TAG, "releaseVirtualDisplay: orphan display $displayId released by fallback path")
            } else {
                known.isOrphan = true
                known.lastSeenAtMs = System.currentTimeMillis()
                Log.w(TAG, "releaseVirtualDisplay: orphan display $displayId cannot be released without recoverable handle")
            }
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

    override fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean {
        Log.v(TAG, "injectKeyEvent: action=$action, keyCode=$keyCode, displayId=$displayId, mode=$mode")
        return try {
            Device.injectKeyEvent(action, keyCode, repeat, metaState, displayId, mode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject key event", e)
            false
        }
    }

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
        reconcileKnownDisplaysWithSystem()
        val ids = knownDisplays.keys.toIntArray()
        Log.v(TAG, "getActiveDisplayIds: ${ids.contentToString()}")
        return ids
    }

    override fun destroy() {
        Log.d(TAG, "DisplayUserService destroy: releasing ${activeDisplays.size} displays")
        activeDisplays.values.forEach { vd ->
            try {
                vd.surface = null
            } catch (_: Exception) {
            }
            try {
                vd.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing display during service destroy", e)
            }
        }
        activeDisplays.clear()
        knownDisplays.clear()
        
        Log.i(TAG, "Service process exiting.")
        android.os.Process.killProcess(android.os.Process.myPid())
        java.lang.System.exit(0)
    }

    private fun registerDisplay(
        displayId: Int,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        vd: VirtualDisplay,
    ) {
        activeDisplays[displayId] = vd
        knownDisplays[displayId] = DisplayRecord(
            displayId = displayId,
            name = name,
            width = width,
            height = height,
            dpi = dpi,
            flags = flags,
            createdAtMs = System.currentTimeMillis(),
            lastSeenAtMs = System.currentTimeMillis(),
            isOrphan = false,
        )
    }

    private fun reconcileKnownDisplaysWithSystem() {
        val now = System.currentTimeMillis()
        val systemDisplays = displayManager.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        val systemIds = systemDisplays.map { it.displayId }.toSet()

        val removed = knownDisplays.keys.filter { it !in systemIds }
        removed.forEach {
            knownDisplays.remove(it)
            activeDisplays.remove(it)
        }

        systemDisplays.forEach { display ->
            val existing = knownDisplays[display.displayId]
            if (existing != null) {
                existing.lastSeenAtMs = now
                existing.isOrphan = activeDisplays[display.displayId] == null
            } else if (isLikelyManagedDisplay(display)) {
                knownDisplays[display.displayId] = DisplayRecord(
                    displayId = display.displayId,
                    name = display.name ?: "vd",
                    width = display.mode?.physicalWidth ?: 0,
                    height = display.mode?.physicalHeight ?: 0,
                    dpi = context.resources.displayMetrics.densityDpi,
                    flags = 0,
                    createdAtMs = now,
                    lastSeenAtMs = now,
                    isOrphan = true,
                )
                Log.w(TAG, "Discovered orphan display: id=${display.displayId}, name=${display.name}")
            }
        }
    }

    private fun isLikelyManagedDisplay(display: Display): Boolean {
        val displayName = display.name ?: return false
        return KNOWN_MANAGED_NAME_PREFIXES.any { displayName.startsWith(it) }
    }

    private fun bestEffortReleaseOrphan(displayId: Int): Boolean {
        return runCatching {
            val manager = ServiceManager.getDisplayManager()
            val candidates = manager.javaClass.methods.filter { method ->
                val params = method.parameterTypes
                val isIntOnly = params.size == 1 &&
                    (params[0] == Int::class.javaPrimitiveType || params[0] == Int::class.java)
                val name = method.name.lowercase()
                isIntOnly && (name.contains("release") || name.contains("remove"))
            }
            for (method in candidates) {
                try {
                    method.isAccessible = true
                    method.invoke(manager, displayId)
                    Log.w(TAG, "bestEffortReleaseOrphan succeeded via reflection method=${method.name}, displayId=$displayId")
                    return true
                } catch (_: Exception) {
                }
            }
            false
        }.getOrElse {
            Log.e(TAG, "bestEffortReleaseOrphan failed", it)
            false
        }
    }
}
