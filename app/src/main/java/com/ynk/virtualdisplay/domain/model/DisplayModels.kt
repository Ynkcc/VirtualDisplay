package com.ynk.virtualdisplay.domain.model

/**
 * 一个虚拟显示器的展示模型，由 [com.ynk.virtualdisplay.domain.DisplayInteractor] 组装。
 */
data class DisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val mirrorDisplayId: Int = -1,
    val isOwned: Boolean = false,
    val ownerPackage: String? = null,
    val ownerUid: Int = 0
)

/**
 * 远端设备上已安装应用的领域模型（由协议层 `DeviceMessage.AppEntry` 映射而来）。
 */
data class RemoteAppInfo(
    val packageName: String,
    val name: String,
    val isSystem: Boolean
)

/**
 * 远端设备上活跃显示器的领域模型（由协议层 `DeviceMessage.DisplayInfoEntry` 映射而来）。
 */
data class ActiveDisplayInfo(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val rotation: Int,
    val mirrorDisplayId: Int = -1,
    val isOwned: Boolean = false,
    val ownerUid: Int = 0,
    val ownerPackage: String? = null
)
