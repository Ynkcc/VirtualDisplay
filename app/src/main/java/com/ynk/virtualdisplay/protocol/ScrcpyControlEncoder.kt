package com.ynk.virtualdisplay.protocol

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.DataOutputStream

/**
 * scrcpy 原生控制协议编码器。
 *
 * 服务端 commit b7aef962 移除了 TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID (206)，
 * 输入注入必须通过 ROLE_CONTROL socket 上的 scrcpy 原生控制消息完成。
 *
 * wire format 参考：
 *   server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java
 *   server/src/main/java/com/genymobile/scrcpy/control/Controller.java
 */
object ScrcpyControlEncoder {

    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val TYPE_INJECT_SCROLL_EVENT = 3

    private const val POINTER_ID_MOUSE = -1L

    /**
     * 将 [KeyEvent] 编码为 TYPE_INJECT_KEYCODE 并写入 [out]。
     *
     * 线格式（均为 Big-Endian）：
     *   1B  type     (0)
     *   1B  action   (KeyEvent.ACTION_DOWN / ACTION_UP)
     *   4B  keycode
     *   4B  repeat
     *   4B  metaState
     */
    fun encodeKeyCode(out: DataOutputStream, event: KeyEvent) {
        out.writeByte(TYPE_INJECT_KEYCODE)
        out.writeByte(event.action)
        out.writeInt(event.keyCode)
        out.writeInt(event.repeatCount)
        out.writeInt(event.metaState)
    }

    /**
     * 将 [MotionEvent] 滚动手势编码为 TYPE_INJECT_SCROLL_EVENT 并写入 [out]。
     *
     * 线格式：
     *   1B  type     (3)
     *   4B  x        (视频画面上的像素 x)
     *   4B  y        (视频画面上的像素 y)
     *   2B  screenWidth  (unsigned short)
     *   2B  screenHeight (unsigned short)
     *   2B  hScroll  (i16 定点数)
     *   2B  vScroll  (i16 定点数)
     *   4B  buttons
     */
    fun encodeScroll(
        out: DataOutputStream,
        event: MotionEvent,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        val x = event.getX(0).toInt()
        val y = event.getY(0).toInt()
        val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val buttons = event.buttonState

        out.writeByte(TYPE_INJECT_SCROLL_EVENT)
        out.writeInt(x)
        out.writeInt(y)
        out.writeShort(screenWidth and 0xFFFF)
        out.writeShort(screenHeight and 0xFFFF)
        out.writeShort(floatToI16FixedPoint(hScroll * 16f).toInt())
        out.writeShort(floatToI16FixedPoint(vScroll * 16f).toInt())
        out.writeInt(buttons)
    }

    /**
     * 将 [MotionEvent] 中的所有触点分别编码为独立的 TYPE_INJECT_TOUCH_EVENT
     * 消息并写入 [out]。
     *
     * 事件中的每个触点作为一条独立的 scrcpy 消息发送；服务端 PointersState 会
     * 按触点追踪状态并合并为一条 MotionEvent 注入。
     *
     * 线格式（每个触点）：
     *   1B  type         (2)
     *   1B  action       (ACTION_DOWN / ACTION_UP / ACTION_MOVE)
     *   8B  pointerId
     *   4B  x            (视频画面上的像素 x)
     *   4B  y            (视频画面上的像素 y)
     *   2B  screenWidth  (unsigned short)
     *   2B  screenHeight (unsigned short)
     *   2B  pressure     (u16 定点数, 0..1)
     *   4B  actionButton
     *   4B  buttons
     */
    fun encodeTouchEvent(
        out: DataOutputStream,
        event: MotionEvent,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerCount = event.pointerCount

        // 判断哪些触点索引需要 ACTION_DOWN/UP，哪些需要 ACTION_MOVE
        val targetPointerId = if (actionIndex >= 0 && actionIndex < pointerCount) {
            event.getPointerId(actionIndex)
        } else {
            -1
        }

        val isMouseEvent = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE

        for (i in 0 until pointerCount) {
            val pointerId = event.getPointerId(i)
            val x = event.getX(i).toInt()
            val y = event.getY(i).toInt()
            val pressure = event.getPressure(i)
            val pointerAction = resolvePointerAction(actionMasked, i, pointerId, targetPointerId)

            out.writeByte(TYPE_INJECT_TOUCH_EVENT)
            out.writeByte(pointerAction)
            out.writeLong(pointerId.toLong())
            out.writeInt(x)
            out.writeInt(y)
            out.writeShort(screenWidth and 0xFFFF)
            out.writeShort(screenHeight and 0xFFFF)
            out.writeShort(floatToU16FixedPoint(pressure.coerceIn(0f, 1f)).toInt())

            val actionButton = if (isMouseEvent && i == actionIndex) {
                event.actionButton
            } else {
                0
            }
            out.writeInt(actionButton)

            val buttons = if (isMouseEvent) {
                event.buttonState
            } else {
                0
            }
            out.writeInt(buttons)
        }
    }

    /**
     * 确定多点触控事件中某个触点对应的 scrcpy action。
     *
     * 服务端 PointersState 追踪是哪个触点被添加/移除/更新：
     * - 目标触点（触发事件的触点）：获得实际的 DOWN/UP action
     * - 其他触点：获得 ACTION_MOVE 以更新位置
     * - CANCEL：所有触点获得 ACTION_UP
     */
    private fun resolvePointerAction(
        actionMasked: Int,
        pointerIndex: Int,
        pointerId: Int,
        targetPointerId: Int,
    ): Int {
        val isTarget = pointerId == targetPointerId
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (isTarget) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isTarget) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            }
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            else -> {
                if (isTarget) actionMasked else MotionEvent.ACTION_MOVE
            }
        }
    }

    /**
     * 将 float [0,1] 转换为无符号 16 位定点数（65536 = 2^16）。
     * 服务端解码方式：unsignedShort == 0xffff ? 1f : (unsignedShort / 65536f)
     */
    private fun floatToU16FixedPoint(value: Float): Short {
        val clamped = value.coerceIn(0f, 1f)
        return if (clamped >= 1f) {
            0xFFFF.toShort()
        } else {
            (clamped * 65536f).toInt().toShort()
        }
    }

    /**
     * 将 float [-1,1] 转换为有符号 16 位定点数（32768 = 2^15）。
     * 服务端解码方式：value == 0x7fff ? 1f : (value / 32768f)
     */
    private fun floatToI16FixedPoint(value: Float): Short {
        val clamped = value.coerceIn(-1f, 1f)
        return if (clamped >= 1f) {
            0x7FFF.toShort()
        } else if (clamped <= -1f) {
            (-0x7FFF - 1).toShort() // -32768
        } else {
            (clamped * 32768f).toInt().toShort()
        }
    }
}
