package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.domain.model.DisplayInfo
import com.ynk.virtualdisplay.ui.components.DisplayItem

/**
 * 虚拟显示器网格：列表为空时展示空态占位。
 *
 * [modifier] 由调用方提供（例如 Column 作用域内的 `Modifier.weight(1f)`）。
 */
@Composable
fun DisplayGrid(
    displays: List<DisplayInfo>,
    orphanDisplayIds: List<Int>,
    gridState: LazyGridState,
    modifier: Modifier,
    onPlay: (DisplayInfo) -> Unit,
    onDelete: (DisplayInfo) -> Unit,
    onLaunchApp: (DisplayInfo) -> Unit,
    onMirror: (DisplayInfo) -> Unit
) {
    if (displays.isEmpty()) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                .border(1.dp, Color.White.copy(alpha = 0.03f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无活跃的虚拟显示器",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    } else {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 72.dp)
        ) {
            items(displays, key = { it.id }) { displayInfo ->
                DisplayItem(
                    displayInfo = displayInfo,
                    isOrphan = displayInfo.id in orphanDisplayIds,
                    onPlay = { onPlay(displayInfo) },
                    onDelete = { onDelete(displayInfo) },
                    onLaunchApp = { onLaunchApp(displayInfo) },
                    onMirror = { onMirror(displayInfo) }
                )
            }
        }
    }
}
