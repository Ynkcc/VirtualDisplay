package com.ynk.virtualdisplay.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 守护进程 TCP 握手的线格式读写工具。
 *
 * 注意：写入函数不能用 `use { ... }`——`use` 会调用 close 并连带关闭传入的
 * 底层 [OutputStream]。调用方（DaemonTransport）会在同一输出流上连续调用
 * writeRole / writeSessionId / writeDisplayId / writeToken，关闭底层流会破坏
 * 后续握手。因此这里只 `apply + flush`，绝不关闭传入的流。
 */
internal object DaemonHandshake {
    /** 写入 1 字节的角色值。 */
    fun writeRole(out: OutputStream, role: Int) {
        DataOutputStream(out).apply { writeByte(role); flush() }
    }

    /** 写入 4 字节 Big-Endian 的会话 ID。 */
    fun writeSessionId(out: OutputStream, sessionId: Int) {
        DataOutputStream(out).apply { writeInt(sessionId); flush() }
    }

    /** 写入 4 字节 Big-Endian 的显示器 ID。 */
    fun writeDisplayId(out: OutputStream, displayId: Int) {
        DataOutputStream(out).apply { writeInt(displayId); flush() }
    }

    /**
     * 写入认证 token，线格式为：4 字节 Big-Endian 长度 + 标准 UTF-8 字节。
     */
    fun writeToken(out: OutputStream, token: String) {
        DataOutputStream(out).apply {
            val bytes = token.toByteArray(StandardCharsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    /**
     * 读取 4 字节 Big-Endian 会话 ID。
     *
     * @throws java.io.EOFException EOF 时抛出（IOException 子类）
     */
    fun readSessionId(input: InputStream): Int {
        return DataInputStream(input).readInt()
    }

    /**
     * 读取 4 字节 Big-Endian 整数。
     *
     * @throws java.io.EOFException EOF 时抛出（IOException 子类）
     */
    fun readInt32(input: InputStream): Int {
        return DataInputStream(input).readInt()
    }

    /**
     * 读取 64 字节定长的设备元数据，并剥离尾部的 null 填充。
     *
     * @throws java.io.EOFException 不足 64 字节时抛出
     */
    fun readDeviceMeta(input: InputStream): String {
        val bytes = ByteArray(64)
        DataInputStream(input).readFully(bytes)
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
