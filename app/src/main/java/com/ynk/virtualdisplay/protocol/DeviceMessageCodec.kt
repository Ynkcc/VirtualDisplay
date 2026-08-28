package com.ynk.virtualdisplay.protocol

import java.io.DataInputStream
import java.nio.charset.StandardCharsets

object DeviceMessageCodec {
    const val TYPE_RESPONSE_GENERIC: Byte = 100
    const val TYPE_RESPONSE_ACTIVE_DISPLAYS: Byte = 101
    const val TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS: Byte = 102
    const val TYPE_RESPONSE_APPS_LIST: Byte = 103

    fun read(input: DataInputStream): DeviceMessage {
        val type = input.readByte()
        return when (type) {
            TYPE_RESPONSE_GENERIC -> readGenericResponse(input)
            TYPE_RESPONSE_ACTIVE_DISPLAYS -> readActiveDisplaysResponse(input)
            TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS -> readActiveDisplayInfosResponse(input)
            TYPE_RESPONSE_APPS_LIST -> readAppsListResponse(input)
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
            val mirrorDisplayId = input.readInt()
            val isOwned = input.readByte().toInt() != 0
            val ownerUid = input.readInt()
            val ownerLen = input.readInt()
            val ownerPackage = if (ownerLen > 0) {
                val ownerBytes = ByteArray(ownerLen)
                input.readFully(ownerBytes)
                String(ownerBytes, StandardCharsets.UTF_8)
            } else {
                null
            }
            displays.add(
                DeviceMessage.DisplayInfoEntry(
                    displayId, width, height, dpi, rotation, mirrorDisplayId, isOwned, ownerUid, ownerPackage
                )
            )
        }
        return DeviceMessage.ActiveDisplayInfosResponse(sequence, displays)
    }

    private fun readAppsListResponse(input: DataInputStream): DeviceMessage.AppsListResponse {
        val sequence = input.readLong()
        val count = input.readInt()
        val apps = ArrayList<DeviceMessage.AppEntry>(count)
        for (i in 0 until count) {
            val pkgLen = input.readInt()
            val pkgBytes = ByteArray(pkgLen)
            input.readFully(pkgBytes)
            val pkgName = String(pkgBytes, StandardCharsets.UTF_8)
            val nameLen = input.readInt()
            val nameBytes = ByteArray(nameLen)
            input.readFully(nameBytes)
            val name = if (nameLen > 0) String(nameBytes, StandardCharsets.UTF_8) else pkgName
            val isSystem = input.readByte().toInt() != 0
            apps.add(DeviceMessage.AppEntry(pkgName, name, isSystem))
        }
        return DeviceMessage.AppsListResponse(sequence, apps)
    }
}
