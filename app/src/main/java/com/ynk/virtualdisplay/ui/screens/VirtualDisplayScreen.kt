package com.ynk.virtualdisplay.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.ynk.virtualdisplay.ConnectionStatus
import com.ynk.virtualdisplay.DisplayActivity
import com.ynk.virtualdisplay.MainViewModel
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
        viewModel.refreshDisplays(context)
    }

    // App Selection Dialog Handler
    showAppSelectionDialogForDisplayId?.let { displayId ->
        AppSelectionDialog(
            onDismiss = { showAppSelectionDialogForDisplayId = null },
            onAppSelected = { appInfo ->
                viewModel.launchSelectedApp(context, displayId, appInfo.packageName)
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
                    TextButton(
                        onClick = { viewModel.forceRestartService(context) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("重启服务", fontWeight = FontWeight.Bold)
                    }
                    IconButton(
                        onClick = { viewModel.refreshDisplays(context) },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
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
                    ConnectionStatus.CONNECTED -> "特权服务: 已连接" to Color(0xFF26A69A)
                    ConnectionStatus.BINDING -> "特权服务: 正在绑定..." to Color(0xFFFFB74D)
                    ConnectionStatus.DISCONNECTED -> "特权服务: 连接断开" to Color(0xFFEF5350)
                    ConnectionStatus.ERROR -> "特权服务: 错误" to Color(0xFFEF5350)
                    ConnectionStatus.IDLE -> "特权服务: 空闲" to Color(0xFF78909C)
                }

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, RoundedCornerShape(4.dp))
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "提示: $status",
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 配置与创建屏幕卡片
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "新建虚拟显示器",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 输入行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.inputWidth,
                            onValueChange = { viewModel.updateInputs(width = it) },
                            label = { Text("宽度") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = uiState.inputHeight,
                            onValueChange = { viewModel.updateInputs(height = it) },
                            label = { Text("高度") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = uiState.inputDpi,
                            onValueChange = { viewModel.updateInputs(dpi = it) },
                            label = { Text("DPI") },
                            modifier = Modifier.weight(0.8f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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
                                        viewModel.updateInputs(
                                            width = preset.width.toString(),
                                            height = preset.height.toString(),
                                            dpi = preset.dpi.toString()
                                        )
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = preset.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 创建按钮
                    Button(
                        onClick = { viewModel.createVirtualDisplay(context) },
                        modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(16.dp))

            // 4. 显示器列表头部
            Text(
                text = "活跃屏幕列表",
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
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(displays, key = { it.id }) { displayInfo ->
                        DisplayItem(
                            displayInfo = displayInfo,
                            repository = viewModel.repository,
                            isOrphan = displayInfo.id in orphanDisplayIds,
                            onPlay = {
                                val intent = Intent(context, DisplayActivity::class.java).apply {
                                    putExtra("display_id", displayInfo.id)
                                }
                                context.startActivity(intent)
                            },
                            onDelete = { viewModel.releaseDisplay(displayInfo.id, context) },
                            onLaunchApp = { showAppSelectionDialogForDisplayId = displayInfo.id }
                        )
                    }
                }
            }
        }
    }
}
