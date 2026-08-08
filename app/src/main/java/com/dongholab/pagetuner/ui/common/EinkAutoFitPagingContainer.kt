package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper

/**
 * An E-Ink Discrete Paging Container that strictly enforces ZERO item clipping.
 * Any item that cannot fit 100% fully within the visible height is automatically moved to the NEXT PAGE.
 */
@Composable
fun <T> EinkAutoFitPagingContainer(
    items: List<T>,
    estimatedItemHeight: Dp = 54.dp,
    itemSpacing: Dp = 6.dp,
    fallbackPageSize: Int = 6,
    busy: Boolean = false,
    emptyContent: @Composable () -> Unit = {},
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        emptyContent()
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val reservedHeaderAndPadding = 56.dp
        val availableHeight = if (maxHeight.isSpecified && maxHeight.value > 0 && !maxHeight.value.isInfinite()) {
            (maxHeight - reservedHeaderAndPadding).coerceAtLeast(estimatedItemHeight)
        } else {
            Dp.Unspecified
        }

        val itemSlotHeight = estimatedItemHeight + itemSpacing
        val calculatedPageSize = if (availableHeight.isSpecified) {
            (availableHeight / itemSlotHeight).toInt().coerceIn(1, 15)
        } else {
            fallbackPageSize
        }

        var currentPageIndex by remember(items, calculatedPageSize) { mutableStateOf(0) }
        val totalPages = (items.size + calculatedPageSize - 1) / calculatedPageSize
        val safePageIndex = currentPageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        val currentPageItems = items.drop(safePageIndex * calculatedPageSize).take(calculatedPageSize)

        val startIndex = safePageIndex * calculatedPageSize + 1
        val endIndex = minOf((safePageIndex + 1) * calculatedPageSize, items.size)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
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
}
