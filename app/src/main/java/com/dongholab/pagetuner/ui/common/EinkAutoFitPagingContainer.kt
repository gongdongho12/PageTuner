package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkMuted

/**
 * An E-Ink Discrete Paging Container that strictly enforces ZERO item clipping.
 * Any item that cannot fit 100% fully within the visible height is automatically moved to the NEXT PAGE.
 */
@Composable
fun <T> EinkAutoFitPagingContainer(
    items: List<T>,
    estimatedItemHeight: Dp = 58.dp,
    itemSpacing: Dp = 6.dp,
    busy: Boolean = false,
    emptyContent: @Composable () -> Unit = {},
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        emptyContent()
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Reserve height for pagination header (44.dp) and safe bottom padding (16.dp)
        val reservedHeaderAndPadding = 60.dp
        val availableHeight = (maxHeight - reservedHeaderAndPadding).coerceAtLeast(estimatedItemHeight)
        
        // Strict floor division: only include an item if 100% of its full height fits
        val itemSlotHeight = estimatedItemHeight + itemSpacing
        val calculatedPageSize = (availableHeight / itemSlotHeight).toInt().coerceIn(1, 8)

        var currentPageIndex by remember(items, calculatedPageSize) { mutableStateOf(0) }
        val totalPages = (items.size + calculatedPageSize - 1) / calculatedPageSize
        val safePageIndex = currentPageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val currentPageItems = items.drop(safePageIndex * calculatedPageSize).take(calculatedPageSize)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (totalPages > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${safePageIndex * calculatedPageSize + 1}-${minOf((safePageIndex + 1) * calculatedPageSize, items.size)} / ${items.size} (Page ${safePageIndex + 1} of $totalPages)",
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
}
