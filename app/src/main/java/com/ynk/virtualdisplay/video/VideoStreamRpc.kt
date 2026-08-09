package com.ynk.virtualdisplay.video

interface VideoStreamRpc {
    suspend fun startVideoStream(displayId: Int): Result<Unit>
    suspend fun stopVideoStream(): Result<Unit>
}
