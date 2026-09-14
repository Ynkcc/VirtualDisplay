package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.data.local.AppDataStore
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.ui.components.AppSelectionDialog
import com.ynk.virtualdisplay.ui.components.CreateDisplayDialog
import com.ynk.virtualdisplay.ui.components.DisplayPreset
import com.ynk.virtualdisplay.ui.components.ErrorDetailDialog
import com.ynk.virtualdisplay.ui.display.DisplayActivity
import com.ynk.virtualdisplay.ui.main.MainIntent
import com.ynk.virtualdisplay.ui.main.MainViewModel

/**
 * 虚拟显示控制台主屏：负责状态编排与各个区块 / 弹窗的组合，
 * 具体 UI 区块见 [ConsoleTopBar] / [ConnectionStatusBar] / [DisplayGrid]。
 */
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

    showAppSelectionDialogForDisplayId?.let { displayId ->
        AppSelectionDialog(
            loadApps = { force -> viewModel.listApps(force) },
            onDismiss = { showAppSelectionDialogForDisplayId = null },
            onAppSelected = { appInfo ->
                viewModel.launchSelectedApp(appInfo.packageName, displayId)
                showAppSelectionDialogForDisplayId = null
            }
        )
    }

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
            ConsoleTopBar(
                connectionStatus = uiState.connectionStatus,
                onConnect = { viewModel.handleIntent(MainIntent.ConnectServer) },
                onDisconnect = { viewModel.handleIntent(MainIntent.DisconnectServer) },
                onRefresh = { viewModel.handleIntent(MainIntent.RefreshDisplays) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ConnectionStatusBar(
                uiState = uiState,
                status = status,
                onSelectNode = { viewModel.handleIntent(MainIntent.SelectServerNode(it)) },
                onAddNode = {
                    viewModel.handleIntent(MainIntent.AddServerNode(it))
                    viewModel.handleIntent(MainIntent.SelectServerNode(it))
                },
                onEditNode = { old, new -> viewModel.handleIntent(MainIntent.EditServerNode(old, new)) },
                onRemoveNode = { viewModel.handleIntent(MainIntent.RemoveServerNode(it)) },
                onErrorClick = { showErrorDialog = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "屏幕列表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            DisplayGrid(
                displays = displays,
                orphanDisplayIds = orphanDisplayIds,
                gridState = gridState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onPlay = { displayInfo ->
                    val intent = DisplayActivity.createIntent(
                        context,
                        displayInfo.id,
                        uiState.currentServerNode.uniqueKey()
                    )
                    context.startActivity(intent)
                },
                onDelete = { displayInfo ->
                    // 实时读取设置中的配置：是否在销毁前将应用移回主屏
                    val moveToDefault = AppDataStore.getMoveTasksOnDestroySync()
                    viewModel.handleIntent(MainIntent.ReleaseDisplay(displayInfo.id, moveToDefault))
                },
                onLaunchApp = { displayInfo -> showAppSelectionDialogForDisplayId = displayInfo.id },
                onMirror = { displayInfo ->
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

        if (showErrorDialog) {
            ErrorDetailDialog(message = status, onDismiss = { showErrorDialog = false })
        }

        if (showCreateDialog) {
            CreateDisplayDialog(
                inputWidth = uiState.inputWidth,
                inputHeight = uiState.inputHeight,
                inputDpi = uiState.inputDpi,
                isLoading = uiState.isLoading,
                presets = presets,
                onInputsChange = { w, h, d -> viewModel.handleIntent(MainIntent.UpdateInputs(w, h, d)) },
                onDismiss = { showCreateDialog = false },
                onConfirm = {
                    viewModel.handleIntent(
                        MainIntent.CreateDisplay(uiState.inputWidth, uiState.inputHeight, uiState.inputDpi)
                    )
                    showCreateDialog = false
                }
            )
        }

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
