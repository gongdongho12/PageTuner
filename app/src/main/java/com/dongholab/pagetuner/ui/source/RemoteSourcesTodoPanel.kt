@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Image as ImageIcon
import com.dongholab.pagetuner.display.DisplayMode
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            var showAddSourceDialog by remember { mutableStateOf(false) }
            var activeCatalogUrlPage by remember { mutableStateOf<String?>(null) }
            var selectedNovelForDetail by remember { mutableStateOf<RemoteBookItem?>(null) }
            var fetchedChapters by remember { mutableStateOf<List<RemoteBookItem>>(emptyList()) }

            LaunchedEffect(selectedNovelForDetail) {
                val item = selectedNovelForDetail
                if (item != null) {
                    fetchedChapters = emptyList()
                    val chapters = runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.dongholab.pagetuner.source.WebNovelRemoteBookSource(
                                accountId = item.identity.accountId,
                                endpointUrl = item.downloadUrl,
                            ).list()
                        }
                    }.getOrDefault(emptyList())
                    fetchedChapters = chapters
                }
            }

            val currentSelectedNovel = selectedNovelForDetail
            if (currentSelectedNovel != null) {
                WebNovelDetailPagePanel(
                    novelItem = currentSelectedNovel,
                    coverBytes = currentSelectedNovel.coverUrl?.let { coverThumbnails[it] },
                    chapters = fetchedChapters,
                    displayMode = displayMode,
                    busy = busy,
                    onBackToList = { selectedNovelForDetail = null },
                    onReadChapter = onImportItem,
                )
                return@Column
            }

            if (showAddSourceDialog) {
                AddCatalogSourceDialog(
                    busy = busy,
                    onDismiss = { showAddSourceDialog = false },
                    onAddCatalog = { title, url ->
                        onCatalogUrlChange(url)
                        onSaveSourceAccount()
                        onLoadCatalog()
                        activeCatalogUrlPage = url
                    },
                )
            }

            val currentCatalogPage = activeCatalogUrlPage
            if (currentCatalogPage != null) {
                WebCatalogPagePanel(
                    catalogUrl = currentCatalogPage,
                    query = query,
                    items = items,
                    coverThumbnails = coverThumbnails,
                    displayMode = displayMode,
                    busy = busy,
                    statusText = statusText,
                    onQueryChange = onQueryChange,
                    onRefreshCatalog = onRefreshCatalog,
                    onOpenDetail = { item -> selectedNovelForDetail = item },
                    onImportItem = onImportItem,
                    onBackToSourceManager = { activeCatalogUrlPage = null },
                )
                return@Column
            }

            var activeSubTab by remember { mutableStateOf(0) } // 0: Catalog, 1: Sources & Filters
            var selectedLanguageFilter by remember { mutableStateOf("All") }
            var selectedGenreFilter by remember { mutableStateOf("All") }
            var selectedOrderByFilter by remember { mutableStateOf("addition_date") }
            var selectedStatusFilter by remember { mutableStateOf("all") }

            val filteredCatalogItems = remember(items, selectedLanguageFilter, selectedGenreFilter) {
                items.filter { item ->
                    val matchLang = when (selectedLanguageFilter) {
                        "All" -> true
                        "en" -> (item.language ?: "en").contains("en", ignoreCase = true)
                        "ko" -> (item.language ?: "").contains("ko", ignoreCase = true)
                        else -> true
                    }
                    val matchGenre = when (selectedGenreFilter) {
                        "All" -> true
                        else -> item.title.contains(selectedGenreFilter, ignoreCase = true) ||
                            item.downloadUrl.contains(selectedGenreFilter, ignoreCase = true)
                    }
                    matchLang && matchGenre
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = EinkPanel,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, EinkLine),
            ) {
                Row(
                    modifier = Modifier.padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .clickable { activeSubTab = 0 },
                        color = if (activeSubTab == 0) EinkSoft else EinkPanel,
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(1.dp, if (activeSubTab == 0) EinkInk else EinkLine),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "📚 Novel Catalog (${items.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (activeSubTab == 0) FontWeight.Bold else FontWeight.Medium,
                                color = if (activeSubTab == 0) EinkInk else EinkMuted,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(if (activeSubTab == 0) EinkInk else EinkPanel),
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .clickable { activeSubTab = 1 },
                        color = if (activeSubTab == 1) EinkSoft else EinkPanel,
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(1.dp, if (activeSubTab == 1) EinkInk else EinkLine),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "⚙️ Sources & Filters",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (activeSubTab == 1) FontWeight.Bold else FontWeight.Medium,
                                color = if (activeSubTab == 1) EinkInk else EinkMuted,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(if (activeSubTab == 1) EinkInk else EinkPanel),
                            )
                        }
                    }
                }
            }

            if (activeSubTab == 0) {
                // Sub-Tab 1: 📚 Novel Catalog View (Maximum Content Viewport Exposure!)
                var isQuickActionsExpanded by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = EinkSoft,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, EinkLine),
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isQuickActionsExpanded = !isQuickActionsExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "⚡ Quick Actions & Direct URL",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = EinkInk,
                                )
                                Text(
                                    text = "• 1 HP, 10,000 SHIELD...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EinkMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { isQuickActionsExpanded = !isQuickActionsExpanded }) {
                                Text(
                                    text = if (isQuickActionsExpanded) "Collapse ▲" else "Expand ▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = EinkInk,
                                )
                            }
                        }

                        if (isQuickActionsExpanded) {
                            Spacer(Modifier.height(6.dp))
                            // Quick Resume Card (.recent-read-card)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = EinkPanel,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, EinkInk),
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Quick Resume (최근 읽던 작품) 🚀",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = EinkMuted,
                                        )
                                        Text(
                                            text = "1 HP, 10,000 SHIELD, IS THAT HOW YOU PLAY A BERSERKER?",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = EinkInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            selectedNovelForDetail = com.dongholab.pagetuner.source.RemoteBookItem(
                                                identity = com.dongholab.pagetuner.source.RemoteBookIdentity(
                                                    sourceType = com.dongholab.pagetuner.source.RemoteSourceType.WebNovel,
                                                    accountId = "quick_resume",
                                                    remoteId = "qr_65434",
                                                ),
                                                title = "1 HP, 10,000 SHIELD, IS THAT HOW YOU PLAY A BERSERKER?",
                                                authors = listOf("Author Name"),
                                                format = com.dongholab.pagetuner.document.DocumentFormat.TEXT,
                                                language = "en",
                                                contentType = "text/plain",
                                                downloadUrl = "https://wtr-lab.com/en/novel/65434/1-hp-10-000-shield",
                                                coverUrl = null,
                                            )
                                        },
                                        enabled = !busy,
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = EinkInk,
                                            contentColor = EinkPanel,
                                        ),
                                        shape = RoundedCornerShape(2.dp),
                                    ) {
                                        Text("Continue 🚀", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            // Direct Web Novel URL Access Bar
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedTextField(
                                    value = catalogUrl,
                                    onValueChange = onCatalogUrlChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy,
                                    label = { Text("Direct Novel URL (e.g. https://wtr-lab.com/...) 🌐") },
                                    singleLine = true,
                                )
                                Button(
                                    onClick = {
                                        if (catalogUrl.isNotBlank()) {
                                            onSaveSourceAccount()
                                            onLoadCatalog()
                                            selectedNovelForDetail = com.dongholab.pagetuner.source.RemoteBookItem(
                                                identity = com.dongholab.pagetuner.source.RemoteBookIdentity(
                                                    sourceType = com.dongholab.pagetuner.source.RemoteSourceType.WebNovel,
                                                    accountId = "direct_url",
                                                    remoteId = "direct_1",
                                                ),
                                                title = catalogUrl.substringAfterLast("/").ifBlank { "Web Novel" },
                                                authors = listOf("WTR-Lab Author"),
                                                format = com.dongholab.pagetuner.document.DocumentFormat.TEXT,
                                                language = "en",
                                                contentType = "text/plain",
                                                downloadUrl = catalogUrl,
                                                coverUrl = null,
                                            )
                                        }
                                    },
                                    enabled = !busy && catalogUrl.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = EinkInk,
                                        contentColor = EinkPanel,
                                    ),
                                    shape = RoundedCornerShape(2.dp),
                                ) {
                                    Text("Load Novel & Open Overview 🚀", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                if (filteredCatalogItems.isEmpty()) {
                    Text(
                        text = if (items.isEmpty()) stringResource(R.string.web_catalog_empty) else "No novels match your language or genre filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    com.dongholab.pagetuner.ui.common.EinkPagingContainer(
                        items = filteredCatalogItems,
                        pageSize = 5,
                        busy = busy,
                    ) { item ->
                        RemoteBookRow(
                            item = item,
                            coverBytes = item.coverUrl?.let { coverThumbnails[it] },
                            displayMode = displayMode,
                            busy = busy,
                            onOpenDetail = { selectedNovelForDetail = item },
                            onImportItem = { bookItem -> onImportItem(bookItem) },
                        )
                    }
                }
            } else {
                // Sub-Tab 2: ⚙️ Sources & Filters View
                RemoteSourcesHeader()

                // Saved Web Novel Catalogs Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Saved Web Novel Catalog Sources (${sourceAccounts.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = EinkInk,
                    )
                    androidx.compose.material3.IconButton(
                        onClick = { showAddSourceDialog = true },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add New Source Catalog", tint = EinkInk, modifier = Modifier.size(22.dp))
                    }
                }
                if (sourceAccounts.isNotEmpty()) {
                    SourceAccountsRow(
                        sourceAccounts = sourceAccounts,
                        busy = busy,
                        onLoadSourceAccount = onLoadSourceAccount,
                        onDeleteSourceAccount = onDeleteSourceAccount,
                        onOpenCatalogPage = { account -> activeCatalogUrlPage = account.endpoint },
                    )
                }
                if (cachedCatalogs.isNotEmpty()) {
                    CachedCatalogsRow(
                        cachedCatalogs = cachedCatalogs,
                        busy = busy,
                        onLoadCachedCatalog = onLoadCachedCatalog,
                    )
                }

                // WTR-LAB OrderBy, Status & Language Filter Controls (Pure E-Ink High Contrast)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Sort Order (orderBy):",
                        style = MaterialTheme.typography.labelSmall,
                        color = EinkMuted,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        com.dongholab.pagetuner.source.WtrLabCatalogQueryParams.ORDER_BY_OPTIONS.forEach { (key, label) ->
                            val isSel = selectedOrderByFilter == key
                            androidx.compose.material3.FilterChip(
                                selected = isSel,
                                onClick = {
                                    selectedOrderByFilter = key
                                    val newUrl = com.dongholab.pagetuner.source.WtrLabCatalogQueryParams(orderBy = key, status = selectedStatusFilter).buildUrl()
                                    onCatalogUrlChange(newUrl)
                                    onLoadCatalog()
                                },
                                enabled = !busy,
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    containerColor = if (isSel) EinkInk else EinkSoft,
                                    labelColor = if (isSel) EinkPanel else EinkInk,
                                ),
                                border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSel,
                                    borderColor = EinkLine,
                                ),
                            )
                        }
                    }

                    Text(
                        text = "Language & Status Filter:",
                        style = MaterialTheme.typography.labelSmall,
                        color = EinkMuted,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("All", "en", "ko").forEach { lang ->
                            val isSel = selectedLanguageFilter == lang
                            androidx.compose.material3.FilterChip(
                                selected = isSel,
                                onClick = { selectedLanguageFilter = lang },
                                enabled = !busy,
                                label = {
                                    Text(
                                        text = when (lang) {
                                            "All" -> "All Languages"
                                            "en" -> "English (en)"
                                            "ko" -> "Korean (ko)"
                                            else -> lang
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    containerColor = if (isSel) EinkInk else EinkSoft,
                                    labelColor = if (isSel) EinkPanel else EinkInk,
                                ),
                                border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSel,
                                    borderColor = EinkLine,
                                ),
                            )
                        }
                        com.dongholab.pagetuner.source.WtrLabCatalogQueryParams.STATUS_OPTIONS.filter { it.first != "all" }.forEach { (key, label) ->
                            val isSel = selectedStatusFilter == key
                            androidx.compose.material3.FilterChip(
                                selected = isSel,
                                onClick = {
                                    selectedStatusFilter = key
                                    val newUrl = com.dongholab.pagetuner.source.WtrLabCatalogQueryParams(orderBy = selectedOrderByFilter, status = key).buildUrl()
                                    onCatalogUrlChange(newUrl)
                                    onLoadCatalog()
                                },
                                enabled = !busy,
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Text(
                        text = "Genre Filter:",
                        style = MaterialTheme.typography.labelSmall,
                        color = EinkMuted,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("All", "Fantasy", "Action", "Romance", "System").forEach { genre ->
                            androidx.compose.material3.FilterChip(
                                selected = selectedGenreFilter == genre,
                                onClick = { selectedGenreFilter = genre },
                                enabled = !busy,
                                label = { Text(genre, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
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
    onOpenCatalogPage: (RemoteSourceAccount) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.remote_source_accounts_title, sourceAccounts.size),
            style = MaterialTheme.typography.labelLarge,
            color = EinkInk,
        )
        sourceAccounts.take(6).forEach { account ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = EinkSoft,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, EinkLine),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) {
                                onLoadSourceAccount(account)
                                onOpenCatalogPage(account)
                            },
                    ) {
                        Text(
                            text = account.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = EinkInk,
                            fontWeight = FontWeight.Bold,
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                onLoadSourceAccount(account)
                                onOpenCatalogPage(account)
                            },
                            enabled = !busy,
                        ) {
                            Text("Open Catalog Page 🚀", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { onDeleteSourceAccount(account) },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.action_delete_remote_source), style = MaterialTheme.typography.labelSmall, color = EinkMuted)
                        }
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
fun RemoteBookRow(
    item: RemoteBookItem,
    coverBytes: ByteArray?,
    displayMode: DisplayMode,
    busy: Boolean,
    onOpenDetail: () -> Unit,
    onImportItem: (RemoteBookItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
        color = EinkSoft,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteCoverThumbnail(
                coverBytes = coverBytes,
                displayMode = displayMode,
                contentDescription = item.title,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !busy) { onOpenDetail() },
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    color = EinkInk,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.authors.firstOrNull() ?: "WTR-Lab"} • ${item.language ?: "en"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = EinkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.downloadUrl.contains("/novel/") || item.downloadUrl.contains("/book/")) {
                    TextButton(
                        onClick = { onOpenDetail() },
                        enabled = !busy,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("Detail 📂", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    onClick = { onImportItem(item) },
                    enabled = !busy,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("Import 📥", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun RemoteCoverThumbnail(
    coverBytes: ByteArray?,
    displayMode: DisplayMode,
    contentDescription: String,
) {
    val bitmap = remember(coverBytes, displayMode) {
        coverBytes?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.copy(Bitmap.Config.ARGB_8888, true)
                    ?.also { bitmap ->
                        bitmap.applyDisplayMode(displayMode)
                    }
            }.getOrNull()
        }
    }

    Surface(
        modifier = Modifier
            .width(44.dp)
            .height(62.dp),
        color = if (bitmap == null) EinkSoft else EinkPanel,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        if (bitmap == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Book,
                    contentDescription = contentDescription,
                    tint = EinkInk,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(1.dp),
                contentScale = ContentScale.Crop,
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
