package com.ynk.virtualdisplay.ui.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ynk.virtualdisplay.R

/**
 * 优化版悬浮可拖拽的控制面板 - 固定为垂直长条，支持即时无动画圆形折叠，完美契合 GameHelper 视觉质感
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DisplayControlPanel(
    context: Context,
    private val onBackClick: () -> Unit,
    private val onHomeClick: () -> Unit,
    private val onAppLauncherClick: () -> Unit,
    private val onKeyboardClick: () -> Unit,
    private val onCloseClick: () -> Unit
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private var isCollapsed = false
    private var isDragging = false

    private val handleContainer: TextView
    private val buttonsContainer: LinearLayout
    private val divider: View

    init {
        val panelSize = (40 * density).toInt()

        orientation = VERTICAL
        gravity = Gravity.CENTER
        elevation = 15f * density

        // 默认展开状态的上下 padding 8dp
        setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())

        // 极简毛玻璃感背景与精致细边框配色 (Color 0xD9121214 + 0.8dp 细微 0x22FFFFFF 边框)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#D9121214"))
            cornerRadius = 20f * density
            setStroke((0.8f * density).toInt(), Color.parseColor("#22FFFFFF"))
        }

        layoutParams = FrameLayout.LayoutParams(
            panelSize,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = (32 * density).toInt()
            topMargin = (100 * density).toInt()
        }

        // 1. 拖拽与展开折叠手柄 (使用 GameHelper 中的字符 "✥" / "◈"，高度对称居中)
        handleContainer = TextView(context).apply {
            text = "✥"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                (32 * density).toInt(),
                (32 * density).toInt()
            ).apply {
                setMargins(0, 0, 0, (6 * density).toInt())
            }
        }
        addView(handleContainer)

        // 按钮容器
        buttonsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 创建统一的控制按钮助手函数 (默认 10% 亮度的圆底，点击直接高亮无缩放动画)
        fun createControlButton(iconRes: Int, onClick: () -> Unit): ImageView {
            return ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                layoutParams = LayoutParams(
                    (32 * density).toInt(),
                    (32 * density).toInt()
                ).apply {
                    setMargins(0, (4 * density).toInt(), 0, (4 * density).toInt())
                }
                setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true

                val backgroundDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#10FFFFFF"))
                }
                background = backgroundDrawable

                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            backgroundDrawable.setColor(Color.parseColor("#30FFFFFF"))
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            backgroundDrawable.setColor(Color.parseColor("#10FFFFFF"))
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
            onBackClick()
        }
        buttonsContainer.addView(backButton)

        // 3. 主页键按钮
        val homeButton = createControlButton(R.drawable.ic_home) {
            onHomeClick()
        }
        buttonsContainer.addView(homeButton)

        // 4. 应用启动器按钮
        val appButton = createControlButton(R.drawable.ic_apps) {
            onAppLauncherClick()
        }
        buttonsContainer.addView(appButton)

        // 4.5 键盘开关按钮
        val keyboardButton = createControlButton(R.drawable.ic_keyboard) {
            onKeyboardClick()
        }
        buttonsContainer.addView(keyboardButton)

        // 5. 分割线
        divider = View(context).apply {
            layoutParams = LayoutParams(
                (20 * density).toInt(),
                (0.8f * density).toInt()
            ).apply {
                setMargins(0, (5 * density).toInt(), 0, (5 * density).toInt())
            }
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
        }
        buttonsContainer.addView(divider)

        // 6. 关闭退出按钮
        val closeButton = createControlButton(R.drawable.ic_close) {
            onCloseClick()
        }
        buttonsContainer.addView(closeButton)

        addView(buttonsContainer)

        var dX = 0f
        var dY = 0f
        var startRawX = 0f
        var startRawY = 0f

        // 拖拽手柄独立触控，无缩放/透明度动画
        handleContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = this.x - event.rawX
                    dY = this.y - event.rawY
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = this.parent as View
                    val newX = (event.rawX + dX).coerceIn(0f, (parent.width - this.width).toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, (parent.height - this.height).toFloat())
                    this.x = newX
                    this.y = newY

                    if (Math.abs(event.rawX - startRawX) > 10 * density || Math.abs(event.rawY - startRawY) > 10 * density) {
                        isDragging = true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (!isDragging) {
                            toggleCollapse()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 监听自身大小变化，防止展开时越界
        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val parent = parent as? View ?: return@addOnLayoutChangeListener
            val selfWidth = (right - left).toFloat()
            val selfHeight = (bottom - top).toFloat()
            if (this.x + selfWidth > parent.width) {
                this.x = (parent.width - selfWidth).coerceAtLeast(0f)
            }
            if (this.y + selfHeight > parent.height) {
                this.y = (parent.height - selfHeight).coerceAtLeast(0f)
            }
        }
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed

        buttonsContainer.visibility = if (isCollapsed) View.GONE else View.VISIBLE

        val panelSize = (40 * density).toInt()
        val lp = layoutParams as FrameLayout.LayoutParams
        if (isCollapsed) {
            lp.width = panelSize
            lp.height = panelSize
            setPadding(0, 0, 0, 0)
            handleContainer.text = "◈"
        } else {
            lp.width = panelSize
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            handleContainer.text = "✥"
        }
        layoutParams = lp

        val handleLp = handleContainer.layoutParams as LayoutParams
        if (isCollapsed) {
            handleLp.setMargins(0, 0, 0, 0)
        } else {
            handleLp.setMargins(0, 0, 0, (6 * density).toInt())
        }
        handleContainer.layoutParams = handleLp

    }
}

