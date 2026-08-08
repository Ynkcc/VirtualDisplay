package com.ynk.virtualdisplay.daemon

import android.content.Context
import android.util.Log
import com.ynk.virtualdisplay.data.DaemonPrefs
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

class ClientDaemonManager(private val context: Context) {

    companion object {
        private const val TAG = "ClientDaemonManager"
    }

    private var process: Process? = null
    private var isRunning = false

    private val daemonPrefs = DaemonPrefs(context)

    fun getDaemonPid(): Int {
        val savedPort = daemonPrefs.getSavedPortSync()
        val port = if (savedPort > 0) savedPort else 27183
        return findDaemonPid(port)
    }

    private fun findDaemonPid(port: Int): Int {
        return try {
            val script = "for pid in \$(pgrep -f [c]om.genymobile.scrcpy.Server); do if cat /proc/\$pid/cmdline | grep -q \"port=$port\"; then echo \$pid; break; fi; done"
            val proc = invokeNewProcess(arrayOf("sh", "-c", script), null, null)
            if (proc != null) {
                val output = proc.inputStream.bufferedReader().use { it.readText() }.trim()
                proc.waitFor()
                if (output.isNotEmpty()) {
                    output.toIntOrNull() ?: -1
                } else {
                    -1
                }
            } else {
                -1
            }
        } catch (e: Throwable) {
            Log.e(TAG, "findDaemonPid failed for port $port", e)
            -1
        }
    }

    private fun getSavedPid(port: Int): Int {
        val pid = findDaemonPid(port)
        if (pid > 0) {
            savePid(port, pid)
            return pid
        }
        return daemonPrefs.getSavedPidForPort(port)
    }

    private fun savePid(port: Int, pid: Int) {
        daemonPrefs.savePid(port, pid)
    }

    private fun clearSavedPid() {
        daemonPrefs.clearSavedPid()
    }

    fun startDaemon(port: Int): Boolean {
        val savedPid = findDaemonPid(port)
        if (savedPid > 0) {
            Log.i(TAG, "Daemon is already running with pid $savedPid on port $port. Reusing it.")
            savePid(port, savedPid)
            isRunning = true
            return true
        }

        clearSavedPid()

        return try {
            val cmd = arrayOf(
                "sh",
                "-c",
                "nohup app_process / com.genymobile.scrcpy.Server ${com.genymobile.scrcpy.BuildConfig.VERSION_NAME} tunnel_forward=true audio=false send_device_meta=false send_dummy_byte=false send_stream_meta=false send_frame_meta=true --daemon --port=$port >/dev/null 2>&1 &"
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

                Thread.sleep(800)
                val activePid = findDaemonPid(port)
                val alive = activePid > 0
                isRunning = alive
                if (alive) {
                    Log.i(TAG, "Daemon started successfully with pid $activePid")
                    savePid(port, activePid)
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
        val savedPort = daemonPrefs.getSavedPortSync()
        val port = if (savedPort > 0) savedPort else 27183
        val pid = getSavedPid(port)
        try {
            Log.i(TAG, "Attempting to stop daemon with pid $pid...")
            if (pid > 0) {
                val proc = invokeNewProcess(arrayOf("kill", "-9", pid.toString()), null, null)
                proc?.waitFor()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "stopDaemon failed", e)
        } finally {
            process = null
            isRunning = false
            clearSavedPid()
            Log.i(TAG, "Daemon stopped")
        }
    }

    fun isDaemonRunning(): Boolean {
        val savedPort = daemonPrefs.getSavedPortSync()
        val port = if (savedPort > 0) savedPort else 27183
        return findDaemonPid(port) > 0
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
