package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Standardized E-Ink Discrete Paging Container.
 * Displays items in fixed discrete pages without vertical drag-scrolling.
 * Standard Pagination Bar: Prev TextButton | Center Page Indicator | Next TextButton
 */
@Composable
fun <T> EinkPagingContainer(
    items: List<T>,
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (totalPages > 1) {
            EinkPageNavigation(
                startIndex = startIndex,
                endIndex = endIndex,
                itemCount = items.size,
                pageIndex = safePageIndex,
                pageCount = totalPages,
                busy = busy,
                onPrevious = { currentPageIndex = (safePageIndex - 1).coerceAtLeast(0) },
                onNext = { currentPageIndex = (safePageIndex + 1).coerceAtMost(totalPages - 1) },
            )
        }

        currentPageItems.forEach { item ->
            itemContent(item)
        }
    }
}
