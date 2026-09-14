package com.ynk.virtualdisplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.data.ServerNode
import com.ynk.virtualdisplay.util.NetUtils

private const val DEFAULT_PORT = 27183

/**
 * 服务器节点的新增 / 编辑对话框（同一表单，编辑时用 [initial] 预填）。
 *
 * @param title 对话框标题
 * @param initial 编辑目标的现有值；为 null 表示新增
 * @param confirmText 确认按钮文案
 * @param onDismiss 取消 / 关闭
 * @param onConfirm 校验通过后回调，携带构造好的节点
 */
@Composable
fun ServerNodeDialog(
    title: String,
    initial: ServerNode?,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (ServerNode) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var port by remember(initial) { mutableStateOf((initial?.port ?: DEFAULT_PORT).toString()) }
    var password by remember(initial) { mutableStateOf(initial?.password ?: "") }

    val hostValid = NetUtils.isValidHostFormat(host.trim())
    val portValid = (port.toIntOrNull() ?: DEFAULT_PORT) in 1024..65535
    val canConfirm = name.isNotBlank() && hostValid && portValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("设备名称") },
                    placeholder = { Text("例: 电视") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("设备 IP / 主机名") },
                    placeholder = { Text("192.168.1.100") },
                    isError = host.isNotBlank() && !hostValid,
                    supportingText = if (host.isNotBlank() && !hostValid) {
                        { Text("IP 或主机名格式无效", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("设备端口 (1024-65535)") },
                    placeholder = { Text("27183") },
                    isError = port.isNotBlank() && !portValid,
                    supportingText = if (port.isNotBlank() && !portValid) {
                        { Text("端口需在 1024-65535 范围内", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("连接密码 (可选)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canConfirm) {
                        onConfirm(
                            ServerNode(
                                name.trim(),
                                host.trim(),
                                port.toIntOrNull() ?: DEFAULT_PORT,
                                password
                            )
                        )
                    }
                },
                enabled = canConfirm
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
