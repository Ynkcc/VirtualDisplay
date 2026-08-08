package com.ynk.virtualdisplay.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

object CustomControlMessage {

    const val TYPE_CREATE_VIRTUAL_DISPLAY: Byte = 101
    const val TYPE_RELEASE_VIRTUAL_DISPLAY: Byte = 102
    const val TYPE_RESIZE_VIRTUAL_DISPLAY: Byte = 103
    const val TYPE_START_ACTIVITY: Byte = 104
    const val TYPE_GET_ACTIVE_DISPLAY_IDS: Byte = 105
    const val TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID: Byte = 106
    const val TYPE_SWITCH_DISPLAY: Byte = 107

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

    private fun buildMessage(type: Byte, sequence: Long, block: DataOutputStream.() -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeByte(type.toInt())
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
