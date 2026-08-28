package com.ynk.virtualdisplay.video

import com.ynk.virtualdisplay.protocol.ScrcpyFrameFlags
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 一帧 scrcpy 视频帧。pts 与标志位（config/keyFrame）从帧头解析得出，
 * data 为该帧 H264 编码数据的一份独立拷贝。
 */
data class ScrcpyFrame(
    /** 帧的呈现时间戳（微秒），由帧头 pts 字段低 62 位解析。 */
    val ptsUs: Long,

    /** 是否为配置帧（SPS/PPS 等编解码器配置数据）。 */
    val isConfig: Boolean,

    /** 是否为关键帧（IDR）。 */
    val isKeyFrame: Boolean,

    /** 该帧的 H264 编码数据。 */
    val data: ByteArray,

    /** 该帧数据的有效字节数。 */
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

/**
 * 从 [InputStream] 逐帧读取 scrcpy 视频流。帧格式为 12 字节大端帧头
 * （8 字节 pts+flags，4 字节负载长度）后跟 H264 负载；返回 null 表示流已结束。
 */
class ScrcpyFrameReader(private val input: InputStream) {

    private val headerBuf = ByteArray(12)
    private var packetBuf = ByteArray(1024 * 1024)

    /**
     * 读取下一帧。解析并校验帧头与负载长度，返回解析后的帧；流正常结束返回 null。
     * 负载长度非法（负数或超过 8MB）或读取不完整时抛出 [IOException]。
     *
     * @return 解析后的 [ScrcpyFrame]；输入流结束返回 null。
     * @throws IOException 帧头被截断、负载长度非法或负载读取不完整时抛出。
     */
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
