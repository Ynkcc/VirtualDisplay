package com.ynk.virtualdisplay.ui.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.data.local.AppSettingsDataSource
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.domain.DisplayInteractor
import com.ynk.virtualdisplay.manager.DisplayMetricsManager
import com.ynk.virtualdisplay.ui.components.AppSelectionDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

@SuppressLint("ClickableViewAccessibility", "UseKtx")
class DisplayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DisplayActivity"

        fun createIntent(context: Context, displayId: Int, nodeKey: String? = null): Intent {
            return Intent(context, DisplayActivity::class.java).apply {
                putExtra("display_id", displayId)
                putExtra("node_key", nodeKey)
            }
        }
    }

    private var remoteDisplayId: Int? = null
    private var nodeKey: String? = null

    // Tracks the surface reported by VideoSurfaceView before videoWidth/Height
    // are known. Without this, onSurfaceAvailable drops the surface when
    // videoWidth==0 (the virtual display may not be registered in the system
    // DisplayManager yet at onCreate time), and nobody retroactively feeds it
    // to the decoder → permanent black screen.
    @Volatile private var pendingSurface: Surface? = null

    private var resizeJob: Job? = null
    private var remoteDisplayMonitorJob: Job? = null
    // 本地配置门面（T2）：替代直接访问 AppSettings 单例，统一收敛本地配置读写。
    private val settingsDataSource: AppSettingsDataSource by inject()
    private val isLocalNode: Boolean
        get() = settingsDataSource.getCurrentServerNodeSync().isLocal

    private lateinit var rootLayout: FrameLayout
    private lateinit var videoSurfaceView: VideoSurfaceView
    private lateinit var statsOverlay: TextView
    private lateinit var controlPanel: DisplayControlPanel
    private lateinit var inputController: InputController
    private lateinit var viewport: VideoViewportController
    private lateinit var displayInfoResolver: DisplayInfoResolver

    private val interactor: DisplayInteractor by inject()
    private val displayMetricsManager: DisplayMetricsManager by inject()
    // 应用级后台作用域：生命周期跟随进程，用于 Activity 销毁后的 fire-and-forget 清理任务
    private val backgroundScope: CoroutineScope by inject(qualifier = named("appBackgroundScope"))
    
    private val repository: IDisplayRepository by lazy {
        val key = nodeKey
        if (key != null) {
            interactor.getSlot(key) ?: error("No slot found for node key $key")
        } else {
            // Fallback to active repository (legacy behavior or current node)
            val activeRepoByInject: IDisplayRepository by inject()
            activeRepoByInject
        }
    }

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "onDisplayAdded: $displayId")
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.w(TAG, "onDisplayRemoved: $displayId")
            if (displayId == remoteDisplayId) {
                Log.w(TAG, "Remote display removed, finishing activity")
                finish()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == remoteDisplayId) {
                rootLayout.post { updateDisplayInfo(displayId) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra("display_id", -1)
        require(displayId != -1) { "Invalid display_id" }
        remoteDisplayId = displayId
        nodeKey = intent.getStringExtra("node_key")

        requestHighRefreshRate()
        enterFullscreen()
        setupContentView(displayId)

        inputController = InputController(
            repository = repository,
            displayIdProvider = { remoteDisplayId },
            scope = lifecycleScope,
        )

        viewport = VideoViewportController(
            inputController = inputController,
            videoSurfaceView = videoSurfaceView,
            rootLayout = rootLayout,
            requestOrientation = { target ->
                if (requestedOrientation != target) {
                    requestedOrientation = target
                    Log.i(TAG, "applyOrientationForVideo -> orientation=$target")
                }
            }
        )

        displayInfoResolver = DisplayInfoResolver(
            repository = repository,
            displayMetricsManager = displayMetricsManager,
            settingsDataSource = settingsDataSource,
            isLocalNode = { isLocalNode },
            localDisplayManager = { getSystemService(android.hardware.display.DisplayManager::class.java) }
        )

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsDataSource.captureBackCache.value) {
                    inputController.injectKey(KeyEvent.KEYCODE_BACK)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        lifecycleScope.launch {
            settingsDataSource.captureBackCache.collect { capture ->
                backCallback.isEnabled = capture
            }
        }

        lifecycleScope.launch {
            settingsDataSource.showPerformanceStatsCache.collect { show ->
                if (::statsOverlay.isInitialized) {
                    statsOverlay.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }

        if (isLocalNode) {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            dm.registerDisplayListener(displayListener, null)
        }
        updateDisplayInfo(displayId)
        startDisconnectDetection(displayId)

        repository.setPerformanceStatsCallback { stats ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (::statsOverlay.isInitialized) {
                    statsOverlay.text = stats
                }
            }
        }

        repository.setVideoConfigCallback { width, height ->
            lifecycleScope.launch(Dispatchers.Main) {
                onVideoConfigChanged(width, height)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backCallback.remove()
        repository.setPerformanceStatsCallback(null)
        repository.setVideoConfigCallback(null)
        resizeJob?.cancel()
        remoteDisplayMonitorJob?.cancel()
        if (isLocalNode) {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            dm.unregisterDisplayListener(displayListener)
        }
        // 退出操控页：销毁 ROLE_VIDEO + ROLE_CONTROL 流通道（保留 negotiation 与显示器）。
        // 不依赖 lifecycleScope（onDestroy 时已取消），用应用级后台作用域触发，确保下次
        // 进入重建全新通道，避免复用被服务端关闭的 control socket。
        if (remoteDisplayId != null) {
            backgroundScope.launch {
                runCatching { repository.stopStreaming() }.onFailure {
                    Log.w(TAG, "stopStreaming on destroy failed", it)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterFullscreen()
        }
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val modes = display.supportedModes
            val bestMode = modes.maxByOrNull { it.refreshRate }

            if (bestMode != null && bestMode.refreshRate > 60f) {
                val params = window.attributes
                params.preferredDisplayModeId = bestMode.modeId
                window.attributes = params
                Log.i(TAG, "Set preferredDisplayModeId to ${bestMode.modeId} (${bestMode.refreshRate}Hz)")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = window.attributes
                params.preferredRefreshRate = 120f
                window.attributes = params
            }
        }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupContentView(displayId: Int) {
        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (::viewport.isInitialized) viewport.updateContentRect()
            }
            setOnTouchListener { view, event ->
                handleTouchEvent(event)
            }
        }

        videoSurfaceView = VideoSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
            setVideoCallbacks(object : VideoSurfaceView.VideoCallbacks {
                override fun onSurfaceAvailable(surface: Surface) {
                    Log.i(TAG, "onSurfaceAvailable: surface=$surface valid=${surface.isValid} videoSize=${viewportSizeText()}")
                    pendingSurface = surface
                    // Regardless of whether videoWidth/Height are known, we MUST set the surface to the repository.
                    // For remote displays, we might never find the display in the local DisplayManager.
                    // Starting the stream allows the decoder to eventually report the correct dimensions.
                    setVideoSurface(surface)
                    pendingSurface = null
                }

                override fun onSurfaceDestroyed() {
                    Log.i(TAG, "onSurfaceDestroyed")
                    pendingSurface = null
                    setVideoSurface(null)
                }

                override fun onSurfaceChanged(width: Int, height: Int) {
                    Log.d(TAG, "onSurfaceChanged: ${width}x${height}")
                }
            })
            setInputCallbacks(object : VideoSurfaceView.InputCallbacks {
                override fun injectKeyEvent(event: KeyEvent): Boolean {
                    return inputController.handleKeyEvent(event)
                }

                override fun injectText(text: String): Boolean {
                    return injectTextViaKeyMap(text)
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    for (i in 0 until beforeLength) {
                        inputController.injectKey(KeyEvent.KEYCODE_DEL)
                    }
                    return true
                }
            })
        }
        rootLayout.addView(videoSurfaceView)

        controlPanel = DisplayControlPanel(
            context = this,
            onBackClick = { inputController.injectKey(KeyEvent.KEYCODE_BACK) },
            onAppLauncherClick = { showAppSelectionDialog() },
            onKeyboardClick = { toggleKeyboard() },
            onCloseClick = { finish() },
        )
        rootLayout.addView(controlPanel)

        statsOverlay = TextView(this).apply {
            setTextColor(Color.GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(16, 16, 16, 16)
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = 120
                rightMargin = 50
            }
        }
        rootLayout.addView(statsOverlay)

        setContentView(rootLayout)
    }

    private fun setVideoSurface(surface: Surface?) {
        // 架构边界：协程内任何未处理异常最终都会崩溃进程（CoroutineExceptionHandler 默认行为）
        // 此处 runCatching 不是"隐藏问题"，是防止协程内部意外异常变成 uncaught 崩溃；
        // 失败统一走 Result + 日志 + UI 反馈传导，绝不静默吞。
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val result = runCatching {
                repository.setDisplaySurface(remoteDisplayId!!, surface)
            }.fold(
                onSuccess = { it },
                onFailure = { Result.failure(it) }
            )
            result.onFailure {
                Log.e(TAG, "Failed to set display surface (surface=$surface)", it)
                // TODO: 通知 UI 层（Toast/内联错误提示）视频连接失败
                // 目前先只保证进程不崩溃，失败信息通过日志可追踪
            }
        }
    }

    private fun onVideoConfigChanged(width: Int, height: Int) {
        Log.i(TAG, "onVideoConfigChanged: ${width}x${height}")
        viewport.applyVideoDimensions(width, height)
    }

    private fun viewportSizeText(): String =
        if (::viewport.isInitialized) "${viewport.width}x${viewport.height}" else "uninitialized"

    private fun updateDisplayInfo(displayId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val isFirstDiscovery = (viewport.width == 0 && viewport.height == 0)
            val spec = displayInfoResolver.resolve(displayId) ?: return@launch
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                applyDisplayDimensions(spec.width, spec.height, spec.dpi, isFirstDiscovery)
            }
        }
    }

    /**
     * 统一的"显示器存活检测"：
     * - 对所有节点：监听 connectionStatus，当连接断开（DISCONNECTED/ERROR）时立即 finish()
     * - 对远程节点额外：每 15s RPC 轮询服务端显示器列表，检测 displayId 是否被销毁
     *   （远程 DisplayManager.DisplayListener 收不到本地系统回调，需要 RPC 替代）
     */
    private fun startDisconnectDetection(displayId: Int) {
        // 1. 即时路径：监听连接状态变化
        lifecycleScope.launch {
            repository.connectionStatus.collect { status ->
                if (status == ConnectionStatus.DISCONNECTED ||
                    status == ConnectionStatus.ERROR
                ) {
                    Log.w(TAG, "Connection status=$status, finishing activity")
                    finish()
                }
            }
        }

        // 2. 远程节点兜底：15s RPC 轮询服务端显示器列表
        if (!isLocalNode) {
            remoteDisplayMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
                while (!isFinishing) {
                    kotlinx.coroutines.delay(15_000)
                    if (isFinishing) break
                    val result = repository.getActiveDisplayInfos()
                    val stillExists = result.getOrNull()?.any { it.displayId == displayId } ?: true
                    if (!stillExists) {
                        Log.w(TAG, "Remote display #$displayId no longer active on server, finishing activity")
                        kotlinx.coroutines.withContext(Dispatchers.Main) { finish() }
                    }
                }
            }
        }
    }

    private fun applyDisplayDimensions(width: Int, height: Int, dpi: Int, isFirstDiscovery: Boolean) {
        if (viewport.applyVideoDimensions(width, height)) {
            Log.i(TAG, "applyDisplayDimensions: #$remoteDisplayId size=${width}x${height}")

            // Retroactively feed the pending surface to the decoder if it
            // arrived before display dimensions were known.
            val ps = pendingSurface
            if (ps != null && ps.isValid) {
                Log.i(TAG, "applyDisplayDimensions: applying pending surface now that dimensions are known")
                pendingSurface = null
                setVideoSurface(ps)
            }

            // Only send a resize command to the daemon when the display
            // dimensions *changed* from a previously known value. On first
            // discovery (isFirstDiscovery), the display was just created
            // with the correct dimensions by createDisplay — sending a
            // redundant resize causes the server to restart its
            // SurfaceEncoder, which closes the video socket and kills the
            // decoder stream.
            if (!isFirstDiscovery) {
                resizeJob?.cancel()
                resizeJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    repository.resizeDisplay(remoteDisplayId!!, width, height, dpi)
                }
            } else {
                Log.i(TAG, "applyDisplayDimensions: first discovery, skipping resize (display already created with correct dimensions)")
            }
        }
    }

    private fun toggleKeyboard() {
        if (!::videoSurfaceView.isInitialized) return
        val willEnable = !videoSurfaceView.isKeyboardActive()
        videoSurfaceView.requestKeyboardInput(willEnable)

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (willEnable) {
            val targetView = videoSurfaceView
            targetView.requestFocus()
            Handler(Looper.getMainLooper()).post {
                @Suppress("DEPRECATION")
                imm.showSoftInput(targetView, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            imm.hideSoftInputFromWindow(videoSurfaceView.windowToken, 0)
        }
        Log.d(TAG, "Keyboard ${if (willEnable) "shown" else "hidden"}")
    }

    @SuppressLint("RestrictedApi")
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val shouldHandleRemotely = (inputController.shouldHandleRemotely(event)) &&
            (isInsideVideoArea(event) || event.actionMasked == MotionEvent.ACTION_DOWN)

        val handled = if (shouldHandleRemotely) {
            inputController.handleMotionEvent(rootLayout, event)
        } else {
            false
        }
        return handled
    }

    private fun isInsideVideoArea(event: MotionEvent): Boolean {
        val rect = inputController.getContentRect(rootLayout) ?: return false
        return event.x in rect.left..rect.right && event.y in rect.top..rect.bottom
    }

    private fun injectTextViaKeyMap(text: String): Boolean {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events: Array<KeyEvent> = kcm.getEvents(text.toCharArray()) ?: return false
        lifecycleScope.launch(Dispatchers.Main) {
            for (event in events.iterator()) {
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
                    InputDevice.SOURCE_KEYBOARD,
                )
                repository.injectInputWithDisplayId(cleanEvent, remoteDisplayId!!)
            }
        }
        return true
    }

    private lateinit var backCallback: OnBackPressedCallback

    @SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (settingsDataSource.captureBackCache.value) {
                if (event.action == KeyEvent.ACTION_UP) {
                    inputController.injectKey(KeyEvent.KEYCODE_BACK)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showAppSelectionDialog() {
        val displayId = remoteDisplayId ?: return
        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(composeView)
        composeView.setContent {
            AppSelectionDialog(
                loadApps = { force -> repository.listApps(force) },
                onDismiss = { rootLayout.removeView(composeView) },
                onAppSelected = { appInfo ->
                    rootLayout.removeView(composeView)
                    lifecycleScope.launch {
                        repository.launchApp(appInfo.packageName, displayId)
                    }
                }
            )
        }
    }
}
