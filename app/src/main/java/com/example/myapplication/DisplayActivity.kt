package com.example.myapplication

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class DisplayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DisplayActivity"
    }

    private var remoteDisplayId: Int? = null
    private var vdWidth = 0
    private var vdHeight = 0
    private var activeDisplaySpec: DisplaySpec? = null
    private var touchGestureStarted = false

    private lateinit var rootLayout: FrameLayout
    private lateinit var textureView: TextureView

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == remoteDisplayId) finish()
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == remoteDisplayId) updateDisplayInfo(displayId)
        }
    }

    private fun updateDisplayInfo(displayId: Int) {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.getDisplay(displayId)?.let { display ->
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            if (vdWidth != size.x || vdHeight != size.y) {
                Log.d(TAG, "Virtual Display #$displayId Resized: ${size.x}x${size.y}")
                vdWidth = size.x
                vdHeight = size.y
                updateSurfaceLayout()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra("display_id", -1)
        require(displayId != -1) { "Invalid display_id" }
        remoteDisplayId = displayId

        enterFullscreen()
        setupContentView(displayId)

        ShizukuDisplayBridge.bindService(this)

        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)
        updateDisplayInfo(displayId)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onDestroy() {
        super.onDestroy()
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.unregisterDisplayListener(displayListener)
        remoteDisplayId?.let { id ->
            lifecycleScope.launch(Dispatchers.Main.immediate + NonCancellable) {
                ShizukuDisplayBridge.setDisplaySurface(id, null)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterFullscreen()
            updateSurfaceLayout()
        }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupContentView(displayId: Int) {
        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateSurfaceLayout()
            }
        }

        // 核心变动：改用 TextureView 接管底层渲染
        textureView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    if (vdWidth > 0 && vdHeight > 0) {
                        surface.setDefaultBufferSize(vdWidth, vdHeight)
                    }
                    lifecycleScope.launch(Dispatchers.Main.immediate) {
                        // TextureView 暴露的是 SurfaceTexture，需要包装成 Surface 传给 Bridge
                        val result = ShizukuDisplayBridge.setDisplaySurface(displayId, Surface(surface))
                        result.exceptionOrNull()?.let { error ->
                            Log.e(TAG, "Failed to set display surface", error)
                        }
                    }
                    updateSurfaceLayout()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    updateSurfaceLayout()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    lifecycleScope.launch(Dispatchers.Main.immediate) {
                        ShizukuDisplayBridge.setDisplaySurface(displayId, null)
                    }
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }

        rootLayout.setOnTouchListener { _, event ->
            handleTouchEvent(event)
        }

        rootLayout.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        setContentView(rootLayout)
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val displayId = remoteDisplayId ?: return false
        val spec = activeDisplaySpec ?: return false

        val isInside = spec.contains(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInside) return false
                touchGestureStarted = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!touchGestureStarted || !isInside) return false
            }

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!touchGestureStarted) return false
            }
        }

        val mappedEvent = mapMotionEvent(event, spec) ?: run {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchGestureStarted = false
            }
            return false
        }

        injectTouchEvent(mappedEvent, displayId)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchGestureStarted = false
        }
        return true
    }

    private fun mapMotionEvent(event: MotionEvent, spec: DisplaySpec): MotionEvent? {
        val pointerCount = event.pointerCount
        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        for (index in 0 until pointerCount) {
            event.getPointerProperties(index, pointerProperties[index])
            event.getPointerCoords(index, pointerCoords[index])

            val mappedPoint = mapPointToSource(
                x = pointerCoords[index].x,
                y = pointerCoords[index].y,
                spec = spec,
            ) ?: return null

            pointerCoords[index].x = mappedPoint.x
            pointerCoords[index].y = mappedPoint.y
        }

        return MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            pointerCount,
            pointerProperties,
            pointerCoords,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            InputDevice.SOURCE_TOUCHSCREEN,
            event.flags,
        )
    }

    private fun mapPointToSource(x: Float, y: Float, spec: DisplaySpec): PointF? {
        if (!spec.contains(x, y) && !touchGestureStarted) {
            return null
        }

        val pts = floatArrayOf(x, y)
        spec.inverseMatrix.mapPoints(pts)

        return PointF(
            pts[0].coerceIn(0f, spec.sourceWidth),
            pts[1].coerceIn(0f, spec.sourceHeight)
        )
    }

    private fun injectTouchEvent(event: MotionEvent, displayId: Int) {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            try {
                val result = ShizukuDisplayBridge.injectInputWithDisplayId(event, displayId)
                if (result.isFailure || result.getOrDefault(false).not()) {
                    Log.e(TAG, result.exceptionOrNull()?.message ?: "触摸事件注入失败")
                }
            } finally {
                event.recycle()
            }
        }
    }

    private fun updateSurfaceLayout() {
        if (!::rootLayout.isInitialized || !::textureView.isInitialized) {
            return
        }

        val sourceW = vdWidth.toFloat()
        val sourceH = vdHeight.toFloat()
        if (sourceW <= 0f || sourceH <= 0f) {
            return
        }

        // 强行约束底层 Texture 的真实渲染分辨率，防止图像本身畸变
        textureView.surfaceTexture?.setDefaultBufferSize(vdWidth, vdHeight)

        val viewW = rootLayout.width.takeIf { it > 0 }?.toFloat() ?: return
        val viewH = rootLayout.height.takeIf { it > 0 }?.toFloat() ?: return

        val rotated = sourceW > sourceH
        val visualSourceWidth = if (rotated) sourceH else sourceW
        val visualSourceHeight = if (rotated) sourceW else sourceH

        val scale = minOf(viewW / visualSourceWidth, viewH / visualSourceHeight)

        // ---------------------------------------------------------
        // 核心修复：通过 Texture Matrix 直接操作图像渲染的旋转与缩放
        // ---------------------------------------------------------
        val textureMatrix = Matrix()
        // TextureView 默认会做全屏拉伸，所以我们先将被拉伸的坐标原点移到中心
        textureMatrix.postTranslate(-viewW / 2f, -viewH / 2f)
        // 抵消拉伸形变，让画面在视觉上恢复回纯正的 sourceW x sourceH 比例
        textureMatrix.postScale(sourceW / viewW, sourceH / viewH)
        // 执行物理方向上的旋转 (此时才真正使得 1280x720 翻转)
        if (rotated) textureMatrix.postRotate(90f)
        // 放大/缩小画面以等比适配屏幕 (Fit-Center)
        textureMatrix.postScale(scale, scale)
        // 将原点从中心移回左上角
        textureMatrix.postTranslate(viewW / 2f, viewH / 2f)

        textureView.setTransform(textureMatrix)


        // ---------------------------------------------------------
        // 建立触摸映射的逆向矩阵 (数学过程和上方完全对齐)
        // ---------------------------------------------------------
        val touchMatrix = Matrix()
        touchMatrix.postTranslate(-sourceW / 2f, -sourceH / 2f)
        if (rotated) touchMatrix.postRotate(90f)
        touchMatrix.postScale(scale, scale)
        touchMatrix.postTranslate(viewW / 2f, viewH / 2f)

        val inverseMatrix = Matrix()
        touchMatrix.invert(inverseMatrix)

        val targetWidth = visualSourceWidth * scale
        val targetHeight = visualSourceHeight * scale

        activeDisplaySpec = DisplaySpec(
            sourceWidth = sourceW,
            sourceHeight = sourceH,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            left = (viewW - targetWidth) / 2f,
            top = (viewH - targetHeight) / 2f,
            inverseMatrix = inverseMatrix
        )
    }
}

// 废弃了所有冗余的 Layout 宽高记录，现在全部由底层的 Matrix 消化
private data class DisplaySpec(
    val sourceWidth: Float,
    val sourceHeight: Float,
    val targetWidth: Float,
    val targetHeight: Float,
    val left: Float,
    val top: Float,
    val inverseMatrix: Matrix
) {
    fun contains(x: Float, y: Float): Boolean {
        return x in left..(left + targetWidth) && y in top..(top + targetHeight)
    }
}