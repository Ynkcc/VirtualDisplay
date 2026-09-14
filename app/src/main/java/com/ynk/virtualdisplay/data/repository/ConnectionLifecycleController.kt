package com.ynk.virtualdisplay.data.repository

import android.util.Log
import com.ynk.virtualdisplay.util.NetUtils
import com.ynk.virtualdisplay.rpc.DaemonRpcState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 连接生命周期控制器：编排连接 / 重连 / daemon 拉起 / 清理的时序。
 *
 * ### 并发契约
 * 本类所有方法（含 [scheduleAutoReconnect] / [launchCleanupOnce]）都只允许在
 * [ConnectionSlot.slotDispatcher] 单线程串行域上调用，因此 Job 字段无需锁保护；
 * 原先的 `synchronized(reconnectLock/cleanupLock)` 已因串行化而移除。
 */
internal class ConnectionLifecycleController(
    private val slot: ConnectionSlot,
    private val res: SlotResources
) {
    companion object {
        private const val CLEANUP_TIMEOUT_MS = 3000L
    }

    private val node get() = slot.node
    private val scope get() = slot.scope
    private val nodeKey get() = node.uniqueKey()

    private var reconnectJob: Job? = null
    private var cleanupJob: Job? = null

    /** 监听 RPC 消息循环异常退出，触发自动重连。 */
    fun startObserving() {
        scope.launch {
            res.rpc.connectionState.collect { state ->
                if (state == DaemonRpcState.LOOP_EXITED_ABNORMALLY && res.isBound) {
                    Log.w(ConnectionSlot.TAG, "[$nodeKey] RPC loop exited abnormally, scheduling auto-reconnect")
                    scheduleAutoReconnect()
                }
            }
        }
    }

    fun bindService() {
        scope.launch {
            if (res.isBound) return@launch
            connectInternal()
        }
    }

    fun unbindService() {
        scope.launch {
            if (!res.isBound) return@launch
            res.isBound = false
            launchCleanupOnce(killDaemon = false)
        }
    }

    /**
     * 建立服务端连接的核心流程（纯连接，不拉起 daemon——拉起唯一入口是设置页"启动"）。
     *
     * 连接目标在进入本流程时通过 [SlotResources.snapshotConnectTarget] 读取一次并快照，
     * 连接存续期间修改监听配置不影响当前连接，也不会触发重连。
     *
     * 成功则标记连接并刷新显示；失败则进入 ERROR 状态并由后台自动重连自愈。
     * @return 连接结果，失败时 [Result.failure] 携带用户可读的中文错误。
     */
    private suspend fun connectInternal(): Result<Unit> {
        res.setStatus(ConnectionStatus.BINDING)
        val target = res.snapshotConnectTarget()

        return try {
            val error = res.connectTransport(target)
            if (error == null) {
                res.markConnected(scope)
                res.refreshManagedDisplays()
            } else {
                res.setError(error)
                res.applyConnectFailure(target)
                scheduleAutoReconnect()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(ConnectionSlot.TAG, "[$nodeKey] connectInternal failed", e)
            res.setError(NetUtils.getFriendlyErrorMessage(e))
            res.setStatus(ConnectionStatus.ERROR)
            // 权限/启动类异常无法通过重连自愈，撤销绑定意图（否则 bindService 将被短路）。
            res.isBound = false
            Result.failure(e)
        }
    }

    /**
     * 后台自动重连：带指数退避的 daemon 存活检查 + transport 重连。
     * 以 [SlotResources.isBound] 作为外层守卫，解绑 / 清理时会取消该 Job 并退出循环。
     */
    private fun scheduleAutoReconnect() {
        val existing = reconnectJob
        if (existing != null && existing.isActive) return
        reconnectJob = scope.launch {
            val target = res.snapshotConnectTarget()

            var attempt = 0
            val baseDelayMs = 200L
            val maxDelayMs = 3000L
            val maxRemoteAttempts = if (node.isLocal) Int.MAX_VALUE else 5

            while (res.isBound) {
                attempt++
                val delayMs = (baseDelayMs * (1L shl (attempt - 1))).coerceAtMost(maxDelayMs)
                res.setStatus(ConnectionStatus.RECONNECTING)

                // 确保 daemon 存活（仅本机特权节点）；拉起失败则退避后重试
                if (!res.ensureDaemonAlive(target)) {
                    delay(delayMs)
                    continue
                }

                Log.i(ConnectionSlot.TAG, "[$nodeKey] Reconnecting transport (attempt $attempt)...")
                val error = res.connectTransport(target)
                if (error == null) {
                    res.markConnected(scope)
                    res.refreshManagedDisplays()
                    Log.i(ConnectionSlot.TAG, "[$nodeKey] Reconnect succeeded on attempt $attempt")
                    return@launch
                }
                Log.w(ConnectionSlot.TAG, "[$nodeKey] Reconnect failed (attempt $attempt), backoff ${delayMs}ms")
                if (attempt >= maxRemoteAttempts) {
                    Log.w(ConnectionSlot.TAG, "[$nodeKey] Max attempts reached, giving up")
                    res.setError("服务端已断开，请手动重连")
                    res.setStatus(ConnectionStatus.DISCONNECTED)
                    // 放弃重连后释放绑定意图，允许后续手动重选节点再次触发连接
                    res.isBound = false
                    return@launch
                }
                delay(delayMs)
            }
        }
    }

    /** 幂等去重的清理任务：同时刻仅执行一次清理。 */
    private fun launchCleanupOnce(killDaemon: Boolean): Job {
        val existing = cleanupJob
        if (existing != null && existing.isActive) return existing
        return scope.launch { performCleanup(killDaemon) }.also { cleanupJob = it }
    }

    /**
     * 统一清理流程。停止顺序必须严格保持：
     * reconnectJob.cancel → videoController.stop → exitDaemon → rpc.stopMessageLoop
     * → transport.disconnect → stopDaemon。
     *
     * 每一步独立兜底：任一步失败都不得中断后续步骤，否则会残留 daemon 进程或卡死连接状态。
     */
    private suspend fun performCleanup(killDaemon: Boolean) {
        res.isBound = false
        reconnectJob?.cancel()
        reconnectJob = null

        res.stopVideo().onFailure { Log.w(ConnectionSlot.TAG, "[$nodeKey] stop video failed", it) }

        if (killDaemon) {
            runCatching { res.exitDaemon() }
                .onFailure { Log.w(ConnectionSlot.TAG, "[$nodeKey] exitDaemon failed", it) }
            delay(100)
        }
        runCatching { res.stopMessageLoop() }
            .onFailure { Log.w(ConnectionSlot.TAG, "[$nodeKey] stopMessageLoop failed", it) }
        runCatching { res.disconnectTransport() }
            .onFailure { Log.w(ConnectionSlot.TAG, "[$nodeKey] disconnectTransport failed", it) }
        if (killDaemon && res.processDataSource != null) {
            runCatching { res.stopDaemonProcess() }
                .onFailure { Log.w(ConnectionSlot.TAG, "[$nodeKey] stopDaemonProcess failed", it) }
            res.resetDaemonPid()
        }
        res.resetConnectionState()
    }

    /** 仅拉起本机 daemon 进程，不建立连接。全应用唯一拉起入口（设置页"启动"）。 */
    suspend fun startDaemon(): Result<Unit> = try {
        res.startDaemonProcess()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(ConnectionSlot.TAG, "[$nodeKey] startDaemon (launch only) failed", e)
        Result.failure(e)
    }

    suspend fun stopDaemon(): Result<Unit> = runCleanupAndAwait(killDaemon = true, action = "停止服务端")

    suspend fun connect(): Result<Unit> {
        if (res.connectionStatus.value == ConnectionStatus.CONNECTED) {
            return Result.success(Unit)
        }
        if (res.isBound) {
            val job = launchCleanupOnce(killDaemon = false)
            withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { job.join() }
        }
        return connectInternal()
    }

    suspend fun disconnect(): Result<Unit> = runCleanupAndAwait(killDaemon = false, action = "断开连接")

    private suspend fun runCleanupAndAwait(killDaemon: Boolean, action: String): Result<Unit> {
        res.isBound = false
        val job = launchCleanupOnce(killDaemon = killDaemon)
        val completed = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { job.join() } != null
        return if (completed) {
            Result.success(Unit)
        } else {
            Log.w(ConnectionSlot.TAG, "[$nodeKey] $action timed out after ${CLEANUP_TIMEOUT_MS}ms")
            Result.failure(IllegalStateException("$action 超时（${CLEANUP_TIMEOUT_MS}ms），连接资源可能未完全释放"))
        }
    }

    /**
     * 销毁整个槽：在串行域上完成清理后关闭槽级 scope 与调度器。
     *
     * 不阻塞调用方线程（原实现用 `runBlocking` 等待，从主线程调用即 ANR）；
     * 且仅在清理真正结束后才关闭 scope，避免取消掉仍在执行的清理任务。
     */
    fun destroyService() {
        res.isBound = false
        val job = launchCleanupOnce(killDaemon = true)
        job.invokeOnCompletion {
            scope.cancel()
            slot.slotDispatcher.close()
        }
    }
}
