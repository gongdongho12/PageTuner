package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft

/**
 * Standardized E-Ink Discrete Paging Container.
 * Displays items in fixed discrete pages without vertical drag-scrolling.
 * Standard Pagination Bar: Prev TextButton | Center Page Indicator | Next TextButton
 */
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

    var currentPageIndex by remember(items, pageSize) { mutableStateOf(0) }
    val totalPages = (items.size + pageSize - 1) / pageSize
    val safePageIndex = currentPageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    val currentPageItems = items.drop(safePageIndex * pageSize).take(pageSize)

    val startIndex = safePageIndex * pageSize + 1
    val endIndex = minOf((safePageIndex + 1) * pageSize, items.size)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (totalPages > 1) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = EinkPaper,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, EinkLine),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { if (safePageIndex > 0) currentPageIndex = safePageIndex - 1 },
                        enabled = safePageIndex > 0 && !busy,
                    ) {
                        Text(
                            text = "◄ Prev",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (safePageIndex > 0 && !busy) EinkInk else EinkMuted,
                        )
                    }

                    Text(
                        text = "$startIndex-$endIndex / ${items.size} (Page ${safePageIndex + 1}/$totalPages)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = EinkInk,
                    )

                    TextButton(
                        onClick = { if (safePageIndex < totalPages - 1) currentPageIndex = safePageIndex + 1 },
                        enabled = safePageIndex < totalPages - 1 && !busy,
                    ) {
                        Text(
                            text = "Next ►",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (safePageIndex < totalPages - 1 && !busy) EinkInk else EinkMuted,
                        )
                    }
                }
            }
        }

        currentPageItems.forEach { item ->
            itemContent(item)
        }
    }
}
