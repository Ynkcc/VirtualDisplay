package com.example.myapplication

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
// VirtualDisplay 对象由远端 UserService 持有，本地仅跟踪 displayId
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.sui.Sui

sealed class ShizukuState {
    data object Checking : ShizukuState()
    data object NotRunning : ShizukuState()
    data object PermissionDenied : ShizukuState()
    data object Ready : ShizukuState()
}

class MainActivity : ComponentActivity() {

    private var shizukuState by mutableStateOf<ShizukuState>(ShizukuState.Checking)

    private val REQUEST_PERMISSION_RESULT_LISTENER = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.d("MainActivity", "Shizuku permission result: requestCode=$requestCode, granted=$granted")
        if (granted) {
            shizukuState = ShizukuState.Ready
            // 授权成功后立即绑定
            ShizukuDisplayBridge.bindUserService(this)
        } else {
            shizukuState = ShizukuState.PermissionDenied
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            if (Sui.init(packageName)) {
                Log.i("MainActivity", "Sui initialized successfully")
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "Sui init failed", e)
        }

        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
        checkShizukuStatus()
        
        // 如果已经有权限，直接尝试绑定
        if (shizukuState == ShizukuState.Ready) {
            ShizukuDisplayBridge.bindUserService(this)
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (shizukuState) {
                            is ShizukuState.Checking -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            is ShizukuState.Ready -> {
                                VirtualDisplayScreen()
                            }
                            else -> {
                                ShizukuPermissionScreen(
                                    state = shizukuState,
                                    onRetry = { checkShizukuStatus() },
                                    onRequestPermission = {
                                        try {
                                            Shizuku.requestPermission(ShizukuDisplayBridge.REQUEST_CODE)
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Failed to request permission", e)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkShizukuStatus() {
        if (!ShizukuDisplayBridge.isShizukuAvailable()) {
            shizukuState = ShizukuState.NotRunning
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            shizukuState = ShizukuState.Ready
            // 状态为 Ready 时确保已绑定
            ShizukuDisplayBridge.bindUserService(this)
        } else {
            shizukuState = ShizukuState.PermissionDenied
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
        ShizukuDisplayBridge.unbindUserService()
    }
}

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

data class AppInfo(val name: String, val packageName: String)

@Composable
fun AppSelectionDialog(onDismiss: () -> Unit, onAppSelected: (AppInfo) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps = installedApps
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM == 0) || (pm.getLaunchIntentForPackage(it.packageName) != null) }
                .map { AppInfo(it.loadLabel(pm).toString(), it.packageName) }
                .sortedBy { it.name }
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAppSelected(app) }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(text = app.name, style = MaterialTheme.typography.bodyLarge)
                                Text(text = app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VirtualDisplayScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Ready") }
    val displayIds = remember { mutableStateListOf<Int>() }
    // 当前 UserService 实例无法管理的显示器（孤儿）
    val orphanDisplayIds = remember { mutableStateListOf<Int>() }
    var mirroringDisplayId by remember { mutableStateOf<Int?>(null) }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }

    val refreshDisplays = {
        displayIds.clear()
        orphanDisplayIds.clear()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        // 查询当前 UserService 管理的 displayId 集合，用于识别孤儿显示器
        val managedByService = ShizukuDisplayBridge.getServiceManagedDisplayIds()
        dm.displays.forEach { display ->
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                displayIds.add(display.displayId)
                // 系统中存在但 UserService 不持有句柄 → 孤儿显示器
                if (managedByService.isNotEmpty() && display.displayId !in managedByService) {
                    orphanDisplayIds.add(display.displayId)
                }
            }
        }
        status = "Displays refreshed"
    }

    LaunchedEffect(Unit) {
        refreshDisplays()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Virtual Displays", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { refreshDisplays() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
            
            Text(text = "Status: $status", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            
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
                    onClick = {
                        val capturedApp = selectedApp
                        status = "Waiting for UserService..."
                        // 先绑定（若已绑定会立即返回），再用 whenReady 确保回调在就绪时执行
                        // 修复竞态：原来直接调 createVirtualDisplay，bindUserService 是异步的，
                        // service 未就绪时会立即报错 "UserService 未就绪"。
                        ShizukuDisplayBridge.bindUserService(context)
                        ShizukuDisplayBridge.whenReady {
                            status = "Creating via Shizuku..."
                            when (val result = ShizukuDisplayBridge.createVirtualDisplay(
                                name = "Shizuku_VD_${System.currentTimeMillis()}",
                                width = 1280,
                                height = 720,
                                dpi = 240
                            )) {
                                is ShizukuDisplayBridge.CreateResult.PermissionRequested -> {
                                    status = "请授予 Shizuku 权限"
                                    try {
                                        Shizuku.requestPermission(ShizukuDisplayBridge.REQUEST_CODE)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Failed to request permission", e)
                                    }
                                }
                                is ShizukuDisplayBridge.CreateResult.Error -> {
                                    status = result.message
                                }
                                is ShizukuDisplayBridge.CreateResult.Success -> {
                                    val newDisplayId = result.displayId
                                    refreshDisplays()
                                    capturedApp?.let { app ->
                                        status = "Launching ${app.name} on $newDisplayId..."
                                        VirtualDisplayHelper.launchAppOnDisplay(context, app.packageName, newDisplayId)
                                    } ?: run {
                                        status = "Created Display ID: $newDisplayId"
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Create")
                }
            }

            Text(text = "Active Displays:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            
            if (displayIds.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(text = "No non-primary displays found.", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(displayIds, key = { it }) { id ->
                        DisplayItem(
                            displayId = id,
                            context = context,
                            isOrphan = id in orphanDisplayIds,
                            onPlay = { mirroringDisplayId = id },
                            onDelete = {
                                ShizukuDisplayBridge.releaseVirtualDisplay(id)
                                refreshDisplays()
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

        if (mirroringDisplayId != null) {
            MirrorOverlay(
                displayId = mirroringDisplayId!!,
                onDismiss = { mirroringDisplayId = null }
            )
        }
    }
}

@Composable
fun MirrorOverlay(displayId: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Mirroring Display: $displayId", color = Color.White)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    // VirtualDisplay 对象在远端进程，本地只持有 displayId
                                    private var remoteDisplayId: Int? = null

                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        post {
                                            if (holder.surface.isValid) {
                                                val capturedSurface = holder.surface
                                                // 修复竞态：bindUserService 是异步的，
                                                // 必须等 whenReady 回调后再 setVirtualDisplaySurface，
                                                // 否则 displayService 仍为 null 导致 set 静默失败 → 黑屏
                                                ShizukuDisplayBridge.bindUserService(ctx)
                                                ShizukuDisplayBridge.whenReady {
                                                    if (capturedSurface.isValid) {
                                                        VirtualDisplayHelper.setDisplaySurface(
                                                            displayId = displayId,
                                                            surface = capturedSurface
                                                        )
                                                        remoteDisplayId = displayId
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        remoteDisplayId?.let {
                                            VirtualDisplayHelper.setDisplaySurface(it, null)
                                        }
                                        remoteDisplayId = null
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun DisplayItem(
    displayId: Int,
    context: Context,
    isOrphan: Boolean = false,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val displayInfo = remember(displayId) { VirtualDisplayHelper.getDisplayInfo(context, displayId) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Display #$displayId",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isOrphan) Color.Gray else MaterialTheme.colorScheme.onSurface
                    )
                    if (isOrphan) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "⚠️ 无法管理",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                displayInfo?.let {
                    Text(text = it.name, style = MaterialTheme.typography.bodySmall)
                    Text(text = "${it.width} x ${it.height}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    if (isOrphan) {
                        Text(
                            text = "该显示器来自上次会话，当前 UserService 无法销毁它",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Row {
                IconButton(onClick = onPlay, enabled = !isOrphan) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Show",
                        tint = if (isOrphan) Color.Gray else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Close, contentDescription = "Delete", tint = Color.Red)
                }
            }
        }
    }
}
