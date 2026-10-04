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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.dongholab.pagetuner.core.paging.ListPagePolicy

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

internal data class EinkAutoFitPagePlan(
    val pageSize: Int,
    val showNavigation: Boolean,
    /**
     * A last-resort layout for a viewport that cannot contain both one complete row and the
     * navigation bar. Showing the row first prevents the old "navigation only" blank page.
     * Screens should still give the pager enough space for both whenever possible.
     */
    val navigationAfterItems: Boolean,
)

internal fun calculateEinkAutoFitPagePlan(
    viewportHeightDp: Float?,
    itemHeightDp: Float,
    itemSpacingDp: Float,
    fallbackPageSize: Int,
    itemCount: Int,
    reservedNavigationHeightDp: Float = 60f,
): EinkAutoFitPagePlan {
    if (itemCount <= 0) {
        return EinkAutoFitPagePlan(pageSize = 1, showNavigation = false, navigationAfterItems = false)
    }

    if (viewportHeightDp == null || !viewportHeightDp.isFinite() || viewportHeightDp <= 0f) {
        val pageSize = fallbackPageSize.coerceIn(1, 5)
        return EinkAutoFitPagePlan(
            pageSize = pageSize,
            showNavigation = itemCount > pageSize,
            navigationAfterItems = false,
        )
    }

    val safeItemHeight = itemHeightDp.coerceAtLeast(1f)
    val safeSpacing = itemSpacingDp.coerceAtLeast(0f)
    val itemsWithoutNavigation =
        ((viewportHeightDp + safeSpacing) / (safeItemHeight + safeSpacing))
            .toInt()
            .coerceIn(1, 8)

    if (itemCount <= itemsWithoutNavigation) {
        return EinkAutoFitPagePlan(
            pageSize = itemsWithoutNavigation,
            showNavigation = false,
            navigationAfterItems = false,
        )
    }

    val minimumPagedHeight = reservedNavigationHeightDp + safeSpacing + safeItemHeight
    if (viewportHeightDp < minimumPagedHeight) {
        return EinkAutoFitPagePlan(
            pageSize = 1,
            showNavigation = true,
            navigationAfterItems = true,
        )
    }

    val pageSize = ((viewportHeightDp - reservedNavigationHeightDp) / (safeItemHeight + safeSpacing))
        .toInt()
        .coerceIn(1, 8)
    return EinkAutoFitPagePlan(
        pageSize = pageSize,
        showNavigation = true,
        navigationAfterItems = false,
    )
}

internal fun coerceEinkPageIndex(
    requestedPageIndex: Int,
    itemCount: Int,
    pageSize: Int,
): Int {
    return ListPagePolicy.coercePageIndex(requestedPageIndex, itemCount, pageSize)
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        val viewportHeightDp = maxHeight.value.takeIf {
            maxHeight.isSpecified && it > 0f && it.isFinite()
        }
        val pagePlan = calculateEinkAutoFitPagePlan(
            viewportHeightDp = viewportHeightDp,
            itemHeightDp = estimatedItemHeight.value,
            itemSpacingDp = itemSpacing.value,
            fallbackPageSize = fallbackPageSize,
            itemCount = items.size,
        )
        val calculatedPageSize = pagePlan.pageSize

        val listPage = ListPagePolicy.slice(items, state.currentPageIndex, calculatedPageSize)
        val totalPages = listPage.pageCount
        val safePageIndex = listPage.pageIndex
        SideEffect {
            if (state.currentPageIndex != safePageIndex) state.currentPageIndex = safePageIndex
        }
        val currentPageItems = listPage.items
        val startIndex = listPage.startItemNumber
        val endIndex = listPage.endItemNumber

        val navigation: @Composable () -> Unit = {
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

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (pagePlan.showNavigation && !pagePlan.navigationAfterItems) {
                navigation()
            }

            currentPageItems.forEach { item ->
                itemContent(item)
            }

            if (pagePlan.showNavigation && pagePlan.navigationAfterItems) {
                navigation()
            }
        }
    }
}
