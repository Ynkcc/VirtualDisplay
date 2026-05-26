package com.example.myapplication.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.myapplication.DisplayInfoModel
import com.example.myapplication.IDisplayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    // 监听生命周期，在 ON_RESUME 时递增 key，强制重新创建 TextureView 实例，彻底清除由于生命周期切换、系统布局通道等引发的缓存及 Buffer 尺寸脏状态
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
                            // 必须设为虚拟显示器的分辨率尺寸，使画面完整且清晰
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
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isOrphan) Color.Gray.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp).fillMaxWidth()
        ) {
            // 1. 顶部标头
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "屏幕 #${displayInfo.id}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOrphan) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isOrphan) {
                    Text(
                        text = "未接管",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                } else {
                    Text(
                        text = "已连接",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FFCC),
                        modifier = Modifier
                            .background(Color(0xFF00FFCC).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
 
            // 2. 预览区域 (高度从 130.dp 缩减为 95.dp，确保不会因为高度过高导致卡片溢出/遮挡删除按钮)
            val name = displayInfo.name
            val width = displayInfo.width
            val height = displayInfo.height
            val aspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 1f
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
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
                    Text("无画面预览", color = Color.Gray, fontSize = 11.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // 3. 详细信息
            Text(
                text = name,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${width} x ${height}",
                fontSize = 9.sp,
                color = Color.Gray
            )
 
            Spacer(modifier = Modifier.height(4.dp))
 
            // 5. 底部控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 删除按钮
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Delete",
                        tint = Color.Red.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }
 
                // 进入全屏按钮
                Button(
                    onClick = onPlay,
                    enabled = !isOrphan,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("操控", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
