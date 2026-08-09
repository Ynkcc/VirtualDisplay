package com.ynk.virtualdisplay.protocol

import java.io.DataInputStream
import java.nio.charset.StandardCharsets

sealed class DeviceMessage {

    data class GenericResponse(
        val sequence: Long,
        val statusCode: Int,
        val displayId: Int,
        val message: String?
    ) : DeviceMessage()

    data class ActiveDisplaysResponse(
        val sequence: Long,
        val displayIds: IntArray
    ) : DeviceMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActiveDisplaysResponse) return false
            return sequence == other.sequence && displayIds.contentEquals(other.displayIds)
        }

        override fun hashCode(): Int = 31 * sequence.hashCode() + displayIds.contentHashCode()
    }
}
