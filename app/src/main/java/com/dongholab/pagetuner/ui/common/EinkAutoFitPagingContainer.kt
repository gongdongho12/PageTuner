package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.core.paging.ListPagePolicy
import com.dongholab.pagetuner.ui.theme.EinkInk

internal fun calculateEinkAutoFitPageSize(
    viewportHeightDp: Float?,
    itemHeightDp: Float,
    itemSpacingDp: Float,
    fallbackPageSize: Int,
    reservedNavigationHeightDp: Float = 60f,
): Int {
    if (viewportHeightDp == null || !viewportHeightDp.isFinite()) {
        return fallbackPageSize.coerceIn(1, 5)
    }
    val itemHeight = itemHeightDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val spacing = itemSpacingDp.takeIf { it.isFinite() && it >= 0f } ?: 0f
    return ((viewportHeightDp - reservedNavigationHeightDp).coerceAtLeast(0f) /
        (itemHeight + spacing)).toInt()
}

internal data class EinkAutoFitPagePlan(
    val pageSize: Int,
    val showNavigation: Boolean,
    /** Non-null when no complete page fits. Never compose a partially visible row or button. */
    val requiredHeight: Float? = null,
)

internal fun calculateEinkAutoFitPagePlan(
    viewportHeightDp: Float?,
    itemHeightDp: Float,
    itemSpacingDp: Float,
    fallbackPageSize: Int,
    itemCount: Int,
    reservedNavigationHeightDp: Float = 60f,
    requireNavigation: Boolean = false,
): EinkAutoFitPagePlan {
    if (itemCount <= 0) return EinkAutoFitPagePlan(pageSize = 1, showNavigation = false)
    if (viewportHeightDp == null || !viewportHeightDp.isFinite()) {
        val pageSize = fallbackPageSize.coerceIn(1, 5)
        return EinkAutoFitPagePlan(pageSize, showNavigation = requireNavigation || itemCount > pageSize)
    }

    val itemHeight = itemHeightDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val spacing = itemSpacingDp.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val itemsWithoutNavigation =
        ((viewportHeightDp.coerceAtLeast(0f) + spacing) / (itemHeight + spacing)).toInt()
    if (!requireNavigation && itemCount <= itemsWithoutNavigation) {
        return EinkAutoFitPagePlan(pageSize = itemsWithoutNavigation, showNavigation = false)
    }

    val pageSize = calculateEinkAutoFitPageSize(
        viewportHeightDp, itemHeight, spacing, fallbackPageSize, reservedNavigationHeightDp,
    )
    if (pageSize == 0) {
        return EinkAutoFitPagePlan(
            pageSize = 0,
            showNavigation = false,
            requiredHeight = if (requireNavigation) reservedNavigationHeightDp + spacing + itemHeight
            else if (itemCount == 1) itemHeight
            else minOf(itemCount.toDouble() * (itemHeight + spacing) - spacing,
                (reservedNavigationHeightDp + spacing + itemHeight).toDouble()).toFloat(),
        )
    }
    return EinkAutoFitPagePlan(pageSize, showNavigation = true)
}

internal fun coerceEinkPageIndex(requestedPageIndex: Int, itemCount: Int, pageSize: Int): Int =
    ListPagePolicy.coercePageIndex(requestedPageIndex, itemCount, pageSize)

internal data class EinkCollectionAnchor(val index: Int, val key: Any? = null)

/** Resolve the original anchor, not the previous resize's page boundary, to avoid cumulative drift. */
internal fun <T> resolveEinkCollectionAnchor(
    anchor: EinkCollectionAnchor?,
    items: List<T>,
    pageSize: Int,
    requestedPageIndex: Int,
    itemKey: ((T) -> Any)?,
): EinkCollectionAnchor {
    val keyedIndex = if (anchor?.key != null && itemKey != null) {
        items.indexOfFirst { itemKey(it) == anchor.key }.takeIf { it >= 0 }
    } else null
    val index = (keyedIndex ?: anchor?.index ?: (requestedPageIndex.toLong() * pageSize)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).coerceIn(0, (items.size - 1).coerceAtLeast(0))
    return EinkCollectionAnchor(index, items.getOrNull(index)?.let { itemKey?.invoke(it) })
}

