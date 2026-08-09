package com.ynk.virtualdisplay.manager

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 运行时权限管理器：统一检查/申请 Android 运行时权限。
 *
 * 当前 VirtualDisplay 仅依赖 Shizuku（由 [ShizukuManager] 管理），
 * 但预留了存储权限、悬浮窗权限等检查入口，以便后续扩展。
 *
 * 属于 coreModule，不依赖任何 appModule 组件。
 */
class PermissionManager(private val context: Context) {

    /**
     * 检查指定权限是否已授予。
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查悬浮窗（SYSTEM_ALERT_WINDOW）权限。
     * Android 6.0+ 需要通过 Settings.canDrawOverlays 判断。
     */
    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 获取悬浮窗权限设置页面的 Intent。
     */
    fun getOverlaySettingsIntent(): android.content.Intent {
        return android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 检查所有必需权限是否已授予。
     * 当前仅检查 Shizuku（由调用方通过 ShizukuManager 判断），
     * 后续可在此添加更多权限检查。
     */
    fun checkAllRequiredPermissions(): Boolean {
        // 当前无额外的运行时权限需求
        // Shizuku 权限由 ShizukuManager 单独管理
        return true
    }
}
