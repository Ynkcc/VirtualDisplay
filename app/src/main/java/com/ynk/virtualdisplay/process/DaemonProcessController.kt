package com.ynk.virtualdisplay.process

import android.content.Context
import android.util.Log
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.DaemonPrefs
import com.ynk.virtualdisplay.data.PrivilegeMode
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class DaemonProcessController(private val context: Context) {

    companion object {
        private const val TAG = "DaemonProcessController"
        private const val PORT_READY_TIMEOUT_MS = 5000L
        private const val PORT_POLL_INTERVAL_MS = 100L
        private const val PORT_PROBE_TIMEOUT_MS = 300
    }

    // Written from startDaemon/stopDaemon on Dispatchers.IO and referenced
    // across coroutine boundaries; @Volatile for visibility. (isRunning was a
    // dead field — never read — so it has been removed.)
    @Volatile private var process: Process? = null

    // Cache of the running daemon pid so UI-facing getDaemonPid() callers don't
    // repeatedly spawn a blocking shell pgrep. Cleared on stop/start; findDaemonPid()
    // is still used directly by isDaemonRunning()/stopDaemon() when fresh liveness
    // data is required.
    @Volatile private var cachedPid: Int = -1

    private val daemonPrefs = DaemonPrefs(context)

    fun getDaemonPid(): Int {
        if (cachedPid > 0) return cachedPid
        val savedPort = daemonPrefs.getSavedPortSync()
        val port = if (savedPort > 0) savedPort else 27183
        val pid = findDaemonPid(port)
        if (pid > 0) cachedPid = pid
        return pid
    }

    private fun findDaemonPid(port: Int): Int {
        val mode = AppSettings.getPrivilegeModeSync()
        if (mode == PrivilegeMode.NONE) return -1
        return try {
            val script = "for pid in \$(pgrep -f [c]om.genymobile.scrcpy.Server); do if cat /proc/\$pid/cmdline | grep -q \"daemon_port=$port\"; then echo \$pid; break; fi; done"
            val proc = executeCommand(arrayOf("sh", "-c", script), null, null)
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
        } catch (e: Exception) {
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

    fun startDaemon(port: Int, address: String = "127.0.0.1", password: String? = null): Boolean {
        val mode = AppSettings.getPrivilegeModeSync()
        if (mode == PrivilegeMode.NONE) {
            Log.i(TAG, "None mode, skipping startDaemon")
            return false
        }
        val savedPid = findDaemonPid(port)
        if (savedPid > 0) {
            Log.i(TAG, "Daemon is already running with pid $savedPid on port $port. Reusing it.")
            savePid(port, savedPid)
            cachedPid = savedPid
            return true
        }

        clearSavedPid()
        cachedPid = -1

        return try {
            val classpath = context.packageCodePath + ":" + context.applicationInfo.sourceDir
            // Debug 构建开启 VERBOSE 日志便于排查；Release 构建降至 INFO 减少日志噪声。
            val logLevel = if (com.ynk.virtualdisplay.BuildConfig.DEBUG) "VERBOSE" else "INFO"
            val passwordArg = if (!password.isNullOrEmpty()) " daemon_secret_token='${password.replace("'", "'\\''")}'" else ""
            val cmd = arrayOf(
                "sh",
                "-c",
                "export CLASSPATH=$classpath && nohup app_process / com.genymobile.scrcpy.Server ${com.genymobile.scrcpy.BuildConfig.VERSION_NAME} tunnel_forward=true audio=false send_device_meta=false send_dummy_byte=false send_stream_meta=false send_frame_meta=true cleanup=false log_level=$logLevel daemon=true daemon_port=$port daemon_bind_address=$address$passwordArg >/dev/null 2>&1 &"
            )

            val envList = mutableListOf<String>()
            System.getenv().forEach { (key, value) ->
                if (key != "CLASSPATH") {
                    envList.add("$key=$value")
                }
            }
            envList.add("CLASSPATH=$classpath")
            val env = envList.toTypedArray()

            val remoteProcess = executeCommand(cmd, env, null)
            if (remoteProcess != null) {
                process = remoteProcess

                // Wait until the daemon's ServerSocket actually accepts connections.
                // The previous fixed Thread.sleep(800) both wasted time when the port
                // was already up and was often insufficient (app_process JVM boot can
                // take >1s), forcing transport.connect into retry backoff. Polling the
                // TCP port returns as soon as the daemon is ready and yields a precise
                // "not listening" error otherwise. The probe connects and closes without
                // writing a role byte; the server's accept loop treats this as an EOF
                // on readSocketRole and continues (harmless one-line warning).
                val ready = waitForPort(address, port, PORT_READY_TIMEOUT_MS)
                if (ready) {
                    // Best-effort pid resolution: pgrep may briefly miss the process
                    // (cmdline not populated yet), but that does not affect connectivity.
                    val activePid = findDaemonPid(port)
                    if (activePid > 0) {
                        Log.i(TAG, "Daemon started successfully with pid $activePid on port $port")
                        savePid(port, activePid)
                        cachedPid = activePid
                    } else {
                        Log.i(TAG, "Daemon port $port is accepting connections (pid not resolved yet)")
                    }
                } else {
                    Log.e(TAG, "Daemon process spawned but port $port not accepting within ${PORT_READY_TIMEOUT_MS}ms")
                    process = null
                }
                ready
            } else {
                Log.e(TAG, "Failed to executeCommand")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "startDaemon failed", e)
            false
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        val connectHost = if (host == "0.0.0.0") "127.0.0.1" else host
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(connectHost, port), PORT_PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(host, port)) return true
            try {
                Thread.sleep(PORT_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun stopDaemon() {
        val mode = AppSettings.getPrivilegeModeSync()
        if (mode == PrivilegeMode.NONE) {
            Log.i(TAG, "None mode, skipping stopDaemon")
            return
        }
        val savedPort = daemonPrefs.getSavedPortSync()
        val port = if (savedPort > 0) savedPort else 27183
        val pid = getSavedPid(port)
        try {
            Log.i(TAG, "Attempting to stop daemon with pid $pid...")
            if (pid > 0) {
                var proc = executeCommand(arrayOf("kill", pid.toString()), null, null)
                proc?.waitFor()
                
                var checkCount = 0
                while (checkCount < 10 && findDaemonPid(port) == pid) {
                    Thread.sleep(100)
                    checkCount++
                }

                if (findDaemonPid(port) == pid) {
                    Log.w(TAG, "Daemon process $pid still alive after SIGTERM, sending SIGKILL...")
                    proc = executeCommand(arrayOf("kill", "-9", pid.toString()), null, null)
                    proc?.waitFor()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopDaemon failed", e)
        } finally {
            process = null
            cachedPid = -1
            clearSavedPid()
            Log.i(TAG, "Daemon stopped")
        }
    }

    fun isDaemonRunning(): Boolean {
        val mode = AppSettings.getPrivilegeModeSync()
        if (mode == PrivilegeMode.NONE) return false
        val savedPort = daemonPrefs.getSavedPortSync()
        val port = if (savedPort > 0) savedPort else 27183
        return findDaemonPid(port) > 0
    }

    private fun executeCommand(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process? {
        val mode = AppSettings.getPrivilegeModeSync()
        return when (mode) {
            PrivilegeMode.SHIZUKU -> {
                invokeNewProcess(cmd, env, dir)
            }
            PrivilegeMode.ROOT -> {
                try {
                    val suCmd = if (cmd.size >= 2 && cmd[0] == "sh" && cmd[1] == "-c") {
                        arrayOf("su", "-c", cmd[2])
                    } else {
                        arrayOf("su", "-c", cmd.joinToString(" "))
                    }
                    val pb = ProcessBuilder(*suCmd)
                    if (env != null) {
                        val pbEnv = pb.environment()
                        env.forEach { e ->
                            val parts = e.split("=", limit = 2)
                            if (parts.size == 2) {
                                pbEnv[parts[0]] = parts[1]
                            }
                        }
                    }
                    if (dir != null) pb.directory(java.io.File(dir))
                    pb.redirectErrorStream(true) // 合并错误流
                    pb.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Root execution failed: ${e.message}", e)
                    null
                }
            }
            PrivilegeMode.NONE -> {
                try {
                    Runtime.getRuntime().exec(cmd, env, dir?.let { java.io.File(it) })
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun invokeNewProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, env, dir) as? Process
        } catch (e: Exception) {
            Log.e(TAG, "invokeNewProcess via reflection failed", e)
            null
        }
    }
}
