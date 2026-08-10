package com.ynk.virtualdisplay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ynk.virtualdisplay.data.model.AppInfo
import com.ynk.virtualdisplay.data.repository.RecentAppHelper
import com.ynk.virtualdisplay.protocol.DeviceMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App 选择对话框 — 统一的远程/本地节点行为。
 *
 * 由调用方通过 [loadApps] 提供远程设备应用列表（走 RPC），
 * 最近启动记录仍从本地 SharedPreferences 读取。
 */
@Composable
fun AppSelectionDialog(
    loadApps: suspend () -> Result<List<DeviceMessage.AppEntry>>,
    onDismiss: () -> Unit,
    onAppSelected: (AppInfo) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<DeviceMessage.AppEntry>>(emptyList()) }
    var recentPkgs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            recentPkgs = RecentAppHelper.getRecentApps(context)
            loadApps()
                .onSuccess { apps = it }
                .onFailure { error = it.message ?: "Failed to load apps" }
            isLoading = false
        }
    }

    val recentSet = recentPkgs.toSet()
    val recentList = apps.filter { it.packageName in recentSet }
    val otherList = apps.filter { it.packageName !in recentSet }.sortedBy { it.name.lowercase() }
    val orderedApps = recentList + otherList

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Select App to Launch", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                when {
                    isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = error!!, color = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                            }
                        }
                    }
                    orderedApps.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "No apps found")
                        }
                    }
                    else -> {
                        LazyColumn {
                            items(orderedApps) { app ->
                                val isRecent = app.packageName in recentSet
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onAppSelected(AppInfo(app.name, app.packageName)) }
                                        .padding(vertical = 12.dp)
                                ) {
                                    Text(text = app.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = if (isRecent) "${app.packageName} • 最近启动" else app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isRecent) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
