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
     * Encode a [KeyEvent] as TYPE_INJECT_KEYCODE and write to [out].
     *
     * Wire format (all big-endian):
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
     * Encode a [MotionEvent] scroll as TYPE_INJECT_SCROLL_EVENT and write to [out].
     *
     * Wire format:
     *   1B  type     (3)
     *   4B  x        (pixel x on the video surface)
     *   4B  y        (pixel y on the video surface)
     *   2B  screenWidth  (unsigned short)
     *   2B  screenHeight (unsigned short)
     *   2B  hScroll  (i16 fixed point)
     *   2B  vScroll  (i16 fixed point)
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
     * Encode all pointers in a [MotionEvent] as individual TYPE_INJECT_TOUCH_EVENT
     * messages and write to [out].
     *
     * Each pointer in the event is sent as a separate scrcpy message. The server's
     * PointersState tracks per-pointer state and combines them into a MotionEvent
     * for injection.
     *
     * Wire format (per pointer):
     *   1B  type         (2)
     *   1B  action       (ACTION_DOWN / ACTION_UP / ACTION_MOVE)
     *   8B  pointerId
     *   4B  x            (pixel x on the video surface)
     *   4B  y            (pixel y on the video surface)
     *   2B  screenWidth  (unsigned short)
     *   2B  screenHeight (unsigned short)
     *   2B  pressure     (u16 fixed point, 0..1)
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

        // Determine which pointer indices need ACTION_DOWN/UP vs ACTION_MOVE
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
     * Determine the scrcpy action for a specific pointer within a multi-touch event.
     *
     * The server's PointersState tracks which pointer is being added/removed/updated.
     * - Target pointer (the one that triggered the event): gets the actual DOWN/UP action
     * - Other pointers: get ACTION_MOVE to update their positions
     * - CANCEL: all pointers get ACTION_UP
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
     * Convert float [0,1] to unsigned 16-bit fixed point (65536 = 2^16).
     * Server decodes with: unsignedShort == 0xffff ? 1f : (unsignedShort / 65536f)
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
     * Convert float [-1,1] to signed 16-bit fixed point (32768 = 2^15).
     * Server decodes with: value == 0x7fff ? 1f : (value / 32768f)
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
