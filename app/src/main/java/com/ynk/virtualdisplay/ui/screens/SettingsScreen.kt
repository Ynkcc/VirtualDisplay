package com.ynk.virtualdisplay.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.BuildConfig
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.ui.main.MainViewModel
import com.ynk.virtualdisplay.ui.main.MainIntent
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val serverPort by AppSettings.serverPortFlow(context).collectAsState(initial = 27183)
    val serverHost by AppSettings.serverHostFlow(context).collectAsState(initial = "127.0.0.1")
    val serverPassword by AppSettings.serverPasswordFlow(context).collectAsState(initial = "")
    val showPerformanceStats by AppSettings.showPerformanceStatsFlow(context).collectAsState(initial = true)
    val captureBack by AppSettings.captureBackFlow(context).collectAsState(initial = false)
    val ultraLowLatency by AppSettings.ultraLowLatencyFlow(context).collectAsState(initial = false)

    val flagStates = remember {
        mutableStateMapOf<String, Boolean>()
    }

    LaunchedEffect(Unit) {
        val flags = AppSettings.getFlags(context)
        ALL_DISPLAY_FLAGS.forEach { flag ->
            flagStates[flag.key] = flags[flag.key] ?: flag.isDefaultEnabled
        }
    }

    var flagsExpanded by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(serverPort.toString()) }
    var hostText by remember { mutableStateOf(serverHost) }
    var passwordText by remember { mutableStateOf(serverPassword) }

    LaunchedEffect(serverPort) {
        if (portText != serverPort.toString()) {
            portText = serverPort.toString()
        }
    }
    LaunchedEffect(serverHost) {
        if (hostText != serverHost) {
            hostText = serverHost
        }
    }
    LaunchedEffect(serverPassword) {
        if (passwordText != serverPassword) {
            passwordText = serverPassword
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "系统设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                .clickable { flagsExpanded = !flagsExpanded }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                                    AppSettings.resetAllFlagsToDefault(context)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("恢复默认", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (flagsExpanded) "▲" else "▼",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                AnimatedVisibility(visible = flagsExpanded) {
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
                                        AppSettings.setFlag(context, flag.key, isChecked)
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

        var gesturesExpanded by remember { mutableStateOf(false) }
        var serverStartExpanded by remember { mutableStateOf(false) }
        val autoStartServer by AppSettings.autoStartServerFlow(context).collectAsState(initial = true)
        val localIps by produceState<List<String>>(initialValue = emptyList()) {
            value = withContext(Dispatchers.IO) {
                getLocalIpAddresses()
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                .clickable { gesturesExpanded = !gesturesExpanded }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "操作与手势设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (gesturesExpanded) "▲" else "▼",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                AnimatedVisibility(visible = gesturesExpanded) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                        SettingSwitchRow(
                            title = "捕获返回事件",
                            description = "操作虚拟显示器时，拦截真实设备的返回手势或按键并传递到虚拟屏，防止退出投屏界面",
                            checked = captureBack,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    AppSettings.setCaptureBack(context, isChecked)
                                }
                            }
                        )

                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                        SettingSwitchRow(
                            title = "质量诊断诊断信息",
                            description = "在投屏界面右上角显示分辨率、帧率、码率、延迟和抖动等性能诊断数据",
                            checked = showPerformanceStats,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    AppSettings.setShowPerformanceStats(context, isChecked)
                                }
                            }
                        )

                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                        SettingSwitchRow(
                            title = "超低延迟模式",
                            description = "开启后视频流解码完成后立即渲染，降低渲染排队延迟（同机投屏操控建议开启）",
                            checked = ultraLowLatency,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    AppSettings.setUltraLowLatency(context, isChecked)
                                }
                            }
                        )
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                .clickable { serverStartExpanded = !serverStartExpanded }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "服务端启动设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (serverStartExpanded) "▲" else "▼",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                AnimatedVisibility(visible = serverStartExpanded) {
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
                                Triple(com.ynk.virtualdisplay.data.PrivilegeMode.SHIZUKU, "Shizuku", "推荐"),
                                Triple(com.ynk.virtualdisplay.data.PrivilegeMode.ROOT, "Root", "su拉起"),
                                Triple(com.ynk.virtualdisplay.data.PrivilegeMode.NONE, "无权限", "仅连接")
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
                                    Text(text = desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                            }
                        }

                        when (uiState.privilegeMode) {
                            com.ynk.virtualdisplay.data.PrivilegeMode.SHIZUKU -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        val statusText = when (uiState.shizukuState) {
                                            is com.ynk.virtualdisplay.data.model.ShizukuState.Ready -> "服务状态: 已就绪"
                                            is com.ynk.virtualdisplay.data.model.ShizukuState.PermissionDenied -> "服务状态: 未授权"
                                            is com.ynk.virtualdisplay.data.model.ShizukuState.NotRunning -> "服务状态: 未运行"
                                            else -> "服务状态: 检测中..."
                                        }
                                        Text(text = statusText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                    if (uiState.shizukuState !is com.ynk.virtualdisplay.data.model.ShizukuState.Ready) {
                                        Button(onClick = { viewModel.handleIntent(MainIntent.RequestShizukuPermission) }) {
                                            Text("启动/授权 Shizuku", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            com.ynk.virtualdisplay.data.PrivilegeMode.ROOT -> {
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
                            com.ynk.virtualdisplay.data.PrivilegeMode.NONE -> {
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

                        OutlinedTextField(
                            value = hostText,
                            onValueChange = { newValue ->
                                hostText = newValue
                                scope.launch {
                                    AppSettings.setServerHost(context, newValue)
                                }
                            },
                            label = { Text("监听地址") },
                            placeholder = { Text("127.0.0.1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = portText,
                            onValueChange = { newValue ->
                                val filtered = newValue.filter { it.isDigit() }
                                if (filtered.length <= 5) {
                                    portText = filtered
                                    val portNum = filtered.toIntOrNull() ?: 27183
                                    if (portNum in 1024..65535) {
                                        scope.launch {
                                            AppSettings.setServerPort(context, portNum)
                                        }
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
                                passwordText = newValue
                                scope.launch {
                                    AppSettings.setServerPassword(context, newValue)
                                }
                            },
                            label = { Text("连接密码 (空表示不设密码)") },
                            placeholder = { Text("未设置密码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        SettingSwitchRow(
                            title = "自动启动服务端",
                            description = "应用启动或切换至本机节点时，自动在后台拉起特权服务端守护进程",
                            checked = autoStartServer,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    AppSettings.setAutoStartServer(context, isChecked)
                                }
                            }
                        )

                        val addressesStr = remember(serverHost, localIps) {
                            val list = mutableListOf<String>()
                            if (serverHost == "0.0.0.0") {
                                list.addAll(localIps)
                            } else {
                                list.add(serverHost)
                            }
                            if (!list.contains("127.0.0.1")) {
                                list.add("127.0.0.1")
                            }
                            list.joinToString(", ")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "实际监听的网卡地址",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = addressesStr,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }

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
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        )
    }
}

private fun getLocalIpAddresses(): List<String> {
    val ipList = mutableListOf<String>()
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp || networkInterface.isVirtual) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address) {
                    address.hostAddress?.let { ipList.add(it) }
                }
            }
        }
    } catch (e: Exception) {
        // ignore
    }
    return ipList
}
