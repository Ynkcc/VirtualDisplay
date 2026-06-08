package com.ynk.virtualdisplay.ui.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.annotation.SuppressLint
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("QueryPermissionsNeeded")
@Composable
fun AppSelectionDialog(onDismiss: () -> Unit, onAppSelected: (AppInfo) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var recentPkgs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val recents = RecentAppHelper.getRecentApps(context)
            recentPkgs = recents

            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val allApps = installedApps
                .filter { info -> (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) || (pm.getLaunchIntentForPackage(info.packageName) != null) }
                .map { info -> AppInfo(info.loadLabel(pm).toString(), info.packageName) }

            val recentList = mutableListOf<AppInfo>()
            recents.forEach { pkg ->
                val app = allApps.find { it.packageName == pkg }
                if (app != null) {
                    recentList.add(app)
                }
            }
            val otherList = allApps.filter { it.packageName !in recents }.sortedBy { it.name }

            apps = recentList + otherList
            isLoading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Select App to Launch", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn {
                        items(apps) { app ->
                            val isRecent = remember(recentPkgs, app.packageName) { app.packageName in recentPkgs }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAppSelected(app) }
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

