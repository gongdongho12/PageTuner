@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.source.CachedWebCatalog
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteSourceTodo
import com.dongholab.pagetuner.source.RemoteSourceTodos
import com.dongholab.pagetuner.ui.text.localizedName
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

@Composable
fun RemoteSourcesTodoPanel(
    catalogUrl: String,
    query: String,
    items: List<RemoteBookItem>,
    cachedCatalogs: List<CachedWebCatalog>,
    busy: Boolean,
    statusText: String,
    onCatalogUrlChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onLoadCatalog: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onLoadCachedCatalog: (CachedWebCatalog) -> Unit,
    onImportItem: (RemoteBookItem) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RemoteSourcesHeader()
            OutlinedTextField(
                value = catalogUrl,
                onValueChange = onCatalogUrlChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                label = { Text(stringResource(R.string.field_web_catalog_url)) },
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                label = { Text(stringResource(R.string.field_web_catalog_search)) },
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = onLoadCatalog,
                    enabled = !busy && catalogUrl.isNotBlank(),
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_load_catalog))
                }
                TextButton(
                    onClick = onRefreshCatalog,
                    enabled = !busy && catalogUrl.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_refresh_catalog))
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = EinkInk,
            )
            if (cachedCatalogs.isNotEmpty()) {
                CachedCatalogsRow(
                    cachedCatalogs = cachedCatalogs,
                    busy = busy,
                    onLoadCachedCatalog = onLoadCachedCatalog,
                )
            }
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.web_catalog_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                )
            } else {
                items.take(5).forEach { item ->
                    RemoteBookRow(
                        item = item,
                        busy = busy,
                        onImportItem = onImportItem,
                    )
                }
            }
            RemoteSourceTodos.items.take(2).forEach { item ->
                RemoteSourceTodoRow(item)
            }
        }
    }
}

@Composable
private fun RemoteSourcesHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.remote_sources_title),
            style = MaterialTheme.typography.titleMedium,
            color = EinkInk,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.remote_sources_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = EinkMuted,
        )
    }
}

@Composable
private fun CachedCatalogsRow(
    cachedCatalogs: List<CachedWebCatalog>,
    busy: Boolean,
    onLoadCachedCatalog: (CachedWebCatalog) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.web_catalog_cached_count, cachedCatalogs.size),
            style = MaterialTheme.typography.labelLarge,
            color = EinkInk,
            modifier = Modifier.padding(top = 8.dp),
        )
        cachedCatalogs.take(3).forEach { cached ->
            TextButton(
                onClick = { onLoadCachedCatalog(cached) },
                enabled = !busy,
            ) {
                Text(
                    text = stringResource(
                        R.string.web_catalog_cached_entry,
                        cached.title,
                        cached.itemCount,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RemoteBookRow(
    item: RemoteBookItem,
    busy: Boolean,
    onImportItem: (RemoteBookItem) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkSoft,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = EinkInk,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.web_catalog_book_meta,
                        item.format.localizedName(),
                        item.language ?: stringResource(R.string.web_catalog_unknown_language),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.authors.isNotEmpty()) {
                    Text(
                        text = item.authors.joinToString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = { onImportItem(item) },
                enabled = !busy,
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_import_remote_book))
            }
        }
    }
}

@Composable
private fun RemoteSourceTodoRow(item: RemoteSourceTodo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            color = EinkPanel,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, EinkLine),
        ) {
            Text(
                text = stringResource(item.phaseRes),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = EinkInk,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(item.titleRes),
                style = MaterialTheme.typography.labelLarge,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(item.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = EinkMuted,
            )
        }
    }
}
