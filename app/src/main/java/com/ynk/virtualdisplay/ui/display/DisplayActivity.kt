package com.ynk.virtualdisplay.ui.display

import android.widget.TextView
import android.util.TypedValue
import android.view.View
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
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
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.model.AppInfo
import com.ynk.virtualdisplay.data.repository.RecentAppHelper
import com.ynk.virtualdisplay.util.DisplayUtils
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
    private var currentRotation = -1
    private var activeDisplaySpec: DisplaySpec? = null
    private var touchGestureStarted = false
    private var isResizing = false
    private var resizeJob: kotlinx.coroutines.Job? = null

    // 辅助证明黑屏问题的诊断变量
    private var lastFrameTimeMs = 0L
    private var totalFrameCount = 0L
    private var isBlackScreenWarningLogged = false
    private var frameMonitoringJob: kotlinx.coroutines.Job? = null
    private var activeSurface: Surface? = null

    private lateinit var rootLayout: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var statsOverlay: TextView
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
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.getDisplay(displayId)?.let { display ->
            val spec = DisplayUtils.getVirtualDisplaySpec(display)
            val width = spec.width
            val height = spec.height
            if (vdWidth != width || vdHeight != height) {
                Log.d(TAG, "Virtual Display #$displayId Size Changed: ${width}x${height}")
                vdWidth = width
                vdHeight = height
                textureView.surfaceTexture?.let { texture ->
                    texture.setDefaultBufferSize(vdWidth, vdHeight)
                }
                updateSurfaceLayout()

                val dpi = spec.dpi
                resizeJob?.cancel()
                resizeJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    repository.resizeDisplay(displayId, width, height, dpi)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 方案 B：不再在此设置全局的 FLAG_NOT_FOCUSABLE，而是通过只允许隐藏文本框聚焦来解决焦点冲突

        val displayId = intent.getIntExtra("display_id", -1)
        require(displayId != -1) { "Invalid display_id" }
        remoteDisplayId = displayId

        enterFullscreen()
        setupContentView(displayId)

        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)
        updateDisplayInfo(displayId)

        // 监听连接状态以做诊断
        lifecycleScope.launch {
            repository.connectionStatus.collect { status ->
                Log.d(TAG, "[DIAGNOSTIC] Shizuku connection status updated: $status")
            }
        }

        repository.setPerformanceStatsCallback { stats ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (::statsOverlay.isInitialized) {
                    statsOverlay.text = stats
                }
            }
        }

        repository.setVideoConfigCallback { width, height ->
            lifecycleScope.launch(Dispatchers.Main) {
                Log.d(TAG, "Video config callback: ${width}x${height}")
                vdWidth = width
                vdHeight = height
                textureView.surfaceTexture?.setDefaultBufferSize(width, height)
                updateSurfaceLayout()
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
        repository.setPerformanceStatsCallback(null)
        repository.setVideoConfigCallback(null)
        resizeJob?.cancel()
        frameMonitoringJob?.cancel()
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
            Log.w(TAG, "checkIsFrameBlack failed", e)
            null
        }
    }

    private lateinit var forwardingEditText: ForwardingEditText

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
            // 确保主屏幕布局本身不抢占焦点
            isFocusable = false
            isFocusableInTouchMode = false
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateSurfaceLayout()
            }
        }

        // 核心变动：改用 TextureView 接管底层渲染
        textureView = TextureView(this).apply {
            // 确保渲染 View 也不抢占焦点
            isFocusable = false
            isFocusableInTouchMode = false
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
                    lifecycleScope.launch(Dispatchers.Main.immediate) {
                        repository.setDisplaySurface(displayId, null)
                        if (isFinishing) {
                            oldSurface?.release()
                        }
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

        // 添加隐藏文本框，用于接收主屏输入法输入并转发到虚拟屏幕
        forwardingEditText = ForwardingEditText(this, displayId, repository, lifecycleScope).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1).apply {
                leftMargin = -10
                topMargin = -10
            }
        }
        rootLayout.addView(forwardingEditText)

        // 优化版悬浮可拖拽的控制面板 - 升级为多功能横向玻璃态胶囊面板
        val controlPanel = DisplayControlPanel(
            context = this,
            onBackClick = { injectKey(android.view.KeyEvent.KEYCODE_BACK) },
            onHomeClick = {
                val displayId = remoteDisplayId
                if (displayId != null) {
                    lifecycleScope.launch {
                        repository.launchHome(displayId)
                    }
                }
            },
            onAppLauncherClick = { showAppSelectionDialog() },
            onKeyboardClick = { toggleKeyboard() },
            onCloseClick = { finish() }
        )
        rootLayout.addView(controlPanel)

        // 性能监控悬浮信息叠层
        statsOverlay = TextView(this).apply {
            setTextColor(Color.GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(16, 16, 16, 16)
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = 120
                rightMargin = 50
            }
        }
        rootLayout.addView(statsOverlay)

        setContentView(rootLayout)
    }

    private fun toggleKeyboard() {
        if (!::forwardingEditText.isInitialized) return
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (forwardingEditText.isFocused) {
            forwardingEditText.clearFocus()
            imm.hideSoftInputFromWindow(forwardingEditText.windowToken, 0)
            Log.d(TAG, "Keyboard hidden and focus cleared")
        } else {
            forwardingEditText.requestFocus()
            imm.showSoftInput(forwardingEditText, 0)
            Log.d(TAG, "Keyboard shown and requested focus")
        }
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
        // 纯本地长边对齐适配逻辑：
        // 如果虚拟屏幕与 View 的横竖方向不一致，通过 Matrix 旋转 90 度来强制长边平行，
        // 从而忽略远端屏幕的物理旋转，避免画面截断或显示不全。
        // ---------------------------------------------------------
        val isSourceLandscape = sourceW > sourceH
        val isViewLandscape = viewW > viewH
        val needRotate = isSourceLandscape != isViewLandscape

        val scale = if (needRotate) {
            minOf(viewW / sourceH, viewH / sourceW)
        } else {
            minOf(viewW / sourceW, viewH / sourceH)
        }

        val textureMatrix = Matrix()
        textureMatrix.postTranslate(-viewW / 2f, -viewH / 2f)
        textureMatrix.postScale(sourceW / viewW, sourceH / viewH) // 抵消全屏拉伸
        if (needRotate) {
            textureMatrix.postRotate(90f)
        }
        textureMatrix.postScale(scale, scale)                      // 等比缩放
        textureMatrix.postTranslate(viewW / 2f, viewH / 2f)

        textureView.setTransform(textureMatrix)

        // ---------------------------------------------------------
        // 触摸坐标映射：计算从 View 坐标系映射回原始虚拟显示器坐标系的逆矩阵
        // ---------------------------------------------------------
        val touchMatrix = Matrix()
        touchMatrix.postTranslate(-sourceW / 2f, -sourceH / 2f)
        if (needRotate) {
            touchMatrix.postRotate(90f)
        }
        touchMatrix.postScale(scale, scale)
        touchMatrix.postTranslate(viewW / 2f, viewH / 2f)

        val inverseMatrix = Matrix()
        touchMatrix.invert(inverseMatrix)

        val targetWidth = if (needRotate) sourceH * scale else sourceW * scale
        val targetHeight = if (needRotate) sourceW * scale else sourceH * scale

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
            val recentPkgs = RecentAppHelper.getRecentApps(this@DisplayActivity)
            val installedApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            val allApps = installedApps
                .filter { info -> (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) || (pm.getLaunchIntentForPackage(info.packageName) != null) }
                .map { info -> AppInfo(info.loadLabel(pm).toString(), info.packageName) }

            val recentList = mutableListOf<AppInfo>()
            recentPkgs.forEach { pkg ->
                val app = allApps.find { it.packageName == pkg }
                if (app != null) {
                    recentList.add(app)
                }
            }
            val otherList = allApps.filter { it.packageName !in recentPkgs }.sortedBy { it.name }
            val sortedAppList = recentList + otherList
            
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val names = sortedAppList.map { app ->
                    if (app.packageName in recentPkgs) {
                        "${app.name} (最近)"
                    } else {
                        app.name
                    }
                }.toTypedArray()
                android.app.AlertDialog.Builder(this@DisplayActivity)
                    .setTitle("选择要在该屏幕启动的应用")
                    .setItems(names) { dialog, which ->
                        val selectedApp = sortedAppList[which]
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

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            val prefs = getSharedPreferences("virtual_display_settings", MODE_PRIVATE)
            val captureBack = prefs.getBoolean("capture_back", false)
            if (captureBack) {
                if (event.action == KeyEvent.ACTION_UP) {
                    injectKey(KeyEvent.KEYCODE_BACK)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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

/**
 * 方案 B 自定义转发文本框
 * 用于拦截主显示器输入法输入并实时安全转发至次级虚拟屏幕
 */
@SuppressLint("ViewConstructor")
class ForwardingEditText(
    ctx: Context,
    private val targetDisplayId: Int,
    private val repository: IDisplayRepository,
    private val scope: kotlinx.coroutines.CoroutineScope
) : android.widget.EditText(ctx) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        alpha = 0f
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val superConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(superConnection, true) {
            
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString() ?: ""
                if (str.isNotEmpty()) {
                    injectTextToTarget(str)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event != null) {
                    injectKeyEventToTarget(event)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                for (i in 0 until beforeLength) {
                    injectKeyToTarget(KeyEvent.KEYCODE_DEL)
                }
                return true
            }
        }
    }

    private fun injectTextToTarget(text: String) {
        val kcm = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray())
        if (events != null) {
            scope.launch(Dispatchers.Main) {
                events.forEach { event ->
                    val cleanEvent = KeyEvent(
                        event.downTime,
                        event.eventTime,
                        event.action,
                        event.keyCode,
                        event.repeatCount,
                        event.metaState,
                        0,
                        0,
                        event.flags,
                        InputDevice.SOURCE_KEYBOARD
                    )
                    repository.injectInputWithDisplayId(cleanEvent, targetDisplayId)
                }
            }
        } else {
            scope.launch(Dispatchers.IO) {
                try {
                    // 1. 设置到主显示器系统剪贴板
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("virtual_display_input", text)
                    clipboard.setPrimaryClip(clip)
                    
                    // 短暂延迟确保系统广播同步完成
                    kotlinx.coroutines.delay(60)
                    
                    // 2. 注入 Ctrl + V 粘贴键
                    injectPasteEvent()
                } catch (e: Exception) {
                    Log.e("ForwardingEditText", "Failed to inject text via clipboard", e)
                }
            }
        }
    }

    private fun injectKeyEventToTarget(event: KeyEvent) {
        scope.launch(Dispatchers.Main) {
            val cleanEvent = KeyEvent(
                event.downTime,
                event.eventTime,
                event.action,
                event.keyCode,
                event.repeatCount,
                event.metaState,
                0,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD
            )
            repository.injectInputWithDisplayId(cleanEvent, targetDisplayId)
        }
    }

    private fun injectKeyToTarget(keyCode: Int) {
        scope.launch(Dispatchers.Main) {
            val now = android.os.SystemClock.uptimeMillis()
            val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            repository.injectInputWithDisplayId(downEvent, targetDisplayId)
            repository.injectInputWithDisplayId(upEvent, targetDisplayId)
        }
    }

    private fun injectPasteEvent() {
        scope.launch(Dispatchers.Main) {
            val now = android.os.SystemClock.uptimeMillis()
            
            // 构造 Ctrl + V (META_CTRL_ON 状态的 KEYCODE_V)
            val ctrlDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON)
            val vDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON)
            val vUp = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON)
            val ctrlUp = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
            
            repository.injectInputWithDisplayId(ctrlDown, targetDisplayId)
            repository.injectInputWithDisplayId(vDown, targetDisplayId)
            repository.injectInputWithDisplayId(vUp, targetDisplayId)
            repository.injectInputWithDisplayId(ctrlUp, targetDisplayId)
        }
    }
}