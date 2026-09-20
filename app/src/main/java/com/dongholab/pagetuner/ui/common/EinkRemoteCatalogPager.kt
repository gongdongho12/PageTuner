package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.source.RemoteCatalogPagingState
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkPaper

val EinkRemoteCatalogPagerHeight = 48.dp

/** Keeps the server-pager slot stable before, during, and after a remote refresh. */
@Composable
fun EinkRemoteCatalogPagerSlot(
    paging: RemoteCatalogPagingState?,
    busy: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(EinkRemoteCatalogPagerHeight),
    ) {
        if (paging != null) {
            EinkRemoteCatalogPager(
                paging = paging,
                busy = busy,
                onPageSelected = onPageSelected,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Server-page navigation with compact E-Ink controls and quick page jump. */
@Composable
fun EinkRemoteCatalogPager(
    paging: RemoteCatalogPagingState,
    busy: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showJumpDialog by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val totalPages = paging.totalPages ?: paging.currentPage

    if (showJumpDialog) {
        com.dongholab.pagetuner.ui.source.CatalogPageJumpDialog(
            currentPage = paging.currentPage,
            totalPages = totalPages,
            onJumpToPage = { targetPage ->
                onPageSelected(targetPage)
                showJumpDialog = false
            },
            onDismiss = { showJumpDialog = false },
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PagerButton(
                text = "◀ 10",
                enabled = !busy && paging.currentPage > 1,
                onClick = { onPageSelected((paging.currentPage - 10).coerceAtLeast(1)) },
                modifier = Modifier.weight(0.18f),
            )
            PagerButton(
                text = "◀",
                enabled = !busy && paging.hasPreviousPage,
                onClick = { onPageSelected(paging.currentPage - 1) },
                modifier = Modifier.weight(0.16f),
            )
            OutlinedButton(
                onClick = { showJumpDialog = true },
                enabled = !busy,
                modifier = Modifier
                    .weight(0.32f)
                    .heightIn(min = 42.dp),
                shape = RoundedCornerShape(2.dp),
                border = BorderStroke(1.dp, EinkInk),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    containerColor = EinkPaper,
                    contentColor = EinkInk,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "${paging.currentPage} / ${paging.totalPages ?: "?"} ▾",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = EinkInk,
                    )
                    Text(
                        text = stringResource(R.string.catalog_jump_hint),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = EinkMuted,
                    )
                }
            }
            PagerButton(
                text = "▶",
                enabled = !busy && paging.hasNextPage,
                onClick = { onPageSelected(paging.currentPage + 1) },
                modifier = Modifier.weight(0.16f),
            )
            PagerButton(
                text = "10 ▶",
                enabled = !busy && (paging.totalPages == null || paging.currentPage < totalPages),
                onClick = { onPageSelected((paging.currentPage + 10).coerceAtMost(totalPages)) },
                modifier = Modifier.weight(0.18f),
            )
        }
    }
}

@Composable
private fun PagerButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 42.dp),
        shape = RoundedCornerShape(2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
    ) {
        Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
