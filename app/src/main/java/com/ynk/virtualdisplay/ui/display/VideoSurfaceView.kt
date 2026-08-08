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
        videoWidth = width
        videoHeight = height
        Log.i(TAG, "setVideoSize: ${width}x${height}")
    }

    fun setFixedBufferSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        try { holder.setFixedSize(width, height) } catch (_: Throwable) {}
        Log.i(TAG, "setFixedBufferSize: ${width}x${height}")
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
        } catch (e: Throwable) {
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