/** Screen-owned page state retains a visible item across refresh, resize and temporary loading. */
@Stable
class EinkPagingState internal constructor(initialPageIndex: Int = 0) {
    var currentPageIndex by mutableIntStateOf(initialPageIndex.coerceAtLeast(0))
        private set
    internal var anchor: EinkCollectionAnchor? = null
        private set
    private var resetVersion by mutableIntStateOf(0)

    var pageCount by mutableIntStateOf(1)
        internal set

    var canBoundaryPrevious: Boolean = false
        internal set

    var canBoundaryNext: Boolean = false
        internal set

    internal var boundaryPreviousAction: (() -> Unit)? = null
    internal var boundaryNextAction: (() -> Unit)? = null
    internal var navigationEnabled: Boolean = true

    fun reset() {
        anchor = null
        currentPageIndex = 0
        // Also invalidate a first page whose anchor is a later row after resize.
        resetVersion++
    }

    internal fun <T> resolve(items: List<T>, pageSize: Int, itemKey: ((T) -> Any)?): EinkCollectionAnchor {
        @Suppress("UNUSED_EXPRESSION")
        resetVersion
        return resolveEinkCollectionAnchor(anchor, items, pageSize, currentPageIndex, itemKey)
    }

    internal fun rememberAnchor(resolved: EinkCollectionAnchor, pageIndex: Int) {
        anchor = resolved
        currentPageIndex = pageIndex
    }

    internal fun <T> moveTo(pageIndex: Int, items: List<T>, pageSize: Int, itemKey: ((T) -> Any)?) {
        val safeIndex = coerceEinkPageIndex(pageIndex, items.size, pageSize)
        anchor = resolveEinkCollectionAnchor(null, items, pageSize, safeIndex, itemKey)
        currentPageIndex = safeIndex
    }

    fun nextPage(): Boolean {
        if (!navigationEnabled) return false
        return if (currentPageIndex < pageCount - 1) {
            // An explicit hardware turn replaces the stable resize anchor.
            anchor = null
            currentPageIndex++
            true
        } else if (canBoundaryNext && boundaryNextAction != null) {
            boundaryNextAction?.invoke()
            true
        } else {
            false
        }
    }

    fun previousPage(): Boolean {
        if (!navigationEnabled) return false
        return if (currentPageIndex > 0) {
            anchor = null
            currentPageIndex--
            true
        } else if (canBoundaryPrevious && boundaryPreviousAction != null) {
            boundaryPreviousAction?.invoke()
            true
        } else {
            false
        }
    }

    internal companion object {
        val Saver = Saver<EinkPagingState, List<Any>>(
            save = {
                buildList {
                    add(it.currentPageIndex)
                    add(it.anchor?.index ?: -1)
                    it.anchor?.key?.takeIf { key -> canBeSaved(key) }?.let(::add)
                }
            },
            restore = { saved ->
                EinkPagingState(saved[0] as Int).apply {
                    val index = saved[1] as Int
                    if (index >= 0) anchor = EinkCollectionAnchor(index, saved.getOrNull(2))
                }
            },
        )
    }
}

@Composable
fun rememberEinkPagingState(vararg resetKeys: Any?): EinkPagingState =
    rememberSaveable(*resetKeys, saver = EinkPagingState.Saver) { EinkPagingState() }

