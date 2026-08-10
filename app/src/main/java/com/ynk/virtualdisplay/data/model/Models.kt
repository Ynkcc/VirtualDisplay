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

// 描述存疑，以及可能不全，待完善
val ALL_DISPLAY_FLAGS = listOf(
    DisplayFlag("flag_public", VIRTUAL_DISPLAY_FLAG_PUBLIC, "Public Display", "允许其他应用在此显示器上渲染内容。如果不设置，它就是私有显示器，仅限创建者应用使用", 19, true),
    DisplayFlag("flag_presentation", VIRTUAL_DISPLAY_FLAG_PRESENTATION, "Presentation Display", "将显示器标记为演示屏幕，适用于外接或第二屏幕体验。系统会优化它以适合运行 Presentation 窗口", 19, true),
    DisplayFlag("flag_secure", VIRTUAL_DISPLAY_FLAG_SECURE, "Secure Display", "保护屏幕内容，禁止屏幕截图、录屏，并且不允许在非安全显示设备上进行镜像", 19, false),
    DisplayFlag("flag_own_content_only", VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, "Own Content Only", "此显示器仅显示它上面的内容。当显示器处于空闲状态时，不会自动镜像主屏幕内容", 19, true),
    DisplayFlag("flag_auto_mirror", VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, "Auto Mirror", "当此虚拟显示器没有任何内容渲染时，允许系统自动将主屏幕的内容镜像到本显示器。与 Own Content Only 互斥", 21, false),
    DisplayFlag("flag_can_show_with_insecure_keyguard", VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD, "Show on Insecure Keyguard", "允许在此显示器上显示那些在设备处于非安全锁屏（Keyguard）状态时也允许展示的窗口和应用", 29, false),
    DisplayFlag("flag_supports_touch", VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH, "Supports Touch", "指示此虚拟显示器关联了输入设备，支持并允许分发或注入触摸事件", 26, true),
    DisplayFlag("flag_rotates_with_content", VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT, "Rotates with Content", "此虚拟显示器的朝向/旋转会跟随其渲染内容的方向自动旋转", 26, true),
    DisplayFlag("flag_destroy_content_on_removal", VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL, "Destroy Content on Removal", "当该虚拟显示器被销毁或移除时，强制销毁该显示器上正在运行的所有 Activity，防止残留", 26, true),
    DisplayFlag("flag_system_decorations", VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS, "System Decorations", "在此显示器中渲染系统的状态栏、导航栏、壁纸以及输入法（IME），使其具有完整的系统UI体验", 29, true),
    DisplayFlag("flag_trusted", VIRTUAL_DISPLAY_FLAG_TRUSTED, "Trusted Display", "将此显示器标记为系统可信的（需特权权限）。这是显示系统装饰以及接收敏感输入的必要前提", 33, true),
    DisplayFlag("flag_own_display_group", VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP, "Own Display Group", "使此虚拟显示器归属于一个独立的显示组。系统不会将其与主屏幕的多窗口机制混淆，能保障其具有完全独立的 Activity 栈管理", 33, true),
    DisplayFlag("flag_always_unlocked", VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED, "Always Unlocked", "使此虚拟屏幕始终处于解锁状态，运行在其上的 Activity 不会因为系统锁屏逻辑而被锁定或阻断", 33, true),
    DisplayFlag("flag_touch_feedback_disabled", VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED, "Disable Touch Feedback", "在该屏幕上进行触控操作时，禁用指针轨迹、水波纹等视觉或触觉震动反馈效果", 33, true),
    DisplayFlag("flag_own_focus", VIRTUAL_DISPLAY_FLAG_OWN_FOCUS, "Own Focus", "使此虚拟显示器能够拥有自己的焦点系统，与主屏可以同时处于 Active 状态，并接收独立的键盘或输入焦点", 34, true),
    DisplayFlag("flag_device_display_group", VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP, "Device Display Group", "指示此虚拟屏幕应被关联到特定的伴随设备组，用于区分普通的系统组和特定的硬件投屏组", 34, false)
)

data class SavedDisplay(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val mirrorDisplayId: Int = -1,
    val isOwned: Boolean = false
) {
    fun toJsonObject(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", id)
            put("name", name)
            put("width", width)
            put("height", height)
            put("dpi", dpi)
            put("mirrorDisplayId", mirrorDisplayId)
            put("isOwned", isOwned)
        }
    }

    companion object {
        fun fromJsonObject(json: org.json.JSONObject): SavedDisplay {
            return SavedDisplay(
                id = json.getInt("id"),
                name = json.getString("name"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                dpi = json.getInt("dpi"),
                mirrorDisplayId = json.optInt("mirrorDisplayId", -1),
                isOwned = json.optBoolean("isOwned", false)
            )
        }
    }
}
