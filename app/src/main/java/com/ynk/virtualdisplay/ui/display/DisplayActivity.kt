package com.ynk.virtualdisplay.ui.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.model.AppInfo
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.repository.RecentAppHelper
import com.ynk.virtualdisplay.util.DisplayUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@SuppressLint("ClickableViewAccessibility", "UseKtx")
class DisplayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DisplayActivity"

        fun createIntent(context: Context, displayId: Int): Intent {
            return Intent(context, DisplayActivity::class.java).apply {
                putExtra("display_id", displayId)
            }
        }
    }

    private var remoteDisplayId: Int? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var currentVideoRotation = 0

    private var resizeJob: Job? = null

    private lateinit var rootLayout: FrameLayout
    private lateinit var videoSurfaceView: VideoSurfaceView
    private lateinit var statsOverlay: TextView
    private lateinit var controlPanel: DisplayControlPanel
    private lateinit var inputController: InputController

    private val repository: IDisplayRepository by lazy {
        (application as MyApplication).displayRepository
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

        requestHighRefreshRate()
        enterFullscreen()
        setupContentView(displayId)

        inputController = InputController(
            repository = repository,
            displayIdProvider = { remoteDisplayId },
            scope = lifecycleScope,
        )

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (AppSettings.captureBackCache.value) {
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
            AppSettings.captureBackCache.collect { capture ->
                backCallback.isEnabled = capture
            }
        }

        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)
        updateDisplayInfo(displayId)

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
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.unregisterDisplayListener(displayListener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterFullscreen()
        }
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
                updateContentRect()
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
                    Log.i(TAG, "Video surface available")
                    if (videoWidth > 0 && videoHeight > 0) {
                        setVideoSurface(surface)
                    }
                }

                override fun onSurfaceDestroyed() {
                    Log.i(TAG, "Video surface destroyed")
                    setVideoSurface(null)
                }

                override fun onSurfaceChanged(width: Int, height: Int) {
                    Log.d(TAG, "Video surface changed: ${width}x${height}")
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
            onHomeClick = {
                val id = remoteDisplayId ?: return@DisplayControlPanel
                lifecycleScope.launch { repository.launchHome(id) }
            },
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
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val result = repository.setDisplaySurface(remoteDisplayId!!, surface)
            result.exceptionOrNull()?.let {
                Log.e(TAG, "Failed to set display surface", it)
            }
        }
    }

    private fun onVideoConfigChanged(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        inputController.updateVideoSize(width, height)
        videoSurfaceView.setVideoSize(width, height)

        val viewW = rootLayout.width
        val viewH = rootLayout.height
        if (viewW > 0 && viewH > 0) {
            val needRotate = (width > height) != (viewW > viewH)
            currentVideoRotation = if (needRotate) 90 else 0
            inputController.setVideoRotation(currentVideoRotation)
            updateContentRect()
        }

        applyOrientationForVideo(width, height)
        Log.i(TAG, "Video config changed: ${width}x${height}, rotation=$currentVideoRotation")
    }

    private fun updateDisplayInfo(displayId: Int) {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm.getDisplay(displayId)?.let { display ->
            val spec = DisplayUtils.getVirtualDisplaySpec(display)
            val width = spec.width
            val height = spec.height
            if (videoWidth != width || videoHeight != height) {
                Log.d(TAG, "Virtual Display #$displayId size changed: ${width}x${height}")
                videoWidth = width
                videoHeight = height
                inputController.updateVideoSize(width, height)
                videoSurfaceView.setVideoSize(width, height)

                val dpi = spec.dpi
                resizeJob?.cancel()
                resizeJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    repository.resizeDisplay(displayId, width, height, dpi)
                }
            }
        }
    }

    private fun updateContentRect() {
        if (::inputController.isInitialized && ::rootLayout.isInitialized) {
            inputController.getContentRect(rootLayout)
        }
    }

    private fun applyOrientationForVideo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val targetOrientation = when {
            width > height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            height > width -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
            Log.i(TAG, "applyOrientationForVideo ${width}x${height} -> orientation=$targetOrientation")
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
            if (AppSettings.captureBackCache.value) {
                if (event.action == KeyEvent.ACTION_UP) {
                    inputController.injectKey(KeyEvent.KEYCODE_BACK)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showAppSelectionDialog() {
        val pm = packageManager
        lifecycleScope.launch(Dispatchers.IO) {
            val recentPkgs = RecentAppHelper.getRecentApps(this@DisplayActivity)
            val installedApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            val allApps = installedApps
                .filter { info ->
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) ||
                        (pm.getLaunchIntentForPackage(info.packageName) != null)
                }
                .map { info -> AppInfo(info.loadLabel(pm).toString(), info.packageName) }

            val recentList = mutableListOf<AppInfo>()
            recentPkgs.forEach { pkg ->
                val app = allApps.find { it.packageName == pkg }
                if (app != null) recentList.add(app)
            }
            val otherList = allApps.filter { it.packageName !in recentPkgs }.sortedBy { it.name }
            val sortedAppList = recentList + otherList

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val names = sortedAppList.map { app ->
                    if (app.packageName in recentPkgs) "${app.name} (最近)" else app.name
                }.toTypedArray()
                android.app.AlertDialog.Builder(this@DisplayActivity)
                    .setTitle("选择要在该屏幕启动的应用")
                    .setItems(names) { _, which ->
                        val selectedApp = sortedAppList[which]
                        val id = remoteDisplayId ?: return@setItems
                        lifecycleScope.launch {
                            repository.launchApp(selectedApp.packageName, id)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}
