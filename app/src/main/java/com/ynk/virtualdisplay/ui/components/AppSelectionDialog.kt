package com.ynk.virtualdisplay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
 *
 * 列表按「最近启动 / 用户应用 / 系统应用」分组展示，
 * 系统/用户由 [DeviceMessage.AppEntry.isSystem] 区分。
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
    // 默认不显示系统应用，通过顶部开关切换
    var showSystemApps by remember { mutableStateOf(false) }
    // 搜索关键字，匹配应用名称或包名
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            recentPkgs = RecentAppHelper.getRecentApps(context)
            loadApps()
                .onSuccess { apps = it }
                .onFailure { error = it.message ?: "Failed to load apps" }
            isLoading = false
        }
    }

    // 搜索时同时匹配应用名称与包名（大小写不敏感）
    val query = searchQuery.trim()
    val filteredBySystem = { list: List<DeviceMessage.AppEntry> ->
        list.filter { showSystemApps || !it.isSystem }
    }
    val matched = if (query.isEmpty()) {
        apps
    } else {
        apps.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }
    val filtered = filteredBySystem(matched)

    val recentSet = recentPkgs.toSet()
    val recentList = filtered.filter { it.packageName in recentSet }
    val others = filtered.filter { it.packageName !in recentSet }
    val userApps = others.filter { !it.isSystem }.sortedBy { it.name.lowercase() }
    val systemApps = others.filter { it.isSystem }.sortedBy { it.name.lowercase() }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select App to Launch",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "显示系统应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索应用名称或包名") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除")
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium
                )
                Spacer(modifier = Modifier.height(8.dp))
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
                    apps.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "No apps found")
                        }
                    }
                    filtered.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (query.isNotEmpty()) "未找到匹配的应用" else "没有可显示的应用",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn {
                            if (recentList.isNotEmpty()) {
                                item(key = "header_recent") {
                                    SectionHeader(text = "最近启动", isRecent = true)
                                }
                                items(recentList, key = { "recent_${it.packageName}" }) { app ->
                                    AppRow(
                                        app = app,
                                        isRecent = true,
                                        onClick = { onAppSelected(AppInfo(app.name, app.packageName)) }
                                    )
                                }
                            }
                            if (userApps.isNotEmpty()) {
                                item(key = "header_user") {
                                    SectionHeader(text = "用户应用", isRecent = false)
                                }
                                items(userApps, key = { "user_${it.packageName}" }) { app ->
                                    AppRow(
                                        app = app,
                                        isRecent = false,
                                        onClick = { onAppSelected(AppInfo(app.name, app.packageName)) }
                                    )
                                }
                            }
                            if (showSystemApps && systemApps.isNotEmpty()) {
                                item(key = "header_system") {
                                    SectionHeader(text = "系统应用", isRecent = false)
                                }
                                items(systemApps, key = { "system_${it.packageName}" }) { app ->
                                    AppRow(
                                        app = app,
                                        isRecent = false,
                                        onClick = { onAppSelected(AppInfo(app.name, app.packageName)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, isRecent: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isRecent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppRow(app: DeviceMessage.AppEntry, isRecent: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
