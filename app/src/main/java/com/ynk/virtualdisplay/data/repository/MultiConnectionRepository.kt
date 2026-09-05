package com.ynk.virtualdisplay.data.repository

import android.util.Log
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.protocol.DeviceMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/**
 * 多连接路由层：管理所有 [ConnectionSlot]，并实现 [IDisplayRepository]（路由到活跃槽）。
 *
 * ## 多连接管理 API
 * - [connectNode] / [disconnectNode]：建立/断开指定节点的连接
 * - [setActiveNode]：切换当前操作的活跃节点
 * - [getSlot]：按节点 key 获取特定槽（供 [DisplayActivity] 路由使用）
 * - [allSlots]：获取全部槽状态（供 ViewModel 聚合展示）
 *
 * ## IDisplayRepository 兼容
 * 所有显示器操作路由到 [activeSlot]，保持对 ViewModel/Interactor 的透明性。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiConnectionRepository(
    private val slotFactory: ConnectionSlotFactory,
    private val scope: CoroutineScope
) : IDisplayRepository {

    companion object {
        private const val TAG = "MultiConnectionRepo"
    }

    /** 唯一的槽状态源：nodeKey → ConnectionSlot，所有增删改都必须经 [MutableStateFlow.update] 修改。 */
    private val _slots = MutableStateFlow<Map<String, ConnectionSlot>>(emptyMap())

    private val _activeNodeKey = MutableStateFlow<String?>(null)

    /** 由 [activeNodeKey] 与 [slots] 共同推导，两者任一变化都会重新计算活跃槽。 */
    private val activeSlotFlow: StateFlow<ConnectionSlot?> = combine(_activeNodeKey, _slots) { key, slotsMap ->
        key?.let { slotsMap[it] }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val activeSlot: ConnectionSlot?
        get() = activeSlotFlow.value

    // ====================== 多连接管理 API ======================

    /**
     * 建立到指定节点的连接：若尚无对应槽则创建并绑定，并设为活跃节点（若当前无活跃节点）。
     * @param node 目标服务器节点
     */
    override fun connectNode(node: ServerNode) {
        val key = node.uniqueKey()
        val existing = _slots.value[key]
        val slot = existing ?: slotFactory.create(node)
        if (existing == null) {
            _slots.update { it + (key to slot) }
        }
        slot.bindService()
        if (_activeNodeKey.value == null) _activeNodeKey.value = key
        Log.i(TAG, "connectNode: ${node.name} (${node.host}:${node.port}), activeNode=${_activeNodeKey.value}")
    }

    /**
     * 断开指定节点的连接并移除其槽。
     * @param node 目标服务器节点
     * @param killDaemon true 时同时销毁服务（停止 daemon），false 仅解绑
     */
    override fun disconnectNode(node: ServerNode, killDaemon: Boolean) {
        val key = node.uniqueKey()
        val slot = _slots.value[key] ?: return
        _slots.update { it - key }
        if (killDaemon) {
            slot.destroyService()
        } else {
            slot.unbindService()
        }
        if (_activeNodeKey.value == key) {
            _activeNodeKey.value = _slots.value.keys.firstOrNull()
        }
        Log.i(TAG, "disconnectNode: ${node.name}, new activeNode=${_activeNodeKey.value}")
    }

    /** 将指定节点设为活跃节点；若槽尚不存在则先建立连接。 */
    override fun setActiveNode(node: ServerNode) {
        val key = node.uniqueKey()
        if (!_slots.value.containsKey(key)) {
            connectNode(node)
        }
        _activeNodeKey.value = key
        Log.i(TAG, "setActiveNode: ${node.name}")
    }

    override fun getSlot(nodeKey: String): IDisplayRepository? = _slots.value[nodeKey]

    override fun allSlots(): Collection<IDisplayRepository> = _slots.value.values

    // ====================== IDisplayRepository 路由 ======================

    override val connectionStatus: StateFlow<ConnectionStatus> = activeSlotFlow
        .flatMapLatest { it?.connectionStatus ?: MutableStateFlow(ConnectionStatus.IDLE) }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionStatus.IDLE)

    override val connectionError: StateFlow<String?> = activeSlotFlow
        .flatMapLatest { it?.connectionError ?: MutableStateFlow<String?>(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val managedDisplayIds: StateFlow<Set<Int>> = activeSlotFlow
        .flatMapLatest { it?.managedDisplayIds ?: MutableStateFlow(emptySet()) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val displayOwners: StateFlow<Map<Int, DisplayOwner>> = activeSlotFlow
        .flatMapLatest { it?.displayOwners ?: MutableStateFlow(emptyMap()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override val daemonPid: StateFlow<Int> = activeSlotFlow
        .flatMapLatest { it?.daemonPid ?: MutableStateFlow(-1) }
        .stateIn(scope, SharingStarted.Eagerly, -1)

    override fun bindService() { activeSlot?.bindService() }
    override fun unbindService() { activeSlot?.unbindService() }
    override suspend fun startDaemon(): Result<Unit> = activeSlot?.startDaemon() ?: Result.failure(noActiveSlotError())
    override suspend fun stopDaemon(): Result<Unit> = activeSlot?.stopDaemon() ?: Result.failure(noActiveSlotError())
    override suspend fun connect(): Result<Unit> = activeSlot?.connect() ?: Result.failure(noActiveSlotError())
    override suspend fun disconnect(): Result<Unit> = activeSlot?.disconnect() ?: Result.failure(noActiveSlotError())
    override fun destroyService() {
        _slots.value.values.forEach { it.destroyService() }
        _slots.update { emptyMap() }
    }
    override fun refreshDisplays() { activeSlot?.refreshDisplays() }

    override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int, mirrorDisplayId: Int): Result<Int> =
        activeSlot?.createDisplay(name, width, height, dpi, flags, mirrorDisplayId) ?: Result.failure(noActiveSlotError())

    override suspend fun releaseDisplay(displayId: Int, moveTasksToDefaultDisplay: Boolean): Result<Unit> =
        activeSlot?.releaseDisplay(displayId, moveTasksToDefaultDisplay) ?: Result.failure(noActiveSlotError())

    override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> =
        activeSlot?.setDisplaySurface(displayId, surface) ?: Result.failure(noActiveSlotError())

    override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> =
        activeSlot?.resizeDisplay(displayId, width, height, dpi) ?: Result.failure(noActiveSlotError())

    override suspend fun stopStreaming() {
        activeSlot?.stopStreaming()
    }

    override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> =
        activeSlot?.launchApp(packageName, displayId) ?: Result.failure(noActiveSlotError())

    override suspend fun launchHome(displayId: Int): Result<Int> =
        activeSlot?.launchHome(displayId) ?: Result.failure(noActiveSlotError())

    override suspend fun listApps(forceRefresh: Boolean): Result<List<DeviceMessage.AppEntry>> =
        activeSlot?.listApps(forceRefresh) ?: Result.failure(noActiveSlotError())

    override suspend fun injectInput(event: InputEvent): Result<Boolean> =
        activeSlot?.injectInput(event) ?: Result.failure(noActiveSlotError())

    override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> =
        activeSlot?.injectInputWithDisplayId(event, displayId) ?: Result.failure(noActiveSlotError())

    override suspend fun getActiveDisplayIds(): Result<IntArray> =
        activeSlot?.getActiveDisplayIds() ?: Result.failure(noActiveSlotError())

    override suspend fun getActiveDisplayInfos(): Result<List<DeviceMessage.DisplayInfoEntry>> =
        activeSlot?.getActiveDisplayInfos() ?: Result.failure(noActiveSlotError())

    override fun setVideoConfigCallback(callback: ((width: Int, height: Int) -> Unit)?) {
        activeSlot?.setVideoConfigCallback(callback)
    }

    override fun setPerformanceStatsCallback(callback: ((String) -> Unit)?) {
        activeSlot?.setPerformanceStatsCallback(callback)
    }

    private fun noActiveSlotError() = IllegalStateException("No active connection slot")
}
