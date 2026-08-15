package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

internal fun calculateEinkAutoFitPageSize(
    viewportHeightDp: Float?,
    itemHeightDp: Float,
    itemSpacingDp: Float,
    fallbackPageSize: Int,
    reservedNavigationHeightDp: Float = 60f,
): Int {
    if (viewportHeightDp == null || !viewportHeightDp.isFinite() || viewportHeightDp <= 0f) {
        return fallbackPageSize.coerceIn(1, 5)
    }
    val availableHeight = (viewportHeightDp - reservedNavigationHeightDp).coerceAtLeast(itemHeightDp)
    return (availableHeight / (itemHeightDp + itemSpacingDp)).toInt().coerceIn(1, 8)
}

internal fun coerceEinkPageIndex(
    requestedPageIndex: Int,
    itemCount: Int,
    pageSize: Int,
): Int {
    if (itemCount <= 0 || pageSize <= 0) return 0
    val lastPageIndex = ((itemCount - 1) / pageSize).coerceAtLeast(0)
    return requestedPageIndex.coerceIn(0, lastPageIndex)
}

/**
 * Page-local state whose lifetime is owned by the screen, not by the latest list instance.
 * Refreshing an equivalent list therefore keeps the current viewport page.
 */
@Stable
class EinkPagingState internal constructor(initialPageIndex: Int = 0) {
    var currentPageIndex by mutableIntStateOf(initialPageIndex.coerceAtLeast(0))
        internal set

    fun reset() {
        currentPageIndex = 0
    }

    internal companion object {
        val Saver = Saver<EinkPagingState, Int>(
            save = { it.currentPageIndex },
            restore = ::EinkPagingState,
        )
    }
}

@Composable
fun rememberEinkPagingState(vararg resetKeys: Any?): EinkPagingState =
    rememberSaveable(*resetKeys, saver = EinkPagingState.Saver) { EinkPagingState() }

/**
 * An E-Ink Discrete Paging Container that strictly enforces ZERO item clipping.
 * Any item that cannot fit 100% fully within the visible height is automatically moved to the NEXT PAGE.
 */
@Composable
fun <T> EinkAutoFitPagingContainer(
    items: List<T>,
    modifier: Modifier = Modifier,
    estimatedItemHeight: Dp = 54.dp,
    itemSpacing: Dp = 6.dp,
    fallbackPageSize: Int = 3,
    busy: Boolean = false,
    state: EinkPagingState = rememberEinkPagingState(),
    emptyContent: @Composable () -> Unit = {},
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        emptyContent()
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportHeightDp = maxHeight.value.takeIf {
            maxHeight.isSpecified && it > 0f && it.isFinite()
        }
        val calculatedPageSize = calculateEinkAutoFitPageSize(
            viewportHeightDp = viewportHeightDp,
            itemHeightDp = estimatedItemHeight.value,
            itemSpacingDp = itemSpacing.value,
            fallbackPageSize = fallbackPageSize,
        )

        val totalPages = (items.size + calculatedPageSize - 1) / calculatedPageSize
        val safePageIndex = coerceEinkPageIndex(
            requestedPageIndex = state.currentPageIndex,
            itemCount = items.size,
            pageSize = calculatedPageSize,
        )
        SideEffect {
            if (state.currentPageIndex != safePageIndex) state.currentPageIndex = safePageIndex
        }
        val currentPageItems = items.drop(safePageIndex * calculatedPageSize).take(calculatedPageSize)

        val startIndex = safePageIndex * calculatedPageSize + 1
        val endIndex = minOf((safePageIndex + 1) * calculatedPageSize, items.size)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (totalPages > 1) {
                EinkPageNavigation(
                    startIndex = startIndex,
                    endIndex = endIndex,
                    itemCount = items.size,
                    pageIndex = safePageIndex,
                    pageCount = totalPages,
                    busy = busy,
                    onPrevious = { state.currentPageIndex = (safePageIndex - 1).coerceAtLeast(0) },
                    onNext = { state.currentPageIndex = (safePageIndex + 1).coerceAtMost(totalPages - 1) },
                )
            }

            currentPageItems.forEach { item ->
                itemContent(item)
            }
        }
    }
}