/** Bounded pages use every complete row that fits, including the real navigation height. */
@Composable
fun <T> EinkAutoFitPagingContainer(
    items: List<T>,
    modifier: Modifier = Modifier,
    estimatedItemHeight: Dp = 54.dp,
    itemSpacing: Dp = 6.dp,
    fallbackPageSize: Int = 3,
    busy: Boolean = false,
    state: EinkPagingState = rememberEinkPagingState(),
    onPageBoundaryPrevious: (() -> Unit)? = null,
    onPageBoundaryNext: (() -> Unit)? = null,
    onFastBoundaryPrevious: (() -> Unit)? = null,
    onFastBoundaryNext: (() -> Unit)? = null,
    pageInfoPrefix: String? = null,
    onPageInfoClick: (() -> Unit)? = null,
    itemKey: ((T) -> Any)? = null,
    onInsufficientHeight: ((Dp?) -> Unit)? = null,
    emptyContent: @Composable () -> Unit = {},
    itemContent: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().clipToBounds()) {
        val density = LocalDensity.current
        val navigationHeight = einkPageNavigationHeight()
        // Use rounded physical pixels: independently rounded dp slots otherwise overflow at
        // non-integer display densities.
        val pagePlan = with(density) {
            calculateEinkAutoFitPagePlan(
                viewportHeightDp = constraints.maxHeight.takeIf { constraints.hasBoundedHeight }?.toFloat(),
                itemHeightDp = estimatedItemHeight.roundToPx().toFloat(),
                itemSpacingDp = itemSpacing.roundToPx().toFloat(),
                fallbackPageSize = fallbackPageSize,
                itemCount = items.size,
                reservedNavigationHeightDp = navigationHeight.roundToPx().toFloat(),
                requireNavigation = onPageBoundaryPrevious != null || onPageBoundaryNext != null || onPageInfoClick != null,
            )
        }
        val requiredHeight = pagePlan.requiredHeight?.let { with(density) { it.toDp() } }
        val notifyHeight by rememberUpdatedState(onInsufficientHeight)
        LaunchedEffect(requiredHeight) { notifyHeight?.invoke(requiredHeight) }
        if (items.isEmpty()) {
            SideEffect { state.navigationEnabled = false }
            emptyContent()
            return@BoxWithConstraints
        }
        if (requiredHeight != null) {
            SideEffect { state.navigationEnabled = false }
            CollectionSpaceNotice()
            return@BoxWithConstraints
        }

        val pageSize = pagePlan.pageSize
        val anchor = state.resolve(items, pageSize, itemKey)
        val listPage = ListPagePolicy.slice(items, anchor.index / pageSize, pageSize)
        SideEffect {
            state.rememberAnchor(anchor, listPage.pageIndex)
            state.pageCount = listPage.pageCount
            state.navigationEnabled = !busy
            state.canBoundaryPrevious = onPageBoundaryPrevious != null
            state.canBoundaryNext = onPageBoundaryNext != null
            state.boundaryPreviousAction = onPageBoundaryPrevious
            state.boundaryNextAction = onPageBoundaryNext
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
            if (pagePlan.showNavigation) {
                EinkPageNavigation(
                    startIndex = listPage.startItemNumber,
                    endIndex = listPage.endItemNumber,
                    itemCount = items.size,
                    pageIndex = listPage.pageIndex,
                    pageCount = listPage.pageCount,
                    busy = busy,
                    onPrevious = { state.previousPage() },
                    onNext = { state.nextPage() },
                    canPrevious = !busy && (listPage.pageIndex > 0 || onPageBoundaryPrevious != null),
                    canNext = !busy && (listPage.pageIndex < listPage.pageCount - 1 || onPageBoundaryNext != null),
                    infoPrefix = pageInfoPrefix,
                    onInfoClick = onPageInfoClick,
                    onFastPrevious = onFastBoundaryPrevious,
                    onFastNext = onFastBoundaryNext,
                    height = navigationHeight,
                )
            }
            listPage.items.forEachIndexed { index, item ->
                key(itemKey?.invoke(item) ?: (listPage.startItemNumber - 1 + index)) { itemContent(item) }
            }
        }
    }
}

/** A keyboard-covered viewport must not expose cut-off rows or unreachable navigation. */
@Composable
private fun CollectionSpaceNotice() {
    val message = stringResource(R.string.collection_more_space_required)
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = message }) {
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.bodySmall
        val textConstraints = Constraints(maxWidth = constraints.maxWidth)
        val full = measurer.measure(AnnotatedString(message), style = style, constraints = textConstraints)
        val text = if (full.size.height <= constraints.maxHeight && !full.hasVisualOverflow) message else "↕"
        val measured = measurer.measure(AnnotatedString(text), style = style, constraints = textConstraints)
        if (measured.size.height <= constraints.maxHeight && !measured.hasVisualOverflow) {
            Text(text, style = style, color = EinkInk)
        }
    }
}
