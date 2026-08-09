package com.ynk.virtualdisplay.ui.screens

import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.manager.PermissionManager
import com.ynk.virtualdisplay.manager.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 权限页 ViewModel（属于 coreModule）。
 *
 * 职责：
 * - 聚合 [ShizukuManager] + [PermissionManager] 的状态
 * - 暴露统一的 [PermissionsUiState] 供 [ShizukuPermissionScreen] 订阅
 * - 封装 Shizuku 权限申请、状态刷新等操作，使 Screen 纯展示无业务逻辑
 *
 * 该 ViewModel 仅依赖 coreModule 内的组件，在 Shizuku 授权前即可使用，
 * 不触发任何 Daemon 进程或 AppSettings 初始化。
 */
class PermissionsViewModel(
    private val shizukuManager: ShizukuManager,
    private val permissionManager: PermissionManager
) : ViewModel() {

    companion object {
        private const val TAG = "PermissionsViewModel"
    }

    /** 聚合权限状态：ShizukuState + 额外权限检查结果 */
    data class PermissionsUiState(
        val shizukuState: ShizukuState = ShizukuState.Checking,
        val canDrawOverlays: Boolean = true,
        val allReady: Boolean = false
    )

    private val _overlayState = MutableStateFlow(permissionManager.canDrawOverlays())

    /** 组合 Shizuku 状态 + 悬浮窗权限状态的统一 UI State */
    val uiState: StateFlow<PermissionsUiState> = combine(
        shizukuManager.shizukuState,
        _overlayState
    ) { shizuku, overlay ->
        PermissionsUiState(
            shizukuState = shizuku,
            canDrawOverlays = overlay,
            allReady = shizuku is ShizukuState.Ready && overlay
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PermissionsUiState()
    )

    /** 初始化 + 首次刷新 Shizuku 状态（在 Activity onCreate 时调用） */
    fun checkAll() {
        shizukuManager.refreshState()
        refreshOverlayPermission()
    }

    /** 重新检测 Shizuku binder 与权限状态 */
    fun refreshShizuku() {
        shizukuManager.refreshState()
    }

    /** 重新检测悬浮窗权限状态 */
    fun refreshOverlayPermission() {
        _overlayState.value = permissionManager.canDrawOverlays()
    }

    /** 申请 Shizuku 权限（binder 存活但未授权时） */
    fun requestShizukuPermission() {
        try {
            if (shizukuManager.isBinderAlive() && !shizukuManager.isPermissionGranted()) {
                Shizuku.requestPermission(ShizukuManager.REQUEST_CODE)
            } else {
                Log.w(TAG, "Shizuku binder not alive or already granted, skip requestPermission")
                shizukuManager.refreshState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    /** 处理 Shizuku 权限申请结果回调 */
    fun onShizukuPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == ShizukuManager.REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku permission result: granted=$granted")
            shizukuManager.refreshState()
        }
    }

    /** Activity 销毁时释放 ShizukuManager 的监听器 */
    override fun onCleared() {
        shizukuManager.destroy()
        super.onCleared()
    }
}
