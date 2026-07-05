@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.display.applyDisplayMode
import com.dongholab.pagetuner.source.CachedWebCatalog
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteSourceAccount
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
    coverThumbnails: Map<String, ByteArray>,
    cachedCatalogs: List<CachedWebCatalog>,
    sourceAccounts: List<RemoteSourceAccount>,
    displayMode: DisplayMode,
    busy: Boolean,
    statusText: String,
    onCatalogUrlChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onLoadCatalog: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onSaveSourceAccount: () -> Unit,
    onLoadSourceAccount: (RemoteSourceAccount) -> Unit,
    onDeleteSourceAccount: (RemoteSourceAccount) -> Unit,
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
                TextButton(
                    onClick = onSaveSourceAccount,
                    enabled = !busy && catalogUrl.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save_remote_source))
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = EinkInk,
            )
            if (sourceAccounts.isNotEmpty()) {
                SourceAccountsRow(
                    sourceAccounts = sourceAccounts,
                    busy = busy,
                    onLoadSourceAccount = onLoadSourceAccount,
                    onDeleteSourceAccount = onDeleteSourceAccount,
                )
            }
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
                        coverBytes = item.coverUrl?.let { coverThumbnails[it] },
                        displayMode = displayMode,
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
private fun SourceAccountsRow(
    sourceAccounts: List<RemoteSourceAccount>,
    busy: Boolean,
    onLoadSourceAccount: (RemoteSourceAccount) -> Unit,
    onDeleteSourceAccount: (RemoteSourceAccount) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.remote_source_accounts_title, sourceAccounts.size),
            style = MaterialTheme.typography.labelLarge,
            color = EinkInk,
        )
        sourceAccounts.take(4).forEach { account ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = EinkSoft,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, EinkLine),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = EinkInk,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = account.displayEndpoint,
                            style = MaterialTheme.typography.bodySmall,
                            color = EinkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { onLoadSourceAccount(account) },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.action_load_remote_source))
                    }
                    TextButton(
                        onClick = { onDeleteSourceAccount(account) },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.action_delete_remote_source))
                    }
                }
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
    coverBytes: ByteArray?,
    displayMode: DisplayMode,
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
            RemoteCoverThumbnail(
                coverBytes = coverBytes,
                displayMode = displayMode,
                contentDescription = item.title,
            )
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
private fun RemoteCoverThumbnail(
    coverBytes: ByteArray?,
    displayMode: DisplayMode,
    contentDescription: String,
) {
    val bitmap = remember(coverBytes, displayMode) {
        coverBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.copy(Bitmap.Config.ARGB_8888, true)
                ?.also { bitmap -> bitmap.applyDisplayMode(displayMode) }
        }
    }

    Surface(
        modifier = Modifier.size(56.dp),
        color = EinkPanel,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        if (bitmap == null) {
            Icon(
                imageVector = Icons.Filled.ImageIcon,
                contentDescription = contentDescription,
                tint = EinkMuted,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit,
            )
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
