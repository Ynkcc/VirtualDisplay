package com.ynk.virtualdisplay.video

import com.ynk.virtualdisplay.protocol.ScrcpyFrameFlags
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class ScrcpyFrameReaderTest {

    private fun serializeFrame(ptsUs: Long, isConfig: Boolean, isKey: Boolean, data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        var ptsAndFlags = ptsUs and ScrcpyFrameFlags.PTS_MASK
        if (isConfig) {
            ptsAndFlags = ptsAndFlags or ScrcpyFrameFlags.FLAG_CONFIG
        }
        if (isKey) {
            ptsAndFlags = ptsAndFlags or ScrcpyFrameFlags.FLAG_KEY_FRAME
        }
        dos.writeLong(ptsAndFlags)
        dos.writeInt(data.size)
        dos.write(data)
        return baos.toByteArray()
    }

    @Test
    fun testReadValidFrame() {
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val frameBytes = serializeFrame(223344L, isConfig = true, isKey = false, data = payload)
        val inStream = ByteArrayInputStream(frameBytes)
        val reader = ScrcpyFrameReader(inStream)

        val frame = reader.readNextFrame()
        assertNotNull(frame)
        assertEquals(223344L, frame!!.ptsUs)
        assertTrue(frame.isConfig)
        assertFalse(frame.isKeyFrame)
        assertEquals(5, frame.size)
        assertArrayEquals(payload, frame.data)
    }

    @Test
    fun testReadTruncatedHeader() {
        val truncated = ByteArray(8) // Header expects 12 bytes
        val inStream = ByteArrayInputStream(truncated)
        val reader = ScrcpyFrameReader(inStream)

        try {
            reader.readNextFrame()
            fail("Should throw IOException due to truncated header")
        } catch (e: IOException) {
            // expected
        }
    }
}
