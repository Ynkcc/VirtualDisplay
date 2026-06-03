package com.ynk.virtualdisplay.data.model

sealed class ShizukuState {
    data object Checking : ShizukuState()
    data object NotRunning : ShizukuState()
    data object PermissionDenied : ShizukuState()
    data object Ready : ShizukuState()
}

data class AppInfo(val name: String, val packageName: String)

data class DisplayFlag(
    val key: String,
    val bitValue: Int,
    val name: String,
    val description: String,
    val minSdk: Int,
    val isDefaultEnabled: Boolean
)

// 虚拟显示器标志常量定义（包含部分系统隐藏/私有标志，迁移自 ShizukuDisplayRepository）
const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0
const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 shl 1
const val VIRTUAL_DISPLAY_FLAG_SECURE = 1 shl 2
const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
const val VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 shl 4
const val VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 shl 5
const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

val ALL_DISPLAY_FLAGS = listOf(
    DisplayFlag("flag_public", VIRTUAL_DISPLAY_FLAG_PUBLIC, "Public Display", "允许其他应用在该显示器渲染内容，若不设置则为私有显示器", 19, true),
    DisplayFlag("flag_presentation", VIRTUAL_DISPLAY_FLAG_PRESENTATION, "Presentation Display", "标识其为一个演示显示器，适用于外接或次级屏幕体验", 19, true),
    DisplayFlag("flag_secure", VIRTUAL_DISPLAY_FLAG_SECURE, "Secure Display", "创建安全显示器，保护内容不被截屏/录屏（防截屏录屏）", 19, false),
    DisplayFlag("flag_own_content_only", VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, "Own Content Only", "限制显示器仅展示该屏幕内启动的应用本身，防止镜像其他显示器", 19, true),
    DisplayFlag("flag_auto_mirror", VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, "Auto Mirror", "当没有其他内容展示时，允许自动镜像主屏幕内容。与 Own Content Only 互斥", 21, false),
    DisplayFlag("flag_can_show_with_insecure_keyguard", VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD, "Show on Insecure Keyguard", "允许在设备处于非安全锁屏状态下在该屏幕展示内容", 29, false),
    DisplayFlag("flag_supports_touch", VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH, "Supports Touch", "支持在该虚拟屏幕进行触摸事件注入", 26, true),
    DisplayFlag("flag_rotates_with_content", VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT, "Rotates with Content", "虚拟显示器的物理朝向跟随其渲染内容的方向自动旋转", 26, true),
    DisplayFlag("flag_destroy_content_on_removal", VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL, "Destroy Content on Removal", "当释放该虚拟显示器时，自动销毁并清理上面的所有 Activity 内容", 26, true),
    DisplayFlag("flag_system_decorations", VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS, "System Decorations", "在此显示器中渲染系统的状态栏、导航栏以及 IME 输入法", 29, true),
    DisplayFlag("flag_trusted", VIRTUAL_DISPLAY_FLAG_TRUSTED, "Trusted Display", "将显示器标记为系统可信的，可防范特权敏感操作被拦截", 33, true),
    DisplayFlag("flag_own_display_group", VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP, "Own Display Group", "将显示器放在独立的显示器分组中，防止被主屏幕的多窗口机制干扰", 33, true),
    DisplayFlag("flag_always_unlocked", VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED, "Always Unlocked", "使此虚拟屏幕始终处于解锁状态，避免在屏幕唤醒时需要解锁输入", 33, true),
    DisplayFlag("flag_touch_feedback_disabled", VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED, "Disable Touch Feedback", "禁用该虚拟屏幕上的触摸触觉/视觉反馈效果（如水波纹）", 33, true),
    DisplayFlag("flag_own_focus", VIRTUAL_DISPLAY_FLAG_OWN_FOCUS, "Own Focus", "使虚拟屏幕支持获取独立的输入焦点 (Android 14+)", 34, true),
    DisplayFlag("flag_device_display_group", VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP, "Device Display Group", "将此屏幕标记为设备级的显示分组，以确保其获取适合该类别的系统服务资源", 34, true)
)
