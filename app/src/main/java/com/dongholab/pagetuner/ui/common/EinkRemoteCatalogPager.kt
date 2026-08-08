package com.dongholab.pagetuner.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.source.RemoteCatalogPagingState
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPanel

/** Server-page navigation kept separate from viewport paging inside the catalog list. */
@Composable
fun EinkRemoteCatalogPager(
    paging: RemoteCatalogPagingState,
    busy: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    R.string.web_catalog_remote_page_summary,
                    paging.currentPage,
                    paging.totalPages ?: paging.currentPage,
                    paging.pageItemCount,
                    paging.totalItems ?: paging.pageItemCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = EinkInk,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PagerButton(
                    text = stringResource(R.string.action_first_page),
                    enabled = !busy && paging.hasPreviousPage,
                    onClick = { onPageSelected(1) },
                    modifier = Modifier.weight(1f),
                )
                PagerButton(
                    text = stringResource(R.string.action_previous_page),
                    enabled = !busy && paging.hasPreviousPage,
                    onClick = { onPageSelected(paging.currentPage - 1) },
                    modifier = Modifier.weight(1f),
                )
                PagerButton(
                    text = stringResource(R.string.action_next_page),
                    enabled = !busy && paging.hasNextPage,
                    onClick = { onPageSelected(paging.currentPage + 1) },
                    modifier = Modifier.weight(1f),
                )
                PagerButton(
                    text = stringResource(R.string.action_last_page),
                    enabled = !busy && paging.hasNextPage && paging.totalPages != null,
                    onClick = { paging.totalPages?.let(onPageSelected) },
                    modifier = Modifier.weight(1f),
                )
            }
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
        Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
    }
}
