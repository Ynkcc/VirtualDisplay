package com.ynk.virtualdisplay

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
import android.annotation.SuppressLint
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

@SuppressLint("ClickableViewAccessibility", "UseKtx", "QueryPermissionsNeeded")
class DisplayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DisplayActivity"
    }

    private var remoteDisplayId: Int? = null
    private var vdWidth = 0
    private var vdHeight = 0
    private var activeDisplaySpec: DisplaySpec? = null
    private var touchGestureStarted = false
    private var isResizing = false

    // 辅助证明黑屏问题的诊断变量
    private var lastFrameTimeMs = 0L
    private var totalFrameCount = 0L
    private var isBlackScreenWarningLogged = false
    private var frameMonitoringJob: kotlinx.coroutines.Job? = null
    private var activeSurface: Surface? = null

    private lateinit var rootLayout: FrameLayout
    private lateinit var textureView: TextureView
    private val repository: IDisplayRepository by lazy {
        (application as MyApplication).displayRepository
    }

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
            if (displayId == remoteDisplayId) {
                // DisplayListener 可能在非主线程回调，View 操作须切到主线程
                rootLayout.post { updateDisplayInfo(displayId) }
            }
        }
    }

    private fun updateDisplayInfo(displayId: Int) {
        if (isResizing) {
            Log.d(TAG, "updateDisplayInfo: Ignore because isResizing = true")
            return
        }
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.getDisplay(displayId)?.let { display ->
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            if (vdWidth != size.x || vdHeight != size.y) {
                Log.d(TAG, "Virtual Display #$displayId Resized: ${size.x}x${size.y}")
                vdWidth = size.x
                vdHeight = size.y
                val targetOrientation = if (vdWidth > vdHeight) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                if (requestedOrientation != targetOrientation) {
                    requestedOrientation = targetOrientation
                }
                textureView.surfaceTexture?.let { texture ->
                    isResizing = true
                    texture.setDefaultBufferSize(vdWidth, vdHeight)
                    val oldSurface = activeSurface
                    val newSurface = Surface(texture)
                    activeSurface = newSurface
                    lifecycleScope.launch(Dispatchers.Main.immediate) {
                        try {
                            Log.d(TAG, "Re-sizing virtual display and re-setting surface due to size change: displayId=$displayId, size=${vdWidth}x${vdHeight}")
                            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                            val dpi = dm.getDisplay(displayId)?.let { d ->
                                val metrics = android.util.DisplayMetrics()
                                @Suppress("DEPRECATION")
                                d.getMetrics(metrics)
                                metrics.densityDpi
                            } ?: android.util.DisplayMetrics.DENSITY_DEFAULT
                            
                            repository.resizeDisplay(displayId, vdWidth, vdHeight, dpi)
                            repository.setDisplaySurface(displayId, newSurface)
                            oldSurface?.release()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during resize and re-bind surface", e)
                        } finally {
                            kotlinx.coroutines.delay(300)
                            isResizing = false
                            checkLatestDisplayInfo(displayId)
                        }
                    }
                }
                updateSurfaceLayout()
            }
        }
    }

    private fun checkLatestDisplayInfo(displayId: Int) {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.getDisplay(displayId)?.let { display ->
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            if (vdWidth != size.x || vdHeight != size.y) {
                Log.d(TAG, "checkLatestDisplayInfo: detect size difference after resize finished, triggering update")
                updateDisplayInfo(displayId)
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

        repository.bindService(this)

        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)
        updateDisplayInfo(displayId)

        // 监听连接状态以做诊断
        lifecycleScope.launch {
            repository.connectionStatus.collect { status ->
                Log.d(TAG, "[DIAGNOSTIC] Shizuku connection status updated: $status")
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
                            
                            // 自动恢复：重设 Surface 绑定以唤醒系统渲染
                            lifecycleScope.launch(Dispatchers.Main) {
                                remoteDisplayId?.let { id ->
                                    val currentSurface = activeSurface
                                    if (currentSurface != null && currentSurface.isValid) {
                                        Log.i(TAG, "[DIAGNOSTIC] Attempting auto-recovery: resetting surface binding for displayId=$id")
                                        try {
                                            repository.setDisplaySurface(id, null)
                                            kotlinx.coroutines.delay(100)
                                            if (activeSurface == currentSurface && currentSurface.isValid) {
                                                repository.setDisplaySurface(id, currentSurface)
                                                Log.i(TAG, "[DIAGNOSTIC] Auto-recovery request sent successfully.")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "[DIAGNOSTIC] Auto-recovery failed", e)
                                        }
                                    }
                                }
                            }
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

    @Suppress("DEPRECATION")
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
                        val result = repository.setDisplaySurface(displayId, newSurface)
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
                    val oldSurface = activeSurface
                    activeSurface = null
                    if (isFinishing) {
                        oldSurface?.release()
                    } else {
                        Log.d(TAG, "onSurfaceTextureDestroyed: Keeping surface active because activity is not finishing")
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


        // 优化版悬浮可拖拽的控制面板 - 升级为多功能横向玻璃态胶囊面板
        val density = resources.displayMetrics.density
        val panelHeight = (46 * density).toInt()
        
        val controlPanel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 15f * density
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            
            // 采用高质感的半透明磨砂黑圆角背景
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(android.graphics.Color.parseColor("#E01A1A1A")) // 更深一些的半透明灰黑，防背景干扰
                cornerRadius = 23f * density // 胶囊圆角
                setStroke((1.5f * density).toInt(), android.graphics.Color.parseColor("#40FFFFFF")) // 质感白色描边
            }
            
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                panelHeight,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = (32 * density).toInt()
                topMargin = (100 * density).toInt()
            }
            
            // 1. 拖拽手柄 (左侧双竖线，传达拖拽指示)
            val handleContainer = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (12 * density).toInt(),
                    (18 * density).toInt()
                ).apply {
                    setMargins(0, 0, (8 * density).toInt(), 0)
                }
                gravity = Gravity.CENTER
                
                val line1 = android.view.View(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams((1.5f * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        setMargins(0, 0, (2 * density).toInt(), 0)
                    }
                    setBackgroundColor(android.graphics.Color.parseColor("#80FFFFFF"))
                }
                val line2 = android.view.View(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams((1.5f * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(android.graphics.Color.parseColor("#80FFFFFF"))
                }
                addView(line1)
                addView(line2)
            }
            addView(handleContainer)

            // 创建统一的控制按钮助手函数
            fun createControlButton(iconRes: Int, onClick: () -> Unit): android.widget.ImageView {
                return android.widget.ImageView(context).apply {
                    setImageResource(iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (32 * density).toInt(),
                        (32 * density).toInt()
                    ).apply {
                        setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                    }
                    setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    isClickable = true
                    isFocusable = true
                    
                    // 触摸高亮反馈与回弹效果
                    val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                    }
                    background = backgroundDrawable
                    
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                backgroundDrawable.setColor(android.graphics.Color.parseColor("#30FFFFFF"))
                                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(50).start()
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                backgroundDrawable.setColor(Color.TRANSPARENT)
                                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start()
                                if (event.action == MotionEvent.ACTION_UP) {
                                    onClick()
                                }
                            }
                        }
                        true
                    }
                }
            }
            
            // 2. 返回键按钮
            val backButton = createControlButton(R.drawable.ic_back) {
                injectKey(android.view.KeyEvent.KEYCODE_BACK)
            }
            addView(backButton)
            
            // 3. 主页键按钮
            val homeButton = createControlButton(R.drawable.ic_home) {
                val displayId = remoteDisplayId
                if (displayId != null) {
                    lifecycleScope.launch {
                        repository.launchHome(displayId)
                    }
                }
            }
            addView(homeButton)
            
            // 4. 应用启动器按钮
            val appButton = createControlButton(R.drawable.ic_apps) {
                showAppSelectionDialog()
            }
            addView(appButton)
            
            // 5. 分割线
            val divider = android.view.View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (1 * density).toInt(),
                    (20 * density).toInt()
                ).apply {
                    setMargins((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                }
                setBackgroundColor(android.graphics.Color.parseColor("#30FFFFFF"))
            }
            addView(divider)
            
            // 6. 关闭退出按钮
            val closeButton = createControlButton(R.drawable.ic_close) {
                finish()
            }
            addView(closeButton)
            
            var dX = 0f
            var dY = 0f
            
            // 面板整体拖拽监听
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - event.rawX
                        dY = view.y - event.rawY
                        view.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.95f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val parent = view.parent as android.view.View
                        val newX = (event.rawX + dX).coerceIn(0f, (parent.width - view.width).toFloat())
                        val newY = (event.rawY + dY).coerceIn(0f, (parent.height - view.height).toFloat())
                        view.x = newX
                        view.y = newY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(100).start()
                        true
                    }
                    else -> false
                }
            }
        }
        rootLayout.addView(controlPanel)

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
            0, // deviceId 设为 0
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
                val result = repository.injectInputWithDisplayId(event, displayId)
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

        // 设置 buffer 尺寸与虚拟屏当前逻辑分辨率一致
        textureView.surfaceTexture?.setDefaultBufferSize(vdWidth, vdHeight)

        val viewW = rootLayout.width.takeIf { it > 0 }?.toFloat() ?: return
        val viewH = rootLayout.height.takeIf { it > 0 }?.toFloat() ?: return

        // ---------------------------------------------------------
        // 虚拟屏内应用自行处理旋转：getRealSize 已返回旋转后的实际宽高。
        // 我们只需做 fit-center 缩放，不需要额外旋转。
        // TextureView 默认将 buffer 拉伸至 view 全屏，先抵消拉伸再等比缩放。
        // ---------------------------------------------------------
        val scale = minOf(viewW / sourceW, viewH / sourceH)

        val textureMatrix = Matrix()
        textureMatrix.postTranslate(-viewW / 2f, -viewH / 2f)
        textureMatrix.postScale(sourceW / viewW, sourceH / viewH) // 抵消全屏拉伸
        textureMatrix.postScale(scale, scale)                      // fit-center 缩放
        textureMatrix.postTranslate(viewW / 2f, viewH / 2f)

        textureView.setTransform(textureMatrix)

        // ---------------------------------------------------------
        // 触摸坐标映射：将屏幕坐标逆映射回虚拟屏坐标空间
        // ---------------------------------------------------------
        val touchMatrix = Matrix()
        touchMatrix.postTranslate(-sourceW / 2f, -sourceH / 2f)
        touchMatrix.postScale(scale, scale)
        touchMatrix.postTranslate(viewW / 2f, viewH / 2f)

        val inverseMatrix = Matrix()
        touchMatrix.invert(inverseMatrix)

        val targetWidth = sourceW * scale
        val targetHeight = sourceH * scale

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

    private fun showAppSelectionDialog() {
        val pm = packageManager
        lifecycleScope.launch(Dispatchers.IO) {
            val installedApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            val appList = installedApps
                .filter { info -> (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) || (pm.getLaunchIntentForPackage(info.packageName) != null) }
                .map { info -> com.ynk.virtualdisplay.models.AppInfo(info.loadLabel(pm).toString(), info.packageName) }
                .sortedBy { it.name }
            
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val names = appList.map { it.name }.toTypedArray()
                android.app.AlertDialog.Builder(this@DisplayActivity)
                    .setTitle("选择要在该屏幕启动的应用")
                    .setItems(names) { dialog, which ->
                        val selectedApp = appList[which]
                        val displayId = remoteDisplayId ?: return@setItems
                        lifecycleScope.launch {
                            repository.launchApp(selectedApp.packageName, displayId)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun injectKey(keyCode: Int) {
        val displayId = remoteDisplayId ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val downEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0)
        
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            repository.injectInputWithDisplayId(downEvent, displayId)
            repository.injectInputWithDisplayId(upEvent, displayId)
        }
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