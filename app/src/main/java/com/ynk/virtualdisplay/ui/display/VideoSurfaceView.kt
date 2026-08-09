package com.ynk.virtualdisplay.ui.display

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class VideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "VideoSurfaceView"
    }

    interface VideoCallbacks {
        fun onSurfaceAvailable(surface: Surface)
        fun onSurfaceDestroyed()
        fun onSurfaceChanged(width: Int, height: Int)
    }

    interface InputCallbacks {
        fun injectKeyEvent(event: KeyEvent): Boolean
        fun injectText(text: String): Boolean
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    }

    private var videoCallbacks: VideoCallbacks? = null
    private var inputCallbacks: InputCallbacks? = null

    @Volatile
    private var videoWidth: Int = 0

    @Volatile
    private var videoHeight: Int = 0

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        setZOrderOnTop(false)
        setZOrderMediaOverlay(false)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated: ${holder.surface}")
                videoCallbacks?.onSurfaceAvailable(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "surfaceChanged: ${width}x${height}")
                videoCallbacks?.onSurfaceChanged(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceDestroyed")
                videoCallbacks?.onSurfaceDestroyed()
            }
        })
    }

    fun setVideoCallbacks(callbacks: VideoCallbacks?) {
        videoCallbacks = callbacks
    }

    fun setInputCallbacks(callbacks: InputCallbacks?) {
        inputCallbacks = callbacks
    }

    fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        videoWidth = width
        videoHeight = height
        Log.i(TAG, "setVideoSize: ${width}x${height}")
        applyFixedSize(width, height)
        requestLayout()
    }

    fun setFixedBufferSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        applyFixedSize(width, height)
        requestLayout()
        Log.i(TAG, "setFixedBufferSize: ${width}x${height}")
    }

    private fun applyFixedSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        try { holder.setFixedSize(width, height) } catch (e: Exception) {
            Log.w(TAG, "setFixedSize failed for ${width}x${height}", e)
        }
    }

    /**
     * 等比例缩放并完整包含在控件内（fit-center）：
     *  - 等比例：取宽/高方向缩放比中较小者为基准，保持画面宽高比不变；
     *  - 完整在控件内：取较小缩放比保证画面不溢出控件；
     *  - 画面较长边与控件较长边方向一致由上层 Activity 方向旋转（DisplayActivity.applyOrientationForVideo）保证；
     *  - 缩放后画面较长边==控件较长边（画面相对更宽时）或较短边==控件较短边（画面相对更高时）。
     *
     *  控件由 MATCH_PARENT 布局，onMeasure 输出 fit-center 尺寸，再由父布局 Gravity.CENTER 居中，
     *  使得可视区域与 InputController.getContentRect() 计算的 fit-center 矩形完全对齐，输入映射与画面一致。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth <= 0 || videoHeight <= 0 || availWidth <= 0 || availHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val availAspect = availWidth.toFloat() / availHeight.toFloat()

        val targetWidth: Int
        val targetHeight: Int
        if (videoAspect > availAspect) {
            // 画面相对更宽：以控件宽度为基准，缩放后画面较长边==控件较长边（横向）
            targetWidth = availWidth
            targetHeight = (availWidth / videoAspect).toInt()
        } else {
            // 画面相对更高：以控件高度为基准，缩放后画面较长边==控件较长边（纵向）
            targetHeight = availHeight
            targetWidth = (availHeight * videoAspect).toInt()
        }
        setMeasuredDimension(targetWidth, targetHeight)
    }

    @android.annotation.SuppressLint("NewApi")
    fun applySurfaceFrameRate(targetRefreshRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = holder.surface
        if (surface == null || !surface.isValid) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    targetRefreshRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                surface.setFrameRate(
                    targetRefreshRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
            Log.i(TAG, "Applied surface frame rate: ${targetRefreshRate}Hz")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply surface frame rate", e)
        }
    }

    fun getSurface(): Surface? = holder.surface?.takeIf { it.isValid }

    fun requestKeyboardInput(enabled: Boolean) {
        isFocusable = enabled
        isFocusableInTouchMode = enabled
        if (enabled) {
            requestFocus()
        } else {
            clearFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    fun isKeyboardActive(): Boolean = isFocused

    override fun onCheckIsTextEditor(): Boolean {
        return isFocusable || super.onCheckIsTextEditor()
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyPreIme(keyCode, event)
        if (inputCallbacks?.injectKeyEvent(event) == true) return true
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!isFocusable) return super.onCreateInputConnection(outAttrs)

        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.packageName = context.packageName

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString() ?: ""
                if (str.isNotEmpty()) {
                    if (inputCallbacks?.injectText(str) == true) return true
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (inputCallbacks?.deleteSurroundingText(beforeLength, afterLength) == true) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.sendKeyEvent(event)
                if (inputCallbacks?.injectKeyEvent(event) == true) return true
                return super.sendKeyEvent(event)
            }
        }
    }
}
