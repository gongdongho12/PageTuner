package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds

/**
 * Keeps a page's content viewport at one stable height while loading/error feedback is overlaid.
 * This prevents a transient indicator from changing auto-fit page size and moving controls.
 */
@Composable
fun ColumnScope.EinkStablePageContent(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .weight(1f)
            .clipToBounds(),
    ) {
        content()
        overlay()
    }
}
