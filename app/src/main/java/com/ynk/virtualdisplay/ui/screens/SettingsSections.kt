package com.ynk.virtualdisplay.ui.screens

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.BuildConfig
import com.ynk.virtualdisplay.data.PrivilegeMode
import com.ynk.virtualdisplay.data.local.DaemonConnectionPrefs
import com.ynk.virtualdisplay.data.local.DisplayFlagPrefs
import com.ynk.virtualdisplay.data.local.FeatureTogglePrefs
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.ui.main.MainIntent
import com.ynk.virtualdisplay.ui.main.MainUiState
import com.ynk.virtualdisplay.ui.main.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
private fun SettingsCard(
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .clickable { onToggle() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (expanded) "▲" else "▼",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/** 虚拟显示器 Flags 设置区块。 */
@Composable
internal fun FlagsSettingsSection(
    context: Context,
    scope: CoroutineScope,
    flagStates: SnapshotStateMap<String, Boolean>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    SettingsCard(onToggle = { onExpandedChange(!expanded) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "虚拟显示器 Flags 设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        ALL_DISPLAY_FLAGS.forEach { flag ->
                            flagStates[flag.key] = flag.isDefaultEnabled
                        }
                        scope.launch {
                            DisplayFlagPrefs.resetAllFlagsToDefault(context)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("恢复默认", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (expanded) "▲" else "▼",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))

                val supportedFlags = remember {
                    ALL_DISPLAY_FLAGS.filter { it.minSdk <= Build.VERSION.SDK_INT }
                }

                supportedFlags.forEachIndexed { index, flag ->
                    SettingSwitchRow(
                        title = flag.name,
                        description = flag.description + " (最低 SDK: API ${flag.minSdk})",
                        checked = flagStates[flag.key] ?: flag.isDefaultEnabled,
                        onCheckedChange = { isChecked ->
                            flagStates[flag.key] = isChecked
                            scope.launch {
                                DisplayFlagPrefs.setFlag(context, flag.key, isChecked)
                            }
                        }
                    )
                    if (index < supportedFlags.lastIndex) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                    }
                }
            }
        }
    }
}

/** 操作与手势设置区块。 */
@Composable
internal fun GesturesSettingsSection(
    context: Context,
    scope: CoroutineScope,
    captureBack: Boolean,
    showPerformanceStats: Boolean,
    ultraLowLatency: Boolean,
    moveTasksOnDestroy: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    SettingsCard(onToggle = { onExpandedChange(!expanded) }) {
        SectionHeader("操作与手势设置", expanded)

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "捕获返回事件",
                    description = "操作虚拟显示器时，拦截真实设备的返回手势或按键并传递到虚拟屏，防止退出投屏界面",
                    checked = captureBack,
                    onCheckedChange = { isChecked ->
                        scope.launch { FeatureTogglePrefs.setCaptureBack(context, isChecked) }
                    }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "质量诊断诊断信息",
                    description = "在投屏界面右上角显示分辨率、帧率、码率、延迟和抖动等性能诊断数据",
                    checked = showPerformanceStats,
                    onCheckedChange = { isChecked ->
                        scope.launch { FeatureTogglePrefs.setShowPerformanceStats(context, isChecked) }
                    }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "超低延迟模式",
                    description = "开启后视频流解码完成后立即渲染，降低渲染排队延迟（同机投屏操控建议开启）",
                    checked = ultraLowLatency,
                    onCheckedChange = { isChecked ->
                        scope.launch { FeatureTogglePrefs.setUltraLowLatency(context, isChecked) }
                    }
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                SettingSwitchRow(
                    title = "销毁显示器时移回主屏幕",
                    description = "删除虚拟显示器时，将其上运行的应用移回主屏幕(需要应用本身支持)；关闭则完全交给系统处理（应用通常会被直接关闭）",
                    checked = moveTasksOnDestroy,
                    onCheckedChange = { isChecked ->
                        scope.launch { FeatureTogglePrefs.setMoveTasksOnDestroy(context, isChecked) }
                    }
                )
            }
        }
    }
}

