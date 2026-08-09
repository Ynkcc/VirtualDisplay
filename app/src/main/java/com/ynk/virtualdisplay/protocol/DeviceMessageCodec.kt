package com.ynk.virtualdisplay.protocol

import java.io.DataInputStream
import java.nio.charset.StandardCharsets

object DeviceMessageCodec {
    const val TYPE_RESPONSE_GENERIC: Byte = 100
    const val TYPE_RESPONSE_ACTIVE_DISPLAYS: Byte = 101
    const val TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS: Byte = 102

    fun read(input: DataInputStream): DeviceMessage {
        val type = input.readByte()
        return when (type) {
            TYPE_RESPONSE_GENERIC -> readGenericResponse(input)
            TYPE_RESPONSE_ACTIVE_DISPLAYS -> readActiveDisplaysResponse(input)
            TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS -> readActiveDisplayInfosResponse(input)
            else -> throw IllegalArgumentException("Unknown device message type: $type")
        }
    }

    private fun readGenericResponse(input: DataInputStream): DeviceMessage.GenericResponse {
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
        return DeviceMessage.GenericResponse(sequence, statusCode, displayId, message)
    }

    private fun readActiveDisplaysResponse(input: DataInputStream): DeviceMessage.ActiveDisplaysResponse {
        val sequence = input.readLong()
        val count = input.readInt()
        val displayIds = IntArray(count)
        for (i in 0 until count) {
            displayIds[i] = input.readInt()
        }
        return DeviceMessage.ActiveDisplaysResponse(sequence, displayIds)
    }

    private fun readActiveDisplayInfosResponse(input: DataInputStream): DeviceMessage.ActiveDisplayInfosResponse {
        val sequence = input.readLong()
        val count = input.readInt()
        val displays = ArrayList<DeviceMessage.DisplayInfoEntry>(count)
        for (i in 0 until count) {
            val displayId = input.readInt()
            val width = input.readInt()
            val height = input.readInt()
            val dpi = input.readInt()
            val rotation = input.readInt()
            displays.add(DeviceMessage.DisplayInfoEntry(displayId, width, height, dpi, rotation))
        }
        return DeviceMessage.ActiveDisplayInfosResponse(sequence, displays)
    }
}
