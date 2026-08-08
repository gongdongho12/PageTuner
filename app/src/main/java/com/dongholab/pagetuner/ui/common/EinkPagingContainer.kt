package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkMuted

@Composable
fun <T> EinkPagingContainer(
    items: List<T>,
    pageSize: Int = 5,
    busy: Boolean = false,
    emptyContent: @Composable () -> Unit = {},
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        emptyContent()
        return
    }

    var currentPageIndex by remember(items) { mutableStateOf(0) }
    val totalPages = (items.size + pageSize - 1) / pageSize
    val safePageIndex = currentPageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    val currentPageItems = items.drop(safePageIndex * pageSize).take(pageSize)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (totalPages > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${safePageIndex * pageSize + 1}-${minOf((safePageIndex + 1) * pageSize, items.size)} / ${items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EinkMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { if (safePageIndex > 0) currentPageIndex = safePageIndex - 1 },
                        enabled = safePageIndex > 0 && !busy,
                    ) {
                        Text("◄ Prev")
                    }
                    TextButton(
                        onClick = { if (safePageIndex < totalPages - 1) currentPageIndex = safePageIndex + 1 },
                        enabled = safePageIndex < totalPages - 1 && !busy,
                    ) {
                        Text("Next ►")
                    }
                }
            }
        }

        currentPageItems.forEach { item ->
            itemContent(item)
        }
    }
}
