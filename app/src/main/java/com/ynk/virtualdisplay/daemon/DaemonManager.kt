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

    fun startDaemon(port: Int): Boolean {
        return try {
            val cmd = arrayOf(
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                com.genymobile.scrcpy.BuildConfig.VERSION_NAME,
                "tunnel_forward=true",
                "audio=false",
                "send_device_meta=false",
                "send_dummy_byte=false",
                "send_stream_meta=false",
                "send_frame_meta=true",
                "--daemon",
                "--port=$port"
            )

            val classpath = context.packageCodePath + ":" + context.applicationInfo.sourceDir
            val envList = mutableListOf<String>()
            System.getenv().forEach { (key, value) ->
                if (key != "CLASSPATH") {
                    envList.add("$key=$value")
                }
            }
            envList.add("CLASSPATH=$classpath")
            val env = envList.toTypedArray()

            val remoteProcess = invokeNewProcess(cmd, env, null)
            if (remoteProcess != null) {
                process = remoteProcess

                // 异步读取错误和标准输出流并输出到 Logcat
                Thread {
                    try {
                        remoteProcess.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { line -> Log.e(TAG, "[Daemon-Stderr] $line") }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error reading daemon stderr", e)
                    }
                }.start()
                Thread {
                    try {
                        remoteProcess.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line -> Log.i(TAG, "[Daemon-Stdout] $line") }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error reading daemon stdout", e)
                    }
                }.start()

                Thread.sleep(800)
                val alive = isProcessAlive(remoteProcess)
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

    private fun getProcessPid(process: Process): Int {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.get(process) as Int
        } catch (e: Throwable) {
            Log.d(TAG, "Pid field reflection failed, trying pid() method", e)
            try {
                val method = process.javaClass.getMethod("pid")
                (method.invoke(process) as Long).toInt()
            } catch (ex: Throwable) {
                Log.d(TAG, "Pid method reflection also failed", ex)
                -1
            }
        }
    }

    private fun isProcessAlive(proc: Process): Boolean {
        try {
            val exitCode = proc.exitValue()
            Log.d(TAG, "isProcessAlive check: Process has exited with code $exitCode")
            return false
        } catch (e: IllegalThreadStateException) {
            return true
        } catch (e: IllegalArgumentException) {
            // ShizukuRemoteProcess 底层通常在进程未退出时抛出此异常
            return true
        } catch (e: Throwable) {
            val alive = try {
                if (proc is ShizukuRemoteProcess) {
                    proc.alive()
                } else {
                    proc.isAlive
                }
            } catch (_: Throwable) {
                proc.isAlive
            }
            Log.d(TAG, "isProcessAlive fallback check: $alive")
            return alive
        }
    }

    fun stopDaemon() {
        val proc = process ?: return
        try {
            Log.i(TAG, "Attempting to stop daemon gracefully...")
            proc.destroy()

            var isDead = false
            for (i in 0 until 10) {
                val alive = isProcessAlive(proc)
                if (!alive) {
                    isDead = true
                    break
                }
                try {
                    Thread.sleep(100)
                } catch (ie: InterruptedException) {
                    Log.w(TAG, "stopDaemon interrupted while waiting for graceful termination")
                    Thread.currentThread().interrupt()
                    break
                }
            }

            if (!isDead) {
                val pid = getProcessPid(proc)
                if (pid > 0) {
                    Log.w(TAG, "Daemon process still alive after 1s, force killing (kill -9 $pid)...")
                    invokeNewProcess(arrayOf("kill", "-9", pid.toString()), null, null)?.destroy()
                } else {
                    Log.e(TAG, "Failed to get process pid, cannot force kill")
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
        val proc = process ?: return false
        return isProcessAlive(proc)
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
