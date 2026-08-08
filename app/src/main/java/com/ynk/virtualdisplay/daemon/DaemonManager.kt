package com.ynk.virtualdisplay.daemon

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

class ClientDaemonManager(private val context: Context) {

    companion object {
        private const val TAG = "ClientDaemonManager"
    }

    private var process: Process? = null
    private var isRunning = false

    fun startDaemon(): Boolean {
        return try {
            val cmd = arrayOf(
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                "tunnel_forward=true",
                "audio=false",
                "send_device_meta=false",
                "send_dummy_byte=false",
                "send_stream_meta=false",
                "send_frame_meta=true"
            )

            val classpath = context.packageCodePath + ":" + context.applicationInfo.sourceDir
            val env = arrayOf("CLASSPATH=$classpath")

            val remoteProcess = invokeNewProcess(cmd, env, null)
            if (remoteProcess != null) {
                process = remoteProcess
                Thread.sleep(500)
                val alive = try {
                    remoteProcess.alive()
                } catch (_: Throwable) {
                    process?.isAlive ?: false
                }
                isRunning = alive
                if (alive) {
                    Log.i(TAG, "Daemon started successfully")
                } else {
                    Log.e(TAG, "Daemon process is not alive after start")
                    process = null
                }
                alive
            } else {
                Log.e(TAG, "Failed to invoke Shizuku.newProcess")
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startDaemon failed", e)
            false
        }
    }

    fun stopDaemon() {
        try {
            process?.let { p ->
                if (p is ShizukuRemoteProcess) {
                    p.destroy()
                } else {
                    p.destroy()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "stopDaemon failed", e)
        } finally {
            process = null
            isRunning = false
            Log.i(TAG, "Daemon stopped")
        }
    }

    fun isDaemonRunning(): Boolean {
        return try {
            process?.isAlive ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeNewProcess(cmd: Array<String>, env: Array<String>?, dir: String?): ShizukuRemoteProcess? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            method.invoke(null, cmd, env, dir) as? ShizukuRemoteProcess
        } catch (e: Throwable) {
            Log.e(TAG, "invokeNewProcess via reflection failed", e)
            null
        }
    }
}
