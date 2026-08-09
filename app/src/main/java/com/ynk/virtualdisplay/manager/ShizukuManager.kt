package com.ynk.virtualdisplay.manager

import android.content.pm.PackageManager
import android.util.Log
import com.ynk.virtualdisplay.data.model.ShizukuState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku

/**
 * Shizuku 管理器：统一封装 Shizuku 的可用性检测、权限申请与状态监听。
 *
 * 职责：
 * - 检测 Shizuku 服务是否运行（[pingBinder]）
 * - 检测/申请 Shizuku 授权（[checkSelfPermission] / [requestPermission]）
 * - 监听 Shizuku binder 的延迟绑定（[addBinderReceivedListener]）
 * - 通过 [shizukuState] 暴露统一的 Shizuku 状态流供 UI 订阅
 *
 * 该管理器属于 coreModule，在 Application.onCreate 阶段即可使用，
 * 不依赖 Daemon 进程或任何需要存储权限的组件。
 */
class ShizukuManager {

    companion object {
        private const val TAG = "ShizukuManager"
        const val REQUEST_CODE = 20260
    }

    private val _shizukuState = MutableStateFlow<ShizukuState>(ShizukuState.Checking)
    val shizukuState: StateFlow<ShizukuState> = _shizukuState.asStateFlow()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permission result: granted=$granted")
        _shizukuState.update {
            if (granted) ShizukuState.Ready else ShizukuState.PermissionDenied
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Binder received, re-checking state")
        refreshState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Binder dead")
        _shizukuState.value = ShizukuState.NotRunning
    }

    init {
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    /**
     * 刷新 Shizuku 状态：检测 binder 是否存活、权限是否已授予。
     * 应在 Activity onCreate / 用户点击重试时调用。
     */
    fun refreshState() {
        if (!isBinderAlive()) {
            _shizukuState.value = ShizukuState.NotRunning
            return
        }

        if (!isPermissionGranted()) {
            _shizukuState.value = ShizukuState.PermissionDenied
            return
        }

        _shizukuState.value = ShizukuState.Ready
    }

    /**
     * 申请 Shizuku 权限。仅在 binder 存活但未授权时有效。
     */
    fun requestPermission() {
        try {
            if (isBinderAlive() && !isPermissionGranted()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    /**
     * 判断 Shizuku binder 是否存活（服务是否运行）。
     */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        Log.d(TAG, "pingBinder failed (binder not alive?)", e)
        false
    }

    /**
     * 判断 Shizuku 权限是否已授予。
     */
    fun isPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: IllegalStateException) {
        // Shizuku not attached yet
        false
    }

    /**
     * 判断 Shizuku 是否完全可用（binder 存活且已授权）。
     */
    fun isAvailable(): Boolean = isBinderAlive() && isPermissionGranted()

    /**
     * 释放资源：在 Application / Activity 销毁时调用。
     * 注意：binder/permission listener 是全局静态注册的，
     * 移除时需确保不会影响其他订阅者。
     */
    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}
