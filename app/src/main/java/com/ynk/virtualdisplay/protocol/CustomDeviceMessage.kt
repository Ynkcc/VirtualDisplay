package com.ynk.virtualdisplay.protocol

import java.io.DataInputStream
import java.nio.charset.StandardCharsets

sealed class CustomDeviceMessage {

    data class GenericResponse(
        val sequence: Long,
        val statusCode: Int,
        val displayId: Int,
        val message: String?
    ) : CustomDeviceMessage()

    data class ActiveDisplaysResponse(
        val sequence: Long,
        val displayIds: IntArray
    ) : CustomDeviceMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActiveDisplaysResponse) return false
            return sequence == other.sequence && displayIds.contentEquals(other.displayIds)
        }

        override fun hashCode(): Int = 31 * sequence.hashCode() + displayIds.contentHashCode()
    }
}

object CustomDeviceMessageReader {

    const val TYPE_RESPONSE_GENERIC: Byte = 100
    const val TYPE_RESPONSE_ACTIVE_DISPLAYS: Byte = 101

    fun read(input: DataInputStream): CustomDeviceMessage {
        val type = input.readByte()
        return when (type) {
            TYPE_RESPONSE_GENERIC -> readGenericResponse(input)
            TYPE_RESPONSE_ACTIVE_DISPLAYS -> readActiveDisplaysResponse(input)
            else -> throw IllegalArgumentException("Unknown device message type: $type")
        }
    }

    private fun readGenericResponse(input: DataInputStream): CustomDeviceMessage.GenericResponse {
        val sequence = input.readLong()
        val statusCode = input.readInt()
        val displayId = input.readInt()
        val stringLength = input.readInt()
        val message = if (stringLength > 0) {
            val bytes = ByteArray(stringLength)
            input.readFully(bytes)
            String(bytes, StandardCharsets.UTF_8)
        } else {
            null
        }
        return CustomDeviceMessage.GenericResponse(sequence, statusCode, displayId, message)
    }

    private fun readActiveDisplaysResponse(input: DataInputStream): CustomDeviceMessage.ActiveDisplaysResponse {
        val sequence = input.readLong()
        val count = input.readInt()
        val displayIds = IntArray(count)
        for (i in 0 until count) {
            displayIds[i] = input.readInt()
        }
        return CustomDeviceMessage.ActiveDisplaysResponse(sequence, displayIds)
    }
}
