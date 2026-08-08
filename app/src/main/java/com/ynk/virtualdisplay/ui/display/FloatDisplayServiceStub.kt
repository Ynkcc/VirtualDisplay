package com.ynk.virtualdisplay.ui.display

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.data.repository.IDisplayRepository

/**
 * 虚拟显示器“悬浮小窗”模式（Small / Mini 模式）实现的架构存根与设计指南。
 *
 * 【重要提示】
 * 本文件是一个实现骨架与技术指引文档，用于为后续实现多态悬浮窗提供完整的逻辑规划 and 注释指导，
 * 避免开发者遗忘相关 API 的限制和权限配置。
 */
class FloatDisplayServiceStub : Service() {

    private lateinit var windowManager: WindowManager
    private var floatWindowView: View? = null
    private var videoSurfaceView: VideoSurfaceView? = null
    
    // 从 Application 中获取仓库以注入控制命令
    private lateinit var repository: IDisplayRepository

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // TODO: 初始化 repository
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val displayId = intent?.getIntExtra("display_id", -1) ?: -1
        if (displayId != -1) {
            showFloatWindow(displayId)
        }
        return START_NOT_STICKY
    }

    /**
     * 构建并展示系统悬浮窗（悬浮小窗模式）
     * 
     * 技术要点 1：系统权限
     * 必须在 AndroidManifest.xml 中声明 `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`
     * 并且在启动服务前，通过 `Settings.canDrawOverlays(context)` 引导用户前往系统设置授权。
     */
    private fun showFloatWindow(displayId: Int) {
        if (floatWindowView != null) return

        // 技术要点 2：窗口参数配置 (WindowManager.LayoutParams)
        val layoutParams = WindowManager.LayoutParams().apply {
            // Android 8.0+ 必须使用 TYPE_APPLICATION_OVERLAY
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            // 格式必须为半透明，以支持悬浮窗圆角或非矩形裁剪
            format = PixelFormat.TRANSLUCENT
            
            // 关键 FLAGS 组合：
            // - FLAG_NOT_FOCUSABLE: 保证悬浮窗不会拦截物理返回键、Home键以及其他全局按键。
            // - FLAG_LAYOUT_IN_SCREEN: 允许悬浮窗布局渲染在状态栏后方，实现沉浸式。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            
            // 初始化小窗的大小（比如宽 400dp，高 225dp，对齐 16:9）与在屏幕上的初始位置
            width = dp2px(320)
            height = dp2px(180)
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        // 技术要点 3：加载悬浮布局与挂载 VideoSurfaceView
        // 1. 可以通过 LayoutInflater 加载一个包含拖动把手、关闭/最大化按钮、以及渲染区域的 xml 布局
        // 2. 将 VideoSurfaceView 动态 addView 进布局容器中。
        // 3. 在悬浮窗的生命周期内，当 Surface 可用时，调用 `repository.setDisplaySurface(displayId, surface)` 开始解码渲染。
        val container = FrameLayout(this)
        
        // 示例：动态创建一个 VideoSurfaceView 并塞入
        val surfaceView = VideoSurfaceView(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(surfaceView)
        videoSurfaceView = surfaceView
        floatWindowView = container

        // 技术要点 4：触控拦截与事件转发
        // 悬浮窗的外侧拖动、手势缩放、双击切换 Mini 状态：
        // 我们可以为 container 设置 OnTouchListener，通过 GestureDetector 区分“拖动悬浮窗位置”和“点击内部应用操作”。
        // 如果是点击内部应用，需要利用 `InputController` 将触摸坐标映射为虚拟屏幕分辨率中的坐标，
        // 然后使用 `repository.injectInputWithDisplayId(event, displayId)` 发送至服务端注入。
        container.setOnTouchListener { view, event ->
            handleTouch(event, displayId)
            true
        }

        windowManager.addView(container, layoutParams)
    }

    /**
     * 处理悬浮窗的触控逻辑
     */
    private fun handleTouch(event: MotionEvent, displayId: Int): Boolean {
        // 1. 判断是否是在点击“把手”区域。如果是，使用 windowManager.updateViewLayout(floatWindowView, lp) 来更新悬浮窗位置，实现拖拽。
        // 2. 如果点击的是视频内容渲染区，需将 x, y 归一化并映射至虚拟显示器的 width/height（需根据 displayId 动态获取 spec 尺寸）。
        // 3. 映射后重新包装成 MotionEvent，通过 repository.injectInputWithDisplayId 注入。
        return true
    }

    /**
     * 技术要点 5：三态转换机制（Mini ↔ Small ↔ Full）
     * - Mini 模式：仅在边缘显示一个狭长色条（如 30dp 宽），点击它会自动展开为 Small 模式。
     * - Small 模式：小悬浮窗。点击“全屏”按钮会关闭服务，并使用 `DisplayActivity.createIntent` 唤起全屏 Activity。
     * - 状态转换时，为了避免底层 MediaCodec 重新初始化和重新链接解码器造成的白屏，
     *   应该保持后台 Service 中的解码循环（H264StreamDecoder）不中断，
     *   仅仅是动态地将同一个 Decoder 的输出 Surface 从 Service 绑定的 SurfaceView 转移切换给 Activity 绑定的 SurfaceView。
     */
    fun switchWindowMode(targetMode: Int) {
        // 切换逻辑：修改 WindowManager.LayoutParams.width/height 并调用 windowManager.updateViewLayout
    }

    private fun removeFloatWindow() {
        floatWindowView?.let {
            windowManager.removeView(it)
            floatWindowView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatWindow()
        // TODO: 记得告诉 repository 清理 Surface `repository.setDisplaySurface(displayId, null)`
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun dp2px(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
