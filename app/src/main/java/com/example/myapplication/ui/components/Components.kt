package com.example.myapplication.ui.components

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp

@Composable
fun ControlIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun DisplayItem(
    displayId: Int,
    context: Context,
    isOrphan: Boolean = false,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onLaunch: () -> Unit
) {
    val displayInfo = remember(displayId) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.getDisplay(displayId)?.let { display ->
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            Triple(display.name, size.x, size.y)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.8f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "#$displayId",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isOrphan) Color.Gray else MaterialTheme.colorScheme.onSurface
                    )
                    if (isOrphan) {
                        Text(
                            text = "Orphan",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                displayInfo?.let { (name, width, height) ->
                    Text(text = name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(text = "$width x $height", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Close, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(4.dp))
                
                OutlinedButton(
                    onClick = onLaunch,
                    enabled = !isOrphan,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Launch", fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.width(4.dp))

                Button(
                    onClick = onPlay,
                    enabled = !isOrphan,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open", fontSize = 12.sp)
                }
            }
        }
    }
}
