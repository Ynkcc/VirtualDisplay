package com.ynk.virtualdisplay.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.ui.main.DisplayInfoModel

@Composable
fun DisplayItem(
    displayInfo: DisplayInfoModel,
    isOrphan: Boolean = false,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onLaunchApp: () -> Unit,
    onMirror: (() -> Unit)? = null
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
                        text = if (displayInfo.mirrorDisplayId >= 0) {
                            "#${displayInfo.mirrorDisplayId} -> #${displayInfo.id}"
                        } else {
                            "#${displayInfo.id}"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Text(
                    text = if (isOrphan) "非本app创建" else "已连接",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = Modifier
                        .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
 
            // 2. 详细信息
            Text(
                text = displayInfo.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            val w = displayInfo.width
            val h = displayInfo.height
            Text(
                text = "${w} × ${h} (${if (w > h) "横屏" else "竖屏"})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            // 本应用（daemon）创建的显示器不显示持有者信息
            if (!displayInfo.isOwned) {
                val ownerText = when {
                    displayInfo.ownerPackage != null ->
                        "持有者：${displayInfo.ownerPackage}" +
                            (if (displayInfo.ownerUid > 0) " (uid ${displayInfo.ownerUid})" else "")
                    isOrphan -> "持有者：未知"
                    else -> "持有者：本应用"
                }
                // 单击复制：仅复制持有者包名，不带额外的描述信息
                val context = LocalContext.current
                val ownerCopyTarget = when {
                    displayInfo.ownerPackage != null -> displayInfo.ownerPackage
                    isOrphan -> null // 未知持有者，无包名可复制
                    else -> context.packageName // 本应用持有者 → 当前应用包名
                }
                Text(
                    text = ownerText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            if (ownerCopyTarget != null) {
                                copyToClipboard(context, ownerCopyTarget)
                                Toast.makeText(context, "已复制包名：$ownerCopyTarget", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "持有者未知，无包名可复制", Toast.LENGTH_SHORT).show()
                            }
                        }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
 
            // 5. 底部控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 删除按钮：主屏幕(ID=0)和非本app创建的孤儿显示器不可删除
                // （孤儿由其他应用持有，系统无按 displayId 删除的 API，daemon 无法接管删除）
                // 只有 daemon 自己创建(isOwned)的显示器才能被 daemon 销毁
                if (displayInfo.id != 0 && displayInfo.isOwned) {
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
                }
                
                Spacer(modifier = Modifier.weight(1f))

                if (isOrphan) {
                    if (onMirror != null) {
                        Button(
                            onClick = onMirror,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("镜像", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // 启动应用按钮
                    OutlinedButton(
                        onClick = onLaunchApp,
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
}

/**
 * 将指定文本复制到系统剪贴板。
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("display_owner", text))
}
