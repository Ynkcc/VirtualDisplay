package com.ynk.virtualdisplay.net

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

internal object DaemonHandshake {
    fun writeRole(out: OutputStream, role: Int) {
        out.write(role)
        out.flush()
    }

    fun writeSessionId(out: OutputStream, sessionId: Int) {
        out.write((sessionId ushr 24) and 0xFF)
        out.write((sessionId ushr 16) and 0xFF)
        out.write((sessionId ushr 8) and 0xFF)
        out.write(sessionId and 0xFF)
        out.flush()
    }

    fun readSessionId(input: InputStream): Int {
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        val b4 = input.read()
        if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) {
            throw IOException("Connection closed before sessionId received")
        }
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    fun readDeviceMeta(input: InputStream): String {
        val bytes = ByteArray(64)
        var read = 0
        while (read < 64) {
            val count = input.read(bytes, read, 64 - read)
            if (count < 0) {
                throw IOException("Connection closed before device name received")
            }
            read += count
        }
        // 剥离尾部的 null 填充字节
        var length = 64
        for (i in 0 until 64) {
            if (bytes[i] == 0.toByte()) {
                length = i
                break
            }
        }
        return String(bytes, 0, length, StandardCharsets.UTF_8)
    }
}
