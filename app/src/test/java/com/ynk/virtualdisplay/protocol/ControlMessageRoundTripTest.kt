package com.ynk.virtualdisplay.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ControlMessageRoundTripTest {

    @Test
    fun testCreateVirtualDisplayEncoding() {
        val msg = ControlMessage.CreateVirtualDisplay("TestVD", 1920, 1080, 320, 5)
        val seq = 42L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_CREATE_VIRTUAL_DISPLAY.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())

        // Read String
        val strLen = buffer.getInt()
        val strBytes = ByteArray(strLen)
        buffer.get(strBytes)
        assertEquals("TestVD", String(strBytes, Charsets.UTF_8))

        assertEquals(1920, buffer.getInt())
        assertEquals(1080, buffer.getInt())
        assertEquals(320, buffer.getInt())
        assertEquals(5, buffer.getInt())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testReleaseVirtualDisplayEncoding() {
        val msg = ControlMessage.ReleaseVirtualDisplay(10)
        val seq = 43L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_RELEASE_VIRTUAL_DISPLAY.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())
        assertEquals(10, buffer.getInt())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testResizeVirtualDisplayEncoding() {
        val msg = ControlMessage.ResizeVirtualDisplay(10, 1280, 720, 240)
        val seq = 44L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_RESIZE_VIRTUAL_DISPLAY.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())
        assertEquals(10, buffer.getInt())
        assertEquals(1280, buffer.getInt())
        assertEquals(720, buffer.getInt())
        assertEquals(240, buffer.getInt())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testStartActivityEncoding() {
        val msg = ControlMessage.StartActivity("com.android.settings", 1)
        val seq = 45L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_START_ACTIVITY.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())

        val strLen = buffer.getInt()
        val strBytes = ByteArray(strLen)
        buffer.get(strBytes)
        assertEquals("com.android.settings", String(strBytes, Charsets.UTF_8))
        assertEquals(1, buffer.getInt())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testGetActiveDisplayIdsEncoding() {
        val msg = ControlMessage.GetActiveDisplayIds
        val seq = 46L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_GET_ACTIVE_DISPLAY_IDS.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testInjectInputEventEncoding() {
        val parcelData = byteArrayOf(1, 2, 3, 4, 5)
        val msg = ControlMessage.InjectInputEvent(2, true, parcelData)
        val seq = 47L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())
        assertEquals(2, buffer.getInt())
        assertEquals(1.toByte(), buffer.get())
        assertEquals(5, buffer.getInt())
        val readParcel = ByteArray(5)
        buffer.get(readParcel)
        assertArrayEquals(parcelData, readParcel)
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testSwitchDisplayEncoding() {
        val msg = ControlMessage.SwitchDisplay(3)
        val seq = 48L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_SWITCH_DISPLAY.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())
        assertEquals(3, buffer.getInt())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testExitDaemonEncoding() {
        val msg = ControlMessage.ExitDaemon
        val seq = 49L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_EXIT_DAEMON.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testStartVideoStreamEncoding() {
        val msg = ControlMessage.StartVideoStream(5)
        val seq = 50L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_START_VIDEO_STREAM.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())
        assertEquals(5, buffer.getInt())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testStopVideoStreamEncoding() {
        val msg = ControlMessage.StopVideoStream
        val seq = 51L
        val bytes = msg.encode(seq)

        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(ControlMessage.TYPE_STOP_VIDEO_STREAM.toByte(), buffer.get())
        assertEquals(seq, buffer.getLong())
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun testScrcpyFrameFlagsFix() {
        // 验证断层 D 的正确性
        // config flag 应该等于 1L shl 62
        assertEquals(0x4000000000000000L, ScrcpyFrameFlags.FLAG_CONFIG)
        // session flag 应该等于 1L shl 63
        assertEquals(Long.MIN_VALUE, ScrcpyFrameFlags.FLAG_SESSION)

        // 验证 config 帧检测
        val ptsAndFlagsConfig = ScrcpyFrameFlags.FLAG_CONFIG or 12345L
        val isConfig = (ptsAndFlagsConfig and ScrcpyFrameFlags.FLAG_CONFIG) != 0L
        assertTrue(isConfig)
        val isSession = (ptsAndFlagsConfig and ScrcpyFrameFlags.FLAG_SESSION) != 0L
        assertFalse(isSession)
    }
}
