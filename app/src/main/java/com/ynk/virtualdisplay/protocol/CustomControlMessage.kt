package com.ynk.virtualdisplay.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

object CustomControlMessage {

    const val TYPE_CREATE_VIRTUAL_DISPLAY: Int = 201
    const val TYPE_RELEASE_VIRTUAL_DISPLAY: Int = 202
    const val TYPE_RESIZE_VIRTUAL_DISPLAY: Int = 203
    const val TYPE_START_ACTIVITY: Int = 204
    const val TYPE_GET_ACTIVE_DISPLAY_IDS: Int = 205
    const val TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID: Int = 206
    const val TYPE_SWITCH_DISPLAY: Int = 207
    const val TYPE_EXIT_DAEMON: Int = 208
    const val TYPE_START_VIDEO_STREAM: Int = 209
    const val TYPE_STOP_VIDEO_STREAM: Int = 210

    private val sequenceGen = AtomicLong(1L)

    fun nextSequence(): Long = sequenceGen.getAndIncrement()

    fun createCreateVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int, sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_CREATE_VIRTUAL_DISPLAY, sequence) {
            writeString(name)
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
            writeInt(flags)
        }
    }

    fun createReleaseVirtualDisplay(displayId: Int, sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_RELEASE_VIRTUAL_DISPLAY, sequence) {
            writeInt(displayId)
        }
    }

    fun createResizeVirtualDisplay(displayId: Int, width: Int, height: Int, dpi: Int, sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_RESIZE_VIRTUAL_DISPLAY, sequence) {
            writeInt(displayId)
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
        }
    }

    fun createStartActivity(packageName: String, displayId: Int, sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_START_ACTIVITY, sequence) {
            writeString(packageName)
            writeInt(displayId)
        }
    }

    fun createGetActiveDisplayIds(sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_GET_ACTIVE_DISPLAY_IDS, sequence) {
        }
    }

    fun createInjectInputEventWithDisplayId(displayId: Int, isKeyEvent: Boolean, parcelBytes: ByteArray, sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID, sequence) {
            writeInt(displayId)
            writeByte(if (isKeyEvent) 1 else 0)
            writeInt(parcelBytes.size)
            write(parcelBytes)
        }
    }

    fun createSwitchDisplay(displayId: Int, sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_SWITCH_DISPLAY, sequence) {
            writeInt(displayId)
        }
    }

    fun createExitDaemon(sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_EXIT_DAEMON, sequence) {
        }
    }

    fun createStartVideoStream(displayId: Int, sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_START_VIDEO_STREAM, sequence) {
            writeInt(displayId)
        }
    }

    fun createStopVideoStream(sequence: Long = nextSequence()): ByteArray {
        return buildMessage(TYPE_STOP_VIDEO_STREAM, sequence) {
        }
    }

    private fun buildMessage(type: Int, sequence: Long, block: DataOutputStream.() -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeByte(type)
            dos.writeLong(sequence)
            dos.block()
        }
        return baos.toByteArray()
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
