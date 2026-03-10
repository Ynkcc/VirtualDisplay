package com.example.myapplication

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * 封装 Shizuku 用户服务绑定逻辑的类
 */
class ShizukuServiceBinder(private val context: Context) {
    private val TAG = "ShizukuServiceBinder"

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _service = MutableStateFlow<IDisplayService?>(null)
    val service: StateFlow<IDisplayService?> = _service.asStateFlow()

    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "onServiceConnected: $name")
            _service.value = IDisplayService.Stub.asInterface(binder)
            _connectionStatus.value = ConnectionStatus.CONNECTED
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected: $name")
            _service.value = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    fun bind() {
        if (_connectionStatus.value == ConnectionStatus.CONNECTED || 
            _connectionStatus.value == ConnectionStatus.BINDING) return

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Shizuku permission not granted, skipping bind")
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }

        Log.d(TAG, "Binding Shizuku user service...")
        _connectionStatus.value = ConnectionStatus.BINDING

        userServiceArgs = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, DisplayUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("display_service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        try {
            Shizuku.bindUserService(userServiceArgs!!, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind user service", e)
            _connectionStatus.value = ConnectionStatus.ERROR
        }
    }

    fun unbind() {
        val args = userServiceArgs ?: return
        Log.d(TAG, "Unbinding Shizuku user service...")
        try {
            Shizuku.unbindUserService(args, serviceConnection, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind service", e)
        }
        _service.value = null
        userServiceArgs = null
        _connectionStatus.value = ConnectionStatus.IDLE
    }
}
