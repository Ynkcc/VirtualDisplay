package com.ynk.virtualdisplay.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal object DaemonHandshake {
    /**
     * 注意：不能用 `DataOutputStream(out).use { ... }`——`use` 会调用 close，
     * 而 close 会连带关闭传入的底层 [OutputStream]。调用方（DaemonTransport）
     * 会在同一个输出流上连续调用 writeRole / writeSessionId / writeDisplayId /
     * writeToken，若关闭底层流会破坏后续握手。因此这里只 `apply + flush`，
     * 绝不关闭传入的流。
     */
    fun writeRole(out: OutputStream, role: Int) {
        DataOutputStream(out).apply { writeByte(role); flush() }
    }

    fun writeSessionId(out: OutputStream, sessionId: Int) {
        DataOutputStream(out).apply { writeInt(sessionId); flush() }
    }

    fun writeDisplayId(out: OutputStream, displayId: Int) {
        DataOutputStream(out).apply { writeInt(displayId); flush() }
    }

    fun writeToken(out: OutputStream, token: String) {
        DataOutputStream(out).apply {
            val bytes = token.toByteArray(StandardCharsets.UTF_8)
            // 4 字节 Big-Endian 长度 + 标准 UTF-8 token bytes
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun readSessionId(input: InputStream): Int {
        // DataInputStream.readInt() 在 EOF 时抛 EOFException（IOException 子类），与原语义兼容
        return DataInputStream(input).readInt()
    }

    fun readInt32(input: InputStream): Int {
        // DataInputStream.readInt() 在 EOF 时抛 EOFException（IOException 子类），与原语义兼容
        return DataInputStream(input).readInt()
    }

    fun readDeviceMeta(input: InputStream): String {
        val bytes = ByteArray(64)
        // readFully 在 EOF 时抛 EOFException（IOException 子类），与原语义兼容
        DataInputStream(input).readFully(bytes)
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
