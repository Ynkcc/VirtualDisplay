package com.ynk.virtualdisplay.video

import com.ynk.virtualdisplay.protocol.ScrcpyFrameFlags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScrcpyFrameReaderTest {

    private fun header(ptsAndFlags: Long, size: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putLong(ptsAndFlags).putInt(size).array()

    private fun reader(vararg chunks: ByteArray): ScrcpyFrameReader {
        val all = chunks.reduceOrNull { acc, bytes -> acc + bytes } ?: ByteArray(0)
        return ScrcpyFrameReader(ByteArrayInputStream(all))
    }

    @Test
    fun `parses plain frame with pts and payload`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = reader(header(12345L, payload.size), payload).readNextFrame()!!

        assertEquals(12345L, frame.ptsUs)
        assertFalse(frame.isConfig)
        assertFalse(frame.isKeyFrame)
        assertEquals(payload.size, frame.size)
        assertArrayEquals(payload, frame.data)
    }

    @Test
    fun `detects config frame flag`() {
        val frame = reader(header(ScrcpyFrameFlags.FLAG_CONFIG, 1), byteArrayOf(0)).readNextFrame()!!
        assertTrue(frame.isConfig)
        assertFalse(frame.isKeyFrame)
    }

    @Test
    fun `detects key frame flag`() {
        val frame = reader(header(ScrcpyFrameFlags.FLAG_KEY_FRAME, 1), byteArrayOf(0)).readNextFrame()!!
        assertTrue(frame.isKeyFrame)
        assertFalse(frame.isConfig)
    }

    @Test
    fun `masks session flag out of pts`() {
        val ptsAndFlags = ScrcpyFrameFlags.FLAG_SESSION or 777L
        val frame = reader(header(ptsAndFlags, 1), byteArrayOf(0)).readNextFrame()!!
        assertEquals(777L, frame.ptsUs)
    }

    @Test
    fun `returns null on end of stream`() {
        assertNull(reader().readNextFrame())
    }

    @Test(expected = IOException::class)
    fun `throws on truncated header`() {
        reader(byteArrayOf(1, 2, 3)).readNextFrame()
    }

    @Test(expected = IOException::class)
    fun `throws on oversized packet size`() {
        reader(header(0L, 9 * 1024 * 1024)).readNextFrame()
    }

    @Test(expected = IOException::class)
    fun `throws on truncated payload`() {
        reader(header(0L, 10), byteArrayOf(1, 2, 3)).readNextFrame()
    }
}
