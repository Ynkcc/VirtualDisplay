package com.example.myapplication

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DisplayActivity : ComponentActivity() {

    private var remoteDisplayId: Int? = null

    // 虚拟显示器的实际分辨率（用于坐标映射）
    private var virtualDisplayWidth: Int = 0
    private var virtualDisplayHeight: Int = 0

    // 用于 setDisplayId 反射
    private val setDisplayIdMethod by lazy {
        try {
            android.view.InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "setDisplayId method not found, touch injection may not target the correct display", e)
            null
        }
    }

    // 上次 DOWN 事件时间（用于构造 MotionEvent）
    private var lastTouchDownTime = 0L

    companion object {
        private const val TAG = "DisplayActivity"
        // 触摸注入模式：异步（0）
        private const val INJECT_MODE_ASYNC = 0
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra("display_id", -1)
        if (displayId == -1) {
            finish()
            return
        }

        // 保持屏幕常亮 & 锁定横屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // 沉浸式全屏
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            MyApplicationTheme {
                var surfaceReady by remember { mutableStateOf(false) }
                var showControls by remember { mutableStateOf(true) }
                val scope = rememberCoroutineScope()

                // 3 秒后自动隐藏控制栏
                fun scheduleHideControls() {
                    scope.launch {
                        delay(3000)
                        showControls = false
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                showControls = !showControls
                                if (showControls) scheduleHideControls()
                            }
                        }
                ) {
                    // SurfaceView 渲染虚拟屏幕（并透传触摸事件）
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        VirtualDisplayHelper.setDisplaySurface(
                                            displayId = displayId,
                                            surface = holder.surface
                                        )
                                        remoteDisplayId = displayId
                                        surfaceReady = true
                                        scheduleHideControls()
                                        // 记录虚拟显示器的实际分辨率，用于坐标映射
                                        VirtualDisplayHelper.getDisplayInfo(ctx, displayId)?.let { info ->
                                            virtualDisplayWidth = info.width
                                            virtualDisplayHeight = info.height
                                            Log.d(TAG, "Virtual display size: ${info.width}x${info.height}")
                                        }
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder, format: Int,
                                        width: Int, height: Int
                                    ) {}

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        remoteDisplayId?.let {
                                            VirtualDisplayHelper.setDisplaySurface(it, null)
                                        }
                                        remoteDisplayId = null
                                        surfaceReady = false
                                    }
                                })

                                // 触摸透传：将 View 坐标映射到虚拟显示器坐标并注入
                                setOnTouchListener { view, event ->
                                    val targetDisplayId = remoteDisplayId ?: return@setOnTouchListener false
                                    val vw = virtualDisplayWidth.takeIf { it > 0 } ?: return@setOnTouchListener false
                                    val vh = virtualDisplayHeight.takeIf { it > 0 } ?: return@setOnTouchListener false
                                    val viewW = view.width.toFloat()
                                    val viewH = view.height.toFloat()
                                    if (viewW <= 0f || viewH <= 0f) return@setOnTouchListener false

                                    injectTouchEvent(event, targetDisplayId, viewW, viewH, vw.toFloat(), vh.toFloat())
                                    // 返回 false，允许事件继续传递（用于控制栏手势检测）
                                    false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 加载中指示器
                    AnimatedVisibility(
                        visible = !surfaceReady,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = "正在连接显示器 #$displayId...",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp
                            )
                        }
                    }

                    // 顶部控制栏（点击屏幕切换显示）
                    AnimatedVisibility(
                        visible = showControls,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.75f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "虚拟屏幕 #$displayId",
                                        color = Color.White,
                                        fontSize = 15.sp
                                    )
                                }
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "关闭",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 将 SurfaceView 上的触摸事件透传到虚拟显示器。
     *
     * 复用 scrcpy 的注入链路（Device.injectEvent → InputManager.injectInputEvent）：
     * - 线性坐标映射：View 坐标 → 虚拟显示器坐标
     * - 通过反射 InputEvent.setDisplayId() 关联虚拟显示器 ID
     * - 完整支持多点触控（ACTION_POINTER_DOWN / ACTION_POINTER_UP）
     *
     * @param srcEvent      原始 MotionEvent（View 坐标系）
     * @param targetDisplay 目标虚拟显示器 ID
     * @param viewW         SurfaceView 宽度（px）
     * @param viewH         SurfaceView 高度（px）
     * @param vdW           虚拟显示器宽度（px）
     * @param vdH           虚拟显示器高度（px）
     */
    private fun injectTouchEvent(
        srcEvent: MotionEvent,
        targetDisplay: Int,
        viewW: Float, viewH: Float,
        vdW: Float, vdH: Float
    ) {
        val scaleX = vdW / viewW
        val scaleY = vdH / viewH
        val now = SystemClock.uptimeMillis()

        val action = srcEvent.actionMasked
        val actionIndex = srcEvent.actionIndex
        val pointerCount = srcEvent.pointerCount

        // 记录 DOWN 事件时间（与 scrcpy Controller.injectTouch 保持一致）
        if (action == MotionEvent.ACTION_DOWN) {
            lastTouchDownTime = now
        }

        // 映射 action：多指 POINTER_DOWN/UP 需要携带 pointerIndex
        val mappedAction = when (action) {
            MotionEvent.ACTION_POINTER_DOWN ->
                MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            MotionEvent.ACTION_POINTER_UP ->
                MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> action
        }

        // 构造 PointerProperties 和 PointerCoords（映射坐标）
        val props = Array(pointerCount) { i ->
            MotionEvent.PointerProperties().also { p ->
                srcEvent.getPointerProperties(i, p)
                p.toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointerCount) { i ->
            MotionEvent.PointerCoords().also { c ->
                srcEvent.getPointerCoords(i, c)
                c.x = c.x * scaleX
                c.y = c.y * scaleY
            }
        }

        val injectedEvent = MotionEvent.obtain(
            lastTouchDownTime, now,
            mappedAction, pointerCount,
            props, coords,
            0, 0,          // metaState, buttonState
            1f, 1f,        // xPrecision, yPrecision
            0, 0,          // deviceId, edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN,
            0              // flags
        )

        // 通过反射将事件关联到虚拟显示器（scrcpy Device.injectEvent 的等价操作）
        try {
            setDisplayIdMethod?.invoke(injectedEvent, targetDisplay)
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayId failed: ${e.message}")
        }

        val ok = ShizukuDisplayBridge.injectInputEvent(injectedEvent, INJECT_MODE_ASYNC)
        Log.v(TAG, "injectTouchEvent action=${MotionEvent.actionToString(action)} pointers=$pointerCount ok=$ok")

        injectedEvent.recycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        remoteDisplayId?.let { VirtualDisplayHelper.setDisplaySurface(it, null) }
        remoteDisplayId = null
    }
}
