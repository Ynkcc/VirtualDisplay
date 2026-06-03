package com.ynk.virtualdisplay.core.ipc

import com.ynk.virtualdisplay.IDisplayService

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
import android.media.ImageReader
import android.graphics.PixelFormat
import android.os.HandlerThread
import android.os.Handler

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
        val imageReader: ImageReader? = null,
        val handlerThread: HandlerThread? = null
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
    private val activeSurfaces = ConcurrentHashMap<Int, Surface>() // 保存 Surface 强引用，避免 GC 析构
    private var diagnosticThread: Thread? = null

    init {
        try {
            Log.d(TAG, "DisplayUserService init in process: ${getCurrentProcessName()}")
            HiddenApiBypass.addHiddenApiExemptions("")
            Workarounds.apply()
            Log.d(TAG, "Workarounds applied successfully")
            reconcileKnownDisplaysWithSystem()
            startDiagnosticThread()
        } catch (e: Throwable) {
            Log.e(TAG, "Critical error during service initialization", e)
        }
    }

    private fun getCurrentProcessName(): String {
        return android.app.Application.getProcessName() ?: "unknown"
    }

    override fun setVirtualDisplaySurface(displayId: Int, surface: Surface?) {
        Log.d(TAG, "[DIAGNOSTIC] setVirtualDisplaySurface: displayId=$displayId, surface=$surface (surface.isValid=${surface?.isValid})")
        val vd = activeDisplays[displayId]
        if (vd == null) {
            if (knownDisplays.containsKey(displayId)) {
                Log.w(TAG, "[DIAGNOSTIC] setVirtualDisplaySurface: Display $displayId is known but currently orphaned (no VirtualDisplay handle), skip")
            } else {
                Log.e(TAG, "[DIAGNOSTIC] setVirtualDisplaySurface: Display $displayId not found in registry")
            }
            return
        }
        val record = knownDisplays[displayId]
        val targetSurface = if (surface == null || !surface.isValid) {
            record?.imageReader?.surface
        } else {
            surface
        }
        try {
            vd.surface = targetSurface
            if (targetSurface != null) {
                activeSurfaces[displayId] = targetSurface
                // 解决 Android 15 投屏黑屏问题：点亮虚拟显示器
                try {
                    val dm = ServiceManager.getDisplayManager()
                    val success = dm.requestDisplayPower(displayId, true)
                    Log.d(TAG, "[DIAGNOSTIC] requestDisplayPower for displayId=$displayId returned: $success")
                } catch (powerEx: Throwable) {
                    Log.w(TAG, "[DIAGNOSTIC] Failed to requestDisplayPower: ${powerEx.message}")
                }
            } else {
                activeSurfaces.remove(displayId)
            }
            knownDisplays[displayId]?.apply {
                isOrphan = false
                lastSeenAtMs = System.currentTimeMillis()
            }
            Log.d(TAG, "[DIAGNOSTIC] setVirtualDisplaySurface succeeded for displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "[DIAGNOSTIC] setVirtualDisplaySurface failed for displayId=$displayId", e)
            try {
                val fallback = record?.imageReader?.surface
                vd.surface = fallback
                if (fallback != null) {
                    activeSurfaces[displayId] = fallback
                } else {
                    activeSurfaces.remove(displayId)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun createVirtualDisplay(name: String?, width: Int, height: Int, dpi: Int, surface: Surface?, flags: Int): Int {
        val callingUid = android.os.Binder.getCallingUid()
        val myUid = android.os.Process.myUid()
        val myPackages = context.packageManager.getPackagesForUid(myUid)?.toList()
        Log.d(TAG, "[DIAG] createVirtualDisplay: callingUid=$callingUid, myUid=$myUid, myPackages=$myPackages")
        Log.d(TAG, "createVirtualDisplay: name=$name, size=${width}x$height, dpi=$dpi, flags=0x${Integer.toHexString(flags)}")
        val displayManager = ServiceManager.getDisplayManager()
        var reader: ImageReader? = null
        var thread: HandlerThread? = null
        val resolvedSurface = if (surface == null) {
            thread = HandlerThread("VDReader-$name").apply { start() }
            val handler = Handler(thread.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r?.acquireLatestImage()
                    img?.close()
                } catch (e: Throwable) {
                    Log.e(TAG, "Error acquiring/closing image", e)
                }
            }, handler)
            reader.surface
        } else {
            surface
        }
        val vd = displayManager.createNewVirtualDisplay(name ?: "vd", width, height, dpi, resolvedSurface, flags)
        val displayId = vd.display.displayId
        registerDisplay(
            displayId = displayId,
            name = name ?: "vd",
            width = width,
            height = height,
            dpi = dpi,
            flags = flags,
            vd = vd,
            imageReader = reader,
            handlerThread = thread
        )
        if (resolvedSurface != null) {
            activeSurfaces[displayId] = resolvedSurface
        }
        Log.d(TAG, "Created virtual display: id=$displayId")
        return displayId
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        Log.d(TAG, "releaseVirtualDisplay: displayId=$displayId")
        activeSurfaces.remove(displayId)
        val vd = activeDisplays.remove(displayId)
        val record = knownDisplays.remove(displayId)
        try {
            record?.imageReader?.close()
            record?.handlerThread?.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ImageReader/HandlerThread", e)
        }
        if (vd != null) {
            try {
                vd.surface = null
            } catch (_: Exception) {
            }
            try {
                vd.release()
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
        Log.d(TAG, "injectInputEventWithDisplayId: displayId=$displayId, mode=$mode, event=$event")
        return try {
            val result = Device.injectEvent(event, displayId, mode)
            Log.d(TAG, "Device.injectEvent returned: $result")
            result
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



    override fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, dpi: Int) {
        Log.d(TAG, "resizeVirtualDisplay: displayId=$displayId, size=${width}x$height, dpi=$dpi")
        val vd = activeDisplays[displayId]
        if (vd == null) {
            Log.e(TAG, "resizeVirtualDisplay failed: VirtualDisplay #$displayId not found")
            return
        }
        try {
            vd.resize(width, height, dpi)
            knownDisplays[displayId]?.let { old ->
                knownDisplays[displayId] = old.copy(width = width, height = height, dpi = dpi)
            }
            Log.d(TAG, "resizeVirtualDisplay succeeded for displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "resizeVirtualDisplay failed for displayId=$displayId", e)
        }
    }

    override fun destroy() {
        Log.d(TAG, "DisplayUserService destroy: releasing ${activeDisplays.size} displays")
        diagnosticThread?.interrupt()
        activeSurfaces.clear()
        knownDisplays.values.forEach { record ->
            try {
                record.imageReader?.close()
                record.handlerThread?.quitSafely()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up imageReader/handlerThread during destroy", e)
            }
        }
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
        imageReader: ImageReader? = null,
        handlerThread: HandlerThread? = null
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
            imageReader = imageReader,
            handlerThread = handlerThread
        )
    }

    private fun reconcileKnownDisplaysWithSystem() {
        val now = System.currentTimeMillis()
        val systemDisplays = displayManager.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        val systemIds = systemDisplays.map { it.displayId }.toSet()

        val removed = knownDisplays.keys.filter { it !in systemIds }
        removed.forEach {
            Log.w(TAG, "[DIAGNOSTIC] Display $it removed from system displays during reconciliation.")
            knownDisplays.remove(it)
            activeDisplays.remove(it)
        }

        systemDisplays.forEach { display ->
            val existing = knownDisplays[display.displayId]
            if (existing != null) {
                existing.lastSeenAtMs = now
                val wasOrphan = existing.isOrphan
                existing.isOrphan = activeDisplays[display.displayId] == null
                if (wasOrphan != existing.isOrphan) {
                    Log.w(TAG, "[DIAGNOSTIC] Display ${display.displayId} orphan state changed: wasOrphan=$wasOrphan, isOrphan=${existing.isOrphan}")
                }
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
                Log.w(TAG, "[DIAGNOSTIC] Discovered orphan display: id=${display.displayId}, name=${display.name}")
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

    private fun startDiagnosticThread() {
        diagnosticThread = Thread {
            Log.d(TAG, "[DIAGNOSTIC_SERVICE] Diagnostic thread started")
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(2000)
                    activeDisplays.forEach { (id, vd) ->
                        val display = vd.display
                        val surface = vd.surface
                        Log.d(TAG, "[DIAGNOSTIC_SERVICE] Display #$id: name=${display.name}, isValid=${display.isValid}, state=${display.state} (${displayStateToString(display.state)}), surface=$surface, surface.isValid=${surface?.isValid == true}")
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "[DIAGNOSTIC_SERVICE] Error in diagnostic thread", e)
                }
            }
            Log.d(TAG, "[DIAGNOSTIC_SERVICE] Diagnostic thread stopped")
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun displayStateToString(state: Int): String {
        return when (state) {
            Display.STATE_UNKNOWN -> "STATE_UNKNOWN"
            Display.STATE_OFF -> "STATE_OFF"
            Display.STATE_ON -> "STATE_ON"
            Display.STATE_DOZE -> "STATE_DOZE"
            Display.STATE_DOZE_SUSPEND -> "STATE_DOZE_SUSPEND"
            Display.STATE_VR -> "STATE_VR"
            else -> "UNKNOWN_VAL(${state})"
        }
    }
}