/** 服务端启动设置区块：特权模式 + 监听网络配置 + 服务控制。 */
@Composable
internal fun ServerStartSettingsSection(
    viewModel: MainViewModel,
    uiState: MainUiState,
    context: Context,
    scope: CoroutineScope,
    serverHost: String,
    portText: String,
    onPortTextChange: (String) -> Unit,
    passwordText: String,
    onPasswordTextChange: (String) -> Unit,
    bindAddresses: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    var hostMenuExpanded by remember { mutableStateOf(false) }

    SettingsCard(onToggle = { onExpandedChange(!expanded) }) {
        SectionHeader("服务端启动设置", expanded)

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                Text(
                    text = "特权启动模式",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val modes = listOf(
                        Triple(PrivilegeMode.SHIZUKU, "Shizuku", "推荐"),
                        Triple(PrivilegeMode.ROOT, "Root", "su拉起"),
                        Triple(PrivilegeMode.NONE, "无权限", "仅连接")
                    )
                    modes.forEach { (mode, name, desc) ->
                        val selected = uiState.privilegeMode == mode
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { viewModel.handleIntent(MainIntent.UpdatePrivilegeMode(mode)) }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.handleIntent(MainIntent.UpdatePrivilegeMode(mode)) }
                            )
                            Text(text = name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                text = desc,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                when (uiState.privilegeMode) {
                    PrivilegeMode.SHIZUKU -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                val statusText = when (uiState.shizukuState) {
                                    is ShizukuState.Ready -> "服务状态: 已就绪"
                                    is ShizukuState.PermissionDenied -> "服务状态: 未授权"
                                    is ShizukuState.NotRunning -> "服务状态: 未运行"
                                    else -> "服务状态: 检测中..."
                                }
                                Text(text = statusText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            if (uiState.shizukuState !is ShizukuState.Ready) {
                                Button(onClick = { viewModel.handleIntent(MainIntent.RequestShizukuPermission) }) {
                                    Text("启动/授权 Shizuku", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    PrivilegeMode.ROOT -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                val statusText = if (uiState.rootChecking) "Root状态: 检测中..." else {
                                    if (uiState.rootAvailable) "Root状态: 已获取" else "Root状态: 未授权或未Root"
                                }
                                Text(text = statusText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Button(onClick = { viewModel.handleIntent(MainIntent.CheckRootPermission) }) {
                                Text("测试 Root 权限", fontSize = 12.sp)
                            }
                        }
                    }
                    PrivilegeMode.NONE -> {
                        Text(
                            text = "提示: 当前为无特权模式，不支持在本地自动拉起守护服务，请在“网络与安全设置”中配置外部设备的 IP 和端口。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                Text(
                    text = "网络配置与自启",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    val hostInteractionSource = remember { MutableInteractionSource() }
                    LaunchedEffect(hostInteractionSource) {
                        hostInteractionSource.interactions.collect { interaction ->
                            if (interaction is PressInteraction.Release) {
                                hostMenuExpanded = true
                            }
                        }
                    }
                    OutlinedTextField(
                        value = serverHost,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Daemon 监听地址") },
                        trailingIcon = {
                            Text(
                                text = if (hostMenuExpanded) "▲" else "▼",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        interactionSource = hostInteractionSource,
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = hostMenuExpanded,
                        onDismissRequest = { hostMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        bindAddresses.forEach { (label, value) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        fontWeight = if (value == serverHost) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    hostMenuExpanded = false
                                    scope.launch { DaemonConnectionPrefs.setServerHost(context, value) }
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = portText,
                    onValueChange = { newValue ->
                        val filtered = newValue.filter { it.isDigit() }
                        if (filtered.length <= 5) {
                            onPortTextChange(filtered)
                            val portNum = filtered.toIntOrNull() ?: 27183
                            if (portNum in 1024..65535) {
                                scope.launch { DaemonConnectionPrefs.setServerPort(context, portNum) }
                            }
                        }
                    },
                    label = { Text("监听端口 (1024-65535)") },
                    placeholder = { Text("27183") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = passwordText,
                    onValueChange = { newValue ->
                        onPasswordTextChange(newValue)
                        scope.launch { DaemonConnectionPrefs.setServerPassword(context, newValue) }
                    },
                    label = { Text("连接密码 (空表示不设密码)") },
                    placeholder = { Text("未设置密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "提示：拉起服务端仅通过上方「启动」按钮执行；连接参数在建立连接时读取一次，连接存续期间修改监听配置不影响当前连接。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "服务状态",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val connectionStatus = uiState.connectionStatus
                    val daemonPid = uiState.daemonPid
                    val (statusText, statusColor) = when {
                        daemonPid > 0 -> "运行中 (PID: $daemonPid)" to Color(0xFF26A69A)
                        connectionStatus == ConnectionStatus.CONNECTED -> "已连接" to Color(0xFF26A69A)
                        connectionStatus == ConnectionStatus.BINDING -> "启动中..." to Color(0xFFFFB74D)
                        connectionStatus == ConnectionStatus.RECONNECTING -> "重连中..." to Color(0xFFFFB74D)
                        connectionStatus == ConnectionStatus.ERROR -> "启动失败" to Color(0xFFEF5350)
                        else -> "已停止" to Color(0xFFEF5350)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.handleIntent(MainIntent.StartServer) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("启动", color = Color.White)
                    }
                    Button(
                        onClick = { viewModel.handleIntent(MainIntent.StopServer) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("停止", color = Color.White)
                    }
                    Button(
                        onClick = { viewModel.handleIntent(MainIntent.RestartService) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        enabled = !uiState.isRestartCooldown
                    ) {
                        Text(if (uiState.isRestartCooldown) "冷却中" else "重启", color = Color.White)
                    }
                }
            }
        }
    }
}

/** 系统信息区块（只读）。 */
@Composable
internal fun SystemInfoSection() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "系统信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            val dirtyMark = if (BuildConfig.GIT_DIRTY) " ●dirty" else ""
            SystemInfoRow("应用版本", "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            SystemInfoRow("Git Commit", "${BuildConfig.GIT_HASH}$dirtyMark")
            SystemInfoRow("构建时间", BuildConfig.BUILD_TIME)
            SystemInfoRow("安卓 SDK 版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                lineHeight = 14.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SystemInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        )
    }
}
