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

    fun writeDisplayId(out: OutputStream, displayId: Int) {
        out.write((displayId ushr 24) and 0xFF)
        out.write((displayId ushr 16) and 0xFF)
        out.write((displayId ushr 8) and 0xFF)
        out.write(displayId and 0xFF)
        out.flush()
    }

    fun writeToken(out: OutputStream, token: String) {
        val bytes = token.toByteArray(StandardCharsets.UTF_8)
        // 4 字节 Big-Endian 长度 + UTF-8 token bytes
        out.write((bytes.size ushr 24) and 0xFF)
        out.write((bytes.size ushr 16) and 0xFF)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
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

    fun readInt32(input: InputStream): Int {
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        val b4 = input.read()
        if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) {
            throw IOException("Connection closed before 32-bit value received")
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
