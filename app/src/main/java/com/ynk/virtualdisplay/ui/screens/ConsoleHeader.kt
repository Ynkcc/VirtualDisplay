package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.ui.components.ServerNodeDialog
import com.ynk.virtualdisplay.ui.main.MainUiState

/** 控制台顶部操作栏：标题 + 连接开关 + 刷新。 */
@Composable
fun ConsoleTopBar(
    connectionStatus: ConnectionStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit
) {
    val isBinding = connectionStatus == ConnectionStatus.BINDING
    val isConnected = connectionStatus == ConnectionStatus.CONNECTED ||
        connectionStatus == ConnectionStatus.RECONNECTING

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
                onClick = { if (isConnected) onDisconnect() else onConnect() },
                enabled = !isBinding,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isConnected) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = when {
                        isBinding -> "连接中…"
                        isConnected -> "断开"
                        else -> "连接"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp)
                    )
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
}

/**
 * 服务连接状态条：节点下拉选择 + 新增 / 编辑设备入口 + 状态提示。
 */
@Composable
fun ConnectionStatusBar(
    uiState: MainUiState,
    status: String,
    onSelectNode: (ServerNode) -> Unit,
    onAddNode: (ServerNode) -> Unit,
    onEditNode: (ServerNode, ServerNode) -> Unit,
    onRemoveNode: (ServerNode) -> Unit,
    onErrorClick: () -> Unit
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
    var editingNode by remember { mutableStateOf<ServerNode?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${node.name} (${node.host}:${node.port})", modifier = Modifier.weight(1f))
                                if (node.host == uiState.currentServerNode.host &&
                                    node.port == uiState.currentServerNode.port
                                ) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                IconButton(
                                    onClick = {
                                        dropdownExpanded = false
                                        editingNode = node
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "编辑设备",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        dropdownExpanded = false
                                        onRemoveNode(node)
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
                        },
                        onClick = {
                            dropdownExpanded = false
                            onSelectNode(node)
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
            ServerNodeDialog(
                title = "添加外部设备",
                initial = null,
                confirmText = "添加并切换",
                onDismiss = { showAddDialog = false },
                onConfirm = {
                    onAddNode(it)
                    showAddDialog = false
                }
            )
        }

        editingNode?.let { node ->
            ServerNodeDialog(
                title = "编辑外部设备",
                initial = node,
                confirmText = "保存",
                onDismiss = { editingNode = null },
                onConfirm = {
                    onEditNode(node, it)
                    editingNode = null
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
            modifier = if (isError) Modifier.clickable { onErrorClick() } else Modifier
        )
    }
}
