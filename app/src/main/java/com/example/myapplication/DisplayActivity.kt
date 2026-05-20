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

    // 辅助证明黑屏问题的诊断变量
    private var lastFrameTimeMs = 0L
    private var totalFrameCount = 0L
    private var isBlackScreenWarningLogged = false
    private var frameMonitoringJob: kotlinx.coroutines.Job? = null
    private var activeSurface: Surface? = null

    private lateinit var rootLayout: FrameLayout
    private lateinit var textureView: TextureView

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "[DIAGNOSTIC] onDisplayAdded: $displayId")
        }
        override fun onDisplayRemoved(displayId: Int) {
            Log.w(TAG, "[DIAGNOSTIC] onDisplayRemoved: $displayId")
            if (displayId == remoteDisplayId) {
                Log.w(TAG, "[DIAGNOSTIC] Remote display removed, finishing activity")
                finish()
            }
        }
        override fun onDisplayChanged(displayId: Int) {
            Log.d(TAG, "[DIAGNOSTIC] onDisplayChanged: $displayId")
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

        // 监听连接状态以做诊断
        lifecycleScope.launch {
            ShizukuDisplayBridge.connectionStatus.collect { status ->
                Log.d(TAG, "[DIAGNOSTIC] ShizukuDisplayBridge connection status updated: $status")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startFrameMonitoring()
    }

    override fun onStop() {
        super.onStop()
        stopFrameMonitoring()
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

    private fun startFrameMonitoring() {
        frameMonitoringJob?.cancel()
        frameMonitoringJob = lifecycleScope.launch(Dispatchers.Main) {
            Log.d(TAG, "[DIAGNOSTIC] Starting frame monitoring...")
            var consecutiveBlackFrames = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                val lastFrame = lastFrameTimeMs
                val surface = activeSurface
                if (lastFrame > 0) {
                    val elapsed = System.currentTimeMillis() - lastFrame
                    if (elapsed > 3000) {
                        if (!isBlackScreenWarningLogged) {
                            Log.e(TAG, "[DIAGNOSTIC] Black screen/Freeze detected! No frame updates on TextureView for $elapsed ms. Total frames rendered so far: $totalFrameCount. Active surface is valid: ${surface?.isValid == true}")
                            isBlackScreenWarningLogged = true
                        }
                    } else {
                        // 视频流正常更新，此时抽样检测渲染内容是否全黑
                        val isBlack = checkIsFrameBlack()
                        if (isBlack == true) {
                            consecutiveBlackFrames++
                            if (consecutiveBlackFrames >= 3) {
                                Log.w(TAG, "[DIAGNOSTIC] Frame updates are active, but rendered content has been PURE BLACK for $consecutiveBlackFrames seconds! (Total frames: $totalFrameCount)")
                            }
                        } else if (isBlack == false) {
                            if (consecutiveBlackFrames >= 3) {
                                Log.i(TAG, "[DIAGNOSTIC] Frame content recovered from black: detected non-black frame. (Total frames: $totalFrameCount)")
                            }
                            consecutiveBlackFrames = 0
                        }
                    }
                } else {
                    Log.d(TAG, "[DIAGNOSTIC] Monitoring: No frames received yet. Active surface is valid: ${surface?.isValid == true}")
                }
            }
        }
    }

    private fun stopFrameMonitoring() {
        Log.d(TAG, "[DIAGNOSTIC] Stopping frame monitoring...")
        frameMonitoringJob?.cancel()
        frameMonitoringJob = null
    }

    private fun checkIsFrameBlack(): Boolean? {
        if (!::textureView.isInitialized || !textureView.isAvailable) return null
        return try {
            // 通过获取 16x16 的像素样本来快速判断画面是否全黑
            val bitmap = textureView.getBitmap(16, 16) ?: return null
            var allBlack = true
            for (x in 0 until bitmap.width) {
                for (y in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    // 允许极小的分量误差以过滤压缩噪点（此处阈值设为 5）
                    if (r > 5 || g > 5 || b > 5) {
                        allBlack = false
                        break
                    }
                }
                if (!allBlack) break
            }
            bitmap.recycle()
            allBlack
        } catch (e: Exception) {
            null
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
                    Log.d(TAG, "[DIAGNOSTIC] onSurfaceTextureAvailable: ${width}x${height}")
                    if (vdWidth > 0 && vdHeight > 0) {
                        surface.setDefaultBufferSize(vdWidth, vdHeight)
                    }
                    val newSurface = Surface(surface)
                    activeSurface = newSurface
                    lastFrameTimeMs = System.currentTimeMillis()
                    isBlackScreenWarningLogged = false
                    lifecycleScope.launch(Dispatchers.Main.immediate) {
                        Log.d(TAG, "Calling setDisplaySurface: displayId=$displayId")
                        val result = ShizukuDisplayBridge.setDisplaySurface(displayId, newSurface)
                        Log.d(TAG, "[DIAGNOSTIC] setDisplaySurface result: $result")
                        result.exceptionOrNull()?.let { error ->
                            Log.e(TAG, "[DIAGNOSTIC] Failed to set display surface", error)
                        }
                    }
                    updateSurfaceLayout()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "[DIAGNOSTIC] onSurfaceTextureSizeChanged: ${width}x${height}")
                    updateSurfaceLayout()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    Log.d(TAG, "[DIAGNOSTIC] onSurfaceTextureDestroyed")
                    activeSurface = null
                    lifecycleScope.launch(Dispatchers.Main.immediate) {
                        val result = ShizukuDisplayBridge.setDisplaySurface(displayId, null)
                        Log.d(TAG, "Cleared display surface, result=$result")
                    }
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    lastFrameTimeMs = System.currentTimeMillis()
                    totalFrameCount++
                    if (isBlackScreenWarningLogged) {
                        Log.i(TAG, "[DIAGNOSTIC] Frame updates resumed after freeze. Total frames: $totalFrameCount")
                        isBlackScreenWarningLogged = false
                    }
                }
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

        // 添加悬浮可移动的退出按钮
        val exitButton = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#80000000"))
            }
            setPadding(40, 40, 40, 40)
            elevation = 10f
            
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, 150, 50, 0)
            }
            
            var dX = 0f
            var dY = 0f
            var clickTime = 0L
            var initialX = 0f
            var initialY = 0f
            
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - event.rawX
                        dY = view.y - event.rawY
                        initialX = event.rawX
                        initialY = event.rawY
                        clickTime = System.currentTimeMillis()
                        view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        view.animate()
                            .x(event.rawX + dX)
                            .y(event.rawY + dY)
                            .setDuration(0)
                            .start()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        val diffX = kotlin.math.abs(event.rawX - initialX)
                        val diffY = kotlin.math.abs(event.rawY - initialY)
                        if (event.actionMasked == MotionEvent.ACTION_UP &&
                            System.currentTimeMillis() - clickTime < 200 &&
                            diffX < 20 && diffY < 20
                        ) {
                            finish()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        rootLayout.addView(exitButton)

        setContentView(rootLayout)
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val displayId = remoteDisplayId ?: return false
        val spec = activeDisplaySpec ?: run {
            Log.w(TAG, "handleTouchEvent: activeDisplaySpec is null")
            return false
        }

        val isInside = spec.contains(event.x, event.y)
        Log.d(TAG, "handleTouchEvent: action=${event.actionMasked}, rawX=${event.rawX}, rawY=${event.rawY}, isInside=$isInside, touchGestureStarted=$touchGestureStarted")
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
            Log.w(TAG, "mapMotionEvent returned null")
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
                Log.d(TAG, "injectTouchEvent: action=${event.action}, deviceId=${event.deviceId}, source=${event.source}, targetDisplayId=$displayId")
                val result = ShizukuDisplayBridge.injectInputWithDisplayId(event, displayId)
                Log.d(TAG, "injectTouchEvent result: $result")
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