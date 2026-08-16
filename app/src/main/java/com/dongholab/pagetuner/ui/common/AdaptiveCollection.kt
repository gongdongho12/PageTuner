package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.settings.ListLayoutMode

val LocalListLayoutMode = staticCompositionLocalOf { ListLayoutMode.Paged }

/**
 * One collection contract for both E-Ink discrete pages and opt-in touch scrolling.
 *
 * Paged mode retains exact fixed-height rows. Scroll mode is explicitly selected by the user and
 * may provide an expanded row implementation without duplicating the surrounding screen.
 */
@Composable
fun <T> AdaptiveCollection(
    items: List<T>,
    estimatedPagedItemHeight: Dp,
    modifier: Modifier = Modifier,
    itemSpacing: Dp = 6.dp,
    fallbackPageSize: Int = 3,
    busy: Boolean = false,
    pagingState: EinkPagingState = rememberEinkPagingState(),
    itemKey: ((T) -> Any)? = null,
    emptyContent: @Composable () -> Unit = {},
    scrollItemContent: (@Composable (T) -> Unit)? = null,
    pagedItemContent: @Composable (T) -> Unit,
) {
    val scrollState = rememberLazyListState()
    if (items.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            emptyContent()
        }
        return
    }

    when (LocalListLayoutMode.current) {
        ListLayoutMode.Paged -> EinkAutoFitPagingContainer(
            items = items,
            modifier = modifier,
            estimatedItemHeight = estimatedPagedItemHeight,
            itemSpacing = itemSpacing,
            fallbackPageSize = fallbackPageSize,
            busy = busy,
            state = pagingState,
            emptyContent = emptyContent,
            itemContent = pagedItemContent,
        )

        ListLayoutMode.Scroll -> {
            val indexedKey: ((Int, T) -> Any)? = itemKey?.let { stableKey ->
                { _, item -> stableKey(item) }
            }
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .clipToBounds(),
                state = scrollState,
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
            ) {
                itemsIndexed(
                    items = items,
                    key = indexedKey,
                ) { _, item ->
                    if (scrollItemContent != null) {
                        scrollItemContent(item)
                    } else {
                        pagedItemContent(item)
                    }
                }
            }
        }
    }
}
