package com.example.myapplication.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapplication.DisplayActivity
import com.example.myapplication.MainViewModel
import com.example.myapplication.models.AppInfo
import com.example.myapplication.ui.components.AppSelectionDialog
import com.example.myapplication.ui.components.DisplayItem

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun VirtualDisplayScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val displayIds = uiState.displayIds
    val orphanDisplayIds = uiState.orphanDisplayIds
    val status = uiState.statusMessage
    
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshDisplays(context)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Virtual Displays", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { viewModel.refreshDisplays(context) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
            
            Text(text = "Status: $status", modifier = Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = if (status.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            
            // 分辨率输入行
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = uiState.inputWidth,
                    onValueChange = { viewModel.updateInputs(width = it) },
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.inputHeight,
                    onValueChange = { viewModel.updateInputs(height = it) },
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.inputDpi,
                    onValueChange = { viewModel.updateInputs(dpi = it) },
                    label = { Text("DPI") },
                    modifier = Modifier.weight(0.8f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showAppPicker = true },
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text(text = selectedApp?.name ?: "Select App", maxLines = 1)
                }
                
                if (selectedApp != null) {
                    IconButton(onClick = { selectedApp = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { viewModel.createVirtualDisplay(context) },
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isLoading
                ) {
                    Text(text = if (uiState.isLoading) "..." else "Create")
                }
            }

            Text(text = "Active Displays:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            
            if (displayIds.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(text = "No non-primary displays found.", color = Color.Gray)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(displayIds, key = { it }) { id ->
                        DisplayItem(
                            displayId = id,
                            context = context,
                            isOrphan = id in orphanDisplayIds,
                            onPlay = {
                                val intent = Intent(context, DisplayActivity::class.java).apply {
                                    putExtra("display_id", id)
                                }
                                context.startActivity(intent)
                            },
                            onDelete = { viewModel.releaseDisplay(id, context) },
                            onLaunch = {
                                selectedApp?.let { app ->
                                    viewModel.launchSelectedApp(context, id, app.packageName)
                                }
                            }
                        )
                    }
                }
            }
        }

        if (showAppPicker) {
            AppSelectionDialog(
                onDismiss = { showAppPicker = false },
                onAppSelected = {
                    selectedApp = it
                    showAppPicker = false
                }
            )
        }
    }
}
