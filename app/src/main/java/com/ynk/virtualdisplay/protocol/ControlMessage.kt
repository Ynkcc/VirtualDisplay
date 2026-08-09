package com.ynk.virtualdisplay.protocol
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

sealed class ControlMessage {
    abstract val type: Int
    abstract fun encode(sequence: Long): ByteArray

    data class CreateVirtualDisplay(
        val name: String,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val flags: Int
    ) : ControlMessage() {
        override val type: Int = TYPE_CREATE_VIRTUAL_DISPLAY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeString(name)
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
            writeInt(flags)
        }
    }

    data class ReleaseVirtualDisplay(val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_RELEASE_VIRTUAL_DISPLAY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
        }
    }

    data class ResizeVirtualDisplay(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val dpi: Int
    ) : ControlMessage() {
        override val type: Int = TYPE_RESIZE_VIRTUAL_DISPLAY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
        }
    }

    data class StartActivity(val packageName: String, val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_START_ACTIVITY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeString(packageName)
            writeInt(displayId)
        }
    }

    object GetActiveDisplayIds : ControlMessage() {
        override val type: Int = TYPE_GET_ACTIVE_DISPLAY_IDS
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {}
    }

    data class InjectInputEvent(
        val displayId: Int,
        val isKeyEvent: Boolean,
        val parcelBytes: ByteArray
    ) : ControlMessage() {
        override val type: Int = TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
            writeByte(if (isKeyEvent) 1 else 0)
            writeInt(parcelBytes.size)
            write(parcelBytes)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InjectInputEvent) return false
            return displayId == other.displayId && isKeyEvent == other.isKeyEvent && parcelBytes.contentEquals(other.parcelBytes)
        }

        override fun hashCode(): Int {
            var result = displayId
            result = 31 * result + isKeyEvent.hashCode()
            result = 31 * result + parcelBytes.contentHashCode()
            return result
        }
    }

    data class SwitchDisplay(val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_SWITCH_DISPLAY
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
        }
    }

    object ExitDaemon : ControlMessage() {
        override val type: Int = TYPE_EXIT_DAEMON
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {}
    }

    data class StartVideoStream(val displayId: Int) : ControlMessage() {
        override val type: Int = TYPE_START_VIDEO_STREAM
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {
            writeInt(displayId)
        }
    }

    object StopVideoStream : ControlMessage() {
        override val type: Int = TYPE_STOP_VIDEO_STREAM
        override fun encode(sequence: Long): ByteArray = buildMessage(type, sequence) {}
    }

    protected fun buildMessage(type: Int, sequence: Long, block: DataOutputStream.() -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeByte(type)
            dos.writeLong(sequence)
            dos.block()
        }
        return baos.toByteArray()
    }

    companion object {
        const val TYPE_CREATE_VIRTUAL_DISPLAY = 201
        const val TYPE_RELEASE_VIRTUAL_DISPLAY = 202
        const val TYPE_RESIZE_VIRTUAL_DISPLAY = 203
        const val TYPE_START_ACTIVITY = 204
        const val TYPE_GET_ACTIVE_DISPLAY_IDS = 205
        const val TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID = 206
        const val TYPE_SWITCH_DISPLAY = 207
        const val TYPE_EXIT_DAEMON = 208
        const val TYPE_START_VIDEO_STREAM = 209
        const val TYPE_STOP_VIDEO_STREAM = 210
    }
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}
