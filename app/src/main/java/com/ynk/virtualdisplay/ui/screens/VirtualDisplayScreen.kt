package com.ynk.virtualdisplay.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.ui.display.DisplayActivity
import com.ynk.virtualdisplay.ui.main.MainIntent
import com.ynk.virtualdisplay.ui.main.MainViewModel
import com.ynk.virtualdisplay.ui.components.DisplayItem
import com.ynk.virtualdisplay.ui.components.AppSelectionDialog

private data class DisplayPreset(val name: String, val width: Int, val height: Int, val dpi: Int)

@Composable
fun VirtualDisplayScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val displays = uiState.displays
    val orphanDisplayIds = uiState.orphanDisplayIds
    val status = uiState.statusMessage
    
    var showAppSelectionDialogForDisplayId by remember { mutableStateOf<Int?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val isFabExpanded by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 100
        }
    }

    val presets = remember(context) {
        val dm = context.resources.displayMetrics
        listOf(
            DisplayPreset("本机", dm.widthPixels, dm.heightPixels, dm.densityDpi),
            DisplayPreset("手机 720p", 720, 1280, 320),
            DisplayPreset("平板 2.5K", 2560, 1600, 320),
            DisplayPreset("电视 1080p", 1920, 1080, 320)
        )
    }

    LaunchedEffect(Unit) {
        viewModel.handleIntent(MainIntent.RefreshDisplays)
    }

    // App Selection Dialog Handler
    showAppSelectionDialogForDisplayId?.let { displayId ->
        AppSelectionDialog(
            loadApps = { viewModel.listApps() },
            onDismiss = { showAppSelectionDialogForDisplayId = null },
            onAppSelected = { appInfo ->
                viewModel.launchSelectedApp(appInfo.packageName, displayId)
                showAppSelectionDialogForDisplayId = null
            }
        )
    }

    // Gradient Background
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 1. 顶部操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "虚拟显示控制台",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. 服务连接状态条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val connectionStatus = uiState.connectionStatus
                val (statusText, statusColor) = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> {
                        val suffix = if (uiState.daemonPid > 0) " (PID: ${uiState.daemonPid})" else ""
                        "已连接$suffix" to Color(0xFF26A69A)
                    }
                    ConnectionStatus.BINDING -> "正在绑定..." to Color(0xFFFFB74D)
                    ConnectionStatus.RECONNECTING -> "正在重连..." to Color(0xFFFFB74D)
                    ConnectionStatus.DISCONNECTED -> (uiState.connectionError ?: "连接断开") to Color(0xFFEF5350)
                    ConnectionStatus.ERROR -> "错误" to Color(0xFFEF5350)
                    ConnectionStatus.IDLE -> "空闲" to Color(0xFF78909C)
                }

                var dropdownExpanded by remember { mutableStateOf(false) }
                var showAddDialog by remember { mutableStateOf(false) }

                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { dropdownExpanded = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${uiState.currentServerNode.name}: $statusText ▼",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        uiState.serverNodes.forEach { node ->
                            val isLocalNode = node.host == "127.0.0.1" || node.host == "localhost" || node.name == "本机"
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${node.name} (${node.host}:${node.port})", modifier = Modifier.weight(1f))
                                        if (node.host == uiState.currentServerNode.host && node.port == uiState.currentServerNode.port) {
                                            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        if (!isLocalNode) {
                                            IconButton(
                                                onClick = {
                                                    dropdownExpanded = false
                                                    viewModel.handleIntent(MainIntent.RemoveServerNode(node))
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "删除设备",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    dropdownExpanded = false
                                    viewModel.handleIntent(MainIntent.SelectServerNode(node))
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ 添加外部设备...") },
                            onClick = {
                                dropdownExpanded = false
                                showAddDialog = true
                            }
                        )
                    }
                }

                if (showAddDialog) {
                    var addName by remember { mutableStateOf("") }
                    var addHost by remember { mutableStateOf("") }
                    var addPort by remember { mutableStateOf("27183") }
                    var addPassword by remember { mutableStateOf("") }

                    AlertDialog(
                        onDismissRequest = { showAddDialog = false },
                        title = { Text("添加外部设备") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = addName,
                                    onValueChange = { addName = it },
                                    label = { Text("设备名称") },
                                    placeholder = { Text("例: 电视") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = addHost,
                                    onValueChange = { addHost = it },
                                    label = { Text("设备 IP") },
                                    placeholder = { Text("192.168.1.100") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = addPort,
                                    onValueChange = { addPort = it.filter { c -> c.isDigit() } },
                                    label = { Text("设备端口") },
                                    placeholder = { Text("27183") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = addPassword,
                                    onValueChange = { addPassword = it },
                                    label = { Text("连接密码 (可选)") },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val portNum = addPort.toIntOrNull() ?: 27183
                                    if (addName.isNotEmpty() && addHost.isNotEmpty()) {
                                        val newNode = com.ynk.virtualdisplay.data.ServerNode(addName, addHost, portNum, addPassword)
                                        viewModel.handleIntent(MainIntent.AddServerNode(newNode))
                                        viewModel.handleIntent(MainIntent.SelectServerNode(newNode))
                                        showAddDialog = false
                                    }
                                }
                            ) {
                                Text("添加并切换")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddDialog = false }) {
                                Text("取消")
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                val isError = status.startsWith("Error:")
                Text(
                    text = "提示: $status",
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isError)
                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = if (isError) Modifier.clickable { showErrorDialog = true } else Modifier
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. 显示器列表头部
            Text(
                text = "屏幕列表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 5. 显示器网格
            if (displays.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                        .border(1.dp, Color.White.copy(alpha = 0.03f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无活跃的虚拟显示器",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 72.dp)
                ) {
                    items(displays, key = { it.id }) { displayInfo ->
                        DisplayItem(
                            displayInfo = displayInfo,
                            isOrphan = displayInfo.id in orphanDisplayIds,
                            onPlay = {
                                val intent = DisplayActivity.createIntent(
                                    context, 
                                    displayInfo.id, 
                                    uiState.currentServerNode.uniqueKey()
                                )
                                context.startActivity(intent)
                            },
                            onDelete = { viewModel.handleIntent(MainIntent.ReleaseDisplay(displayInfo.id)) },
                            onLaunchApp = { showAppSelectionDialogForDisplayId = displayInfo.id },
                            onMirror = {
                                viewModel.handleIntent(
                                    MainIntent.CreateDisplay(
                                        width = displayInfo.width.toString(),
                                        height = displayInfo.height.toString(),
                                        dpi = displayInfo.dpi.toString(),
                                        mirrorDisplayId = displayInfo.id
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        // 错误详情弹窗
        if (showErrorDialog) {
            AlertDialog(
                onDismissRequest = { showErrorDialog = false },
                title = { Text("\u521b\u5efa\u5931\u8d25", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("\u9519\u8bef\u65e5\u5fd7", status))
                        Toast.makeText(context, "\u5df2\u590d\u5236", Toast.LENGTH_SHORT).show()
                        showErrorDialog = false
                    }) {
                        Text("\u590d\u5236")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showErrorDialog = false }) {
                        Text("\u5173\u95ed")
                    }
                }
            )
        }

        // 6. 新建屏幕弹窗 (Creation Dialog)
        if (showCreateDialog) {

            Dialog(onDismissRequest = { showCreateDialog = false }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "新建虚拟显示器",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 输入行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = uiState.inputWidth,
                                onValueChange = { viewModel.handleIntent(MainIntent.UpdateInputs(width = it)) },
                                label = { Text("宽度") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )
                            OutlinedTextField(
                                value = uiState.inputHeight,
                                onValueChange = { viewModel.handleIntent(MainIntent.UpdateInputs(height = it)) },
                                label = { Text("高度") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )
                            OutlinedTextField(
                                value = uiState.inputDpi,
                                onValueChange = { viewModel.handleIntent(MainIntent.UpdateInputs(dpi = it)) },
                                label = { Text("DPI") },
                                modifier = Modifier.weight(0.8f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 预设选择器
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            presets.forEach { preset ->
                                val isSelected = uiState.inputWidth == preset.width.toString() &&
                                                 uiState.inputHeight == preset.height.toString() &&
                                                 uiState.inputDpi == preset.dpi.toString()
                                
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            viewModel.handleIntent(MainIntent.UpdateInputs(
                                                width = preset.width.toString(),
                                                height = preset.height.toString(),
                                                dpi = preset.dpi.toString()
                                            ))
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = preset.name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showCreateDialog = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("取消", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    viewModel.handleIntent(MainIntent.CreateDisplay(
                                        uiState.inputWidth, uiState.inputHeight, uiState.inputDpi
                                    ))
                                    showCreateDialog = false
                                },
                                modifier = Modifier.weight(1.5f),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !uiState.isLoading
                            ) {
                                if (uiState.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text("创建并启动", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. 中间偏下悬浮 + 号按钮
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = if (isFabExpanded) 16.dp else 0.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建显示器"
                )
                if (isFabExpanded) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "新建显示器",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}


