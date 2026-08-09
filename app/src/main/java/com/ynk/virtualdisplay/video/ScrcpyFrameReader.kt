package com.ynk.virtualdisplay.video

import com.ynk.virtualdisplay.protocol.ScrcpyFrameFlags
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ScrcpyFrame(
    val ptsUs: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
    val data: ByteArray,
    val size: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScrcpyFrame) return false
        return ptsUs == other.ptsUs && isConfig == other.isConfig && isKeyFrame == other.isKeyFrame && data.contentEquals(other.data) && size == other.size
    }

    override fun hashCode(): Int {
        var result = ptsUs.hashCode()
        result = 31 * result + isConfig.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + size
        return result
    }
}

class ScrcpyFrameReader(private val input: InputStream) {

    private val headerBuf = ByteArray(12)
    private var packetBuf = ByteArray(1024 * 1024)

    fun readNextFrame(): ScrcpyFrame? {
        val read = readExact(headerBuf, 0, 12)
        if (read != 12) {
            if (read >= 0) {
                throw IOException("Truncated frame header: expected=12, got=$read")
            }
            return null
        }

        val header = ByteBuffer.wrap(headerBuf).apply { order(ByteOrder.BIG_ENDIAN) }
        val ptsAndFlags = header.long
        val packetSize = header.int

        if (packetSize < 0 || packetSize > 8 * 1024 * 1024) {
            throw IOException("Invalid packet size: $packetSize")
        }

        if (packetSize == 0) {
            return ScrcpyFrame(0L, false, false, ByteArray(0), 0)
        }

        if (packetSize > packetBuf.size) {
            packetBuf = ByteArray(packetSize)
        }

        val bytesRead = readExact(packetBuf, 0, packetSize)
        if (bytesRead != packetSize) {
            throw IOException("Truncated frame payload: expected=$packetSize, got=$bytesRead")
        }

        val isConfig = (ptsAndFlags and ScrcpyFrameFlags.FLAG_CONFIG) != 0L
        val isKeyFrame = (ptsAndFlags and ScrcpyFrameFlags.FLAG_KEY_FRAME) != 0L
        val ptsUs = ptsAndFlags and ScrcpyFrameFlags.PTS_MASK

        val data = ByteArray(packetSize)
        System.arraycopy(packetBuf, 0, data, 0, packetSize)

        return ScrcpyFrame(ptsUs, isConfig, isKeyFrame, data, packetSize)
    }

    private fun readExact(buffer: ByteArray, offset: Int, size: Int): Int {
        var total = 0
        while (total < size) {
            val count = input.read(buffer, offset + total, size - total)
            if (count < 0) {
                return if (total == 0) -1 else total
            }
            total += count
        }
        return total
    }
}
