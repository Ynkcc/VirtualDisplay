package com.ynk.virtualdisplay.ui.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ynk.virtualdisplay.ui.main.DisplayInfoModel
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Keep
@Composable
fun DisplayPreview(
    displayId: Int,
    repository: IDisplayRepository,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewKey by remember { mutableStateOf(0) }

    // 监听生命周期，在 ON_RESUME 时递增 key，强制重新创建 TextureView 实例，彻底清除脏缓存及脏尺寸状态
    DisposableEffect(lifecycleOwner, displayId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                previewKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CoroutineScope(Dispatchers.Main).launch {
                repository.setDisplaySurface(displayId, null)
            }
        }
    }

    key(previewKey) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
                            surfaceTexture.setDefaultBufferSize(width, height)
                            val surface = Surface(surfaceTexture)
                            CoroutineScope(Dispatchers.Main).launch {
                                repository.setDisplaySurface(displayId, surface)
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
                            surfaceTexture.setDefaultBufferSize(width, height)
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                    }
                }
            },
            modifier = modifier
        )
    }
}

@Composable
fun DisplayItem(
    displayInfo: DisplayInfoModel,
    repository: IDisplayRepository,
    isOrphan: Boolean = false,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onLaunchApp: () -> Unit
) {
    val borderColor = if (isOrphan) {
        Color(0xFFE57373).copy(alpha = 0.4f)
    } else {
        Color(0xFF4DD0E1).copy(alpha = 0.5f)
    }
    
    val accentColor = if (isOrphan) Color(0xFFEF5350) else Color(0xFF26A69A)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(borderColor, borderColor.copy(alpha = 0.1f))
                ),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth()
        ) {
            // 1. 顶部标头
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(accentColor, RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "显示器 #${displayInfo.id}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Text(
                    text = if (isOrphan) "未接管" else "已连接",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = Modifier
                        .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
 
            // 2. 预览区域
            val width = displayInfo.width
            val height = displayInfo.height
            val aspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 1f
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF121212))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (!isOrphan) {
                    val parentRatio = maxWidth.value / maxHeight.value
                    val (previewWidth, previewHeight) = if (aspectRatio > parentRatio) {
                        maxWidth to (maxWidth / aspectRatio)
                    } else {
                        (maxHeight * aspectRatio) to maxHeight
                    }
                    DisplayPreview(
                        displayId = displayInfo.id,
                        repository = repository,
                        width = width,
                        height = height,
                        modifier = Modifier.size(previewWidth, previewHeight)
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("无画面预览", color = Color.Gray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("需要再次连接", color = Color.Gray.copy(alpha = 0.7f), fontSize = 10.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 3. 详细信息
            Text(
                text = displayInfo.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Text(
                text = "${width} × ${height} (${if (width > height) "横屏" else "竖屏"})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
 
            Spacer(modifier = Modifier.height(10.dp))
 
            // 5. 底部控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 删除按钮
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFFEF5350).copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))

                // 启动应用按钮
                OutlinedButton(
                    onClick = onLaunchApp,
                    enabled = !isOrphan,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("启动", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
 
                // 进入全屏按钮
                Button(
                    onClick = onPlay,
                    enabled = !isOrphan,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("操控", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
