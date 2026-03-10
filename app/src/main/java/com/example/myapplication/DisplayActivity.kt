package com.example.myapplication

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DisplayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DisplayActivity"
        private const val INJECT_MODE_ASYNC = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayId = intent.getIntExtra("display_id", -1)
        Log.d(TAG, "onCreate: displayId=$displayId")
        require(displayId != -1) { "必须传入有效的 display_id" }

        ShizukuDisplayBridge.bindService(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MyApplicationTheme {
                var surfaceReady by remember { mutableStateOf(false) }
                var showControls by remember { mutableStateOf(true) }
                var vdWidth by remember { mutableStateOf(0) }
                var vdHeight by remember { mutableStateOf(0) }
                var displayRatio by remember { mutableStateOf<Float?>(null) }
                
                val scope = rememberCoroutineScope()

                LaunchedEffect(displayId) {
                    val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                    dm.getDisplay(displayId)?.let { display ->
                        val size = android.graphics.Point()
                        @Suppress("DEPRECATION")
                        display.getRealSize(size)
                        vdWidth = size.x
                        vdHeight = size.y
                        displayRatio = size.x.toFloat() / size.y.toFloat()
                        Log.d(TAG, "Display info loaded: ${size.x}x${size.y}")
                    }
                }

                fun scheduleHideControls() {
                    scope.launch {
                        delay(3000)
                        showControls = false
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                showControls = !showControls
                                if (showControls) scheduleHideControls()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val scale = if (vdWidth > 0 && vdHeight > 0) {
                        val scaleW = constraints.maxWidth.toFloat() / vdWidth
                        val scaleH = constraints.maxHeight.toFloat() / vdHeight
                        minOf(scaleW, scaleH)
                    } else 1f
                    
                    val viewW = vdWidth * scale
                    val viewH = vdHeight * scale

                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        Log.d(TAG, "Surface created for display #$displayId")
                                        scope.launch {
                                            ShizukuDisplayBridge.setDisplaySurface(displayId, holder.surface)
                                        }
                                        remoteDisplayId = displayId
                                        surfaceReady = true
                                        scheduleHideControls()
                                        
                                        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
                                        dm.getDisplay(displayId)?.let { display ->
                                            val size = android.graphics.Point()
                                            @Suppress("DEPRECATION")
                                            display.getRealSize(size)
                                            vdWidth = size.x
                                            vdHeight = size.y
                                            displayRatio = size.x.toFloat() / size.y.toFloat()
                                        }
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                                        Log.v(TAG, "Surface changed: ${w}x${h}")
                                    }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        Log.d(TAG, "Surface destroyed for display #$displayId")
                                        val id = remoteDisplayId
                                        remoteDisplayId = null
                                        surfaceReady = false
                                        
                                        // 及时清理远程显示表面，防止缓冲队列被放弃
                                        if (id != null) {
                                            scope.launch {
                                                try {
                                                    ShizukuDisplayBridge.setDisplaySurface(id, null)
                                                    Log.d(TAG, "Successfully cleared display surface for #$id")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Failed to clear display surface for #$id", e)
                                                }
                                            }
                                        }
                                    }
                                })

                                 var lastActionDownTime = 0L
                                 setOnTouchListener { view, event ->
                                    val targetDisplayId = remoteDisplayId ?: return@setOnTouchListener false
                                    
                                    // 确保坐标和尺寸有效
                                    if (view.width <= 0 || view.height <= 0 || vdWidth <= 0 || vdHeight <= 0) {
                                        return@setOnTouchListener false
                                    }

                                    injectTouchEvent(
                                        event, targetDisplayId,
                                        view.width.toFloat(), view.height.toFloat(),
                                        vdWidth.toFloat(), vdHeight.toFloat()
                                    )
                                    
                                    // 处理点击以切换控制栏（因为返回 true 会拦截父容器的手势检测）
                                    when (event.action) {
                                        MotionEvent.ACTION_DOWN -> {
                                            lastActionDownTime = System.currentTimeMillis()
                                        }
                                        MotionEvent.ACTION_UP -> {
                                            val duration = System.currentTimeMillis() - lastActionDownTime
                                            if (duration < 300) { // 简单判定为点击
                                                showControls = !showControls
                                                if (showControls) scheduleHideControls()
                                            }
                                        }
                                    }
                                    
                                    true // 必须返回 true 以接收后续事件（MOVE/UP）
                                }
                            }
                        },
                        modifier = Modifier
                            .size(
                                width = with(LocalDensity.current) { viewW.toDp() },
                                height = with(LocalDensity.current) { viewH.toDp() }
                            )
                    )

                    AnimatedVisibility(visible = !surfaceReady, enter = fadeIn(), exit = fadeOut()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color.White, strokeWidth = 3.dp)
                            Text(text = "正在连接显示器 #$displayId...", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        }
                    }

                    AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                        Box(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))).statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "虚拟屏幕 #$displayId", color = Color.White, fontSize = 15.sp)
                                }
                                IconButton(onClick = { 
                                    Log.d(TAG, "Close button clicked")
                                    // 关闭前清理Surface，防止错误
                                    val id = remoteDisplayId
                                    if (id != null) {
                                        remoteDisplayId = null
                                        scope.launch {
                                            try {
                                                ShizukuDisplayBridge.setDisplaySurface(id, null)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed to clear display surface before finish", e)
                                            }
                                            finish()
                                        }
                                    } else {
                                        finish()
                                    }
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        
        // 确保销毁时清理远程显示表面，使用 Dispatchers.Main.immediate + NonCancellable 确保任务执行
        val id = remoteDisplayId
        if (id != null) {
            remoteDisplayId = null
            lifecycleScope.launch(Dispatchers.Main.immediate + NonCancellable) {
                try {
                    ShizukuDisplayBridge.setDisplaySurface(id, null)
                    Log.d(TAG, "Cleared display surface in onDestroy for #$id")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear display surface in onDestroy", e)
                }
            }
        }
    }

    private var remoteDisplayId: Int? = null

    private fun injectTouchEvent(
        event: MotionEvent, displayId: Int,
        viewW: Float, viewH: Float,
        vdW: Float, vdH: Float
    ) {
        val newEvent = MotionEvent.obtain(event)
        
        // 使用 Matrix 进行整体缩放转换，这能处理所有触控点（多指）和历史轨迹
        val matrix = android.graphics.Matrix()
        matrix.postScale(vdW / viewW, vdH / viewH)
        newEvent.transform(matrix)
        
        newEvent.source = InputDevice.SOURCE_TOUCHSCREEN

        // 在协程内注入，并在注入完成后回收 MotionEvent
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            try {
                val result = ShizukuDisplayBridge.injectInputWithDisplayId(newEvent, displayId)
                if (result.isFailure) {
                    Log.e(TAG, "Failed to inject input: ${result.exceptionOrNull()?.message}")
                } else if (result.getOrNull() == false) {
                    // 如果返回 false，可能是权限不足（如 MIUI 安全设置）
                    Log.w(TAG, "Input injection returned false for display #$displayId - check Security Settings/Permissions")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during input injection", e)
            } finally {
                newEvent.recycle()
            }
        }
    }
}
