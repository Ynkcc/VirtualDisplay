package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ynk.virtualdisplay.data.model.ShizukuState

@Composable
fun ShizukuPermissionScreen(
    state: ShizukuState,
    onRetry: () -> Unit,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val title = if (state is ShizukuState.NotRunning) "Shizuku 未运行" else "未获得 Shizuku 权限"
        val description = if (state is ShizukuState.NotRunning) {
            "本应用需要 Shizuku 才能正常工作。请确保 Shizuku 服务已启动。"
        } else {
            "本应用需要 Shizuku 权限来执行系统级操作。请授予权限以继续。"
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (state is ShizukuState.NotRunning) {
            Button(onClick = onRetry) {
                Text("重试检测")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onRetry) {
                    Text("重试检测")
                }
                Button(onClick = onRequestPermission) {
                    Text("请求授权")
                }
            }
        }
    }
}
