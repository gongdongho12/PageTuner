@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.applyDisplayMode
import com.dongholab.pagetuner.settings.ListLayoutMode
import com.dongholab.pagetuner.source.CachedWebCatalog
import com.dongholab.pagetuner.source.BatchDownloadProgress
import com.dongholab.pagetuner.source.CatalogItemTranslation
import com.dongholab.pagetuner.source.CatalogTranslationProgress
import com.dongholab.pagetuner.source.RemoteCatalogPagingState
import com.dongholab.pagetuner.source.WebCatalogLoading
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteCatalogRoute
import com.dongholab.pagetuner.source.RemoteBookHierarchyResolver
import com.dongholab.pagetuner.source.RoutingRemoteBookHierarchyResolver
import com.dongholab.pagetuner.source.RemoteSourceAccount
import com.dongholab.pagetuner.source.translationKey
import com.dongholab.pagetuner.source.webnovel.WebNovelCatalogCapabilities
import com.dongholab.pagetuner.ui.text.localizedName
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft
import com.dongholab.pagetuner.ui.common.EinkRemoteCatalogPagerSlot
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.common.EinkStablePageContent
import com.dongholab.pagetuner.ui.common.LocalListLayoutMode
import com.dongholab.pagetuner.ui.common.rememberEinkPagingState

@Composable
fun RemoteSourcesTodoPanel(
    catalogUrl: String,
    query: String,
    selectedGenreKey: String?,
    catalogCapabilities: WebNovelCatalogCapabilities,
    items: List<RemoteBookItem>,
    coverThumbnails: Map<String, ByteArray>,
    cachedCatalogs: List<CachedWebCatalog>,
    sourceAccounts: List<RemoteSourceAccount>,
    displayMode: DisplayMode,
    busy: Boolean,
    statusText: String,
    targetLanguage: String,
    canTranslate: Boolean,
    translatedItems: Map<String, CatalogItemTranslation>,
    catalogTranslationProgress: CatalogTranslationProgress?,
    remotePaging: RemoteCatalogPagingState?,
    catalogLoading: WebCatalogLoading?,
    batchDownloadProgress: BatchDownloadProgress?,
    route: RemoteCatalogRoute,
    onRouteChange: (RemoteCatalogRoute) -> Unit,
    onCatalogUrlChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onSearchCatalog: () -> Unit,
    onClearCatalogSearch: () -> Unit,
    onLoadCatalog: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onSaveSourceAccount: () -> Unit,
    onLoadSourceAccount: (RemoteSourceAccount) -> Unit,
    onDeleteSourceAccount: (RemoteSourceAccount) -> Unit,
    onLoadCachedCatalog: (CachedWebCatalog) -> Unit,
    onImportItem: (RemoteBookItem) -> Unit,
    onReadAndTranslateItem: (RemoteBookItem) -> Unit,
    onTranslateCatalog: () -> Unit,
    onRemoteCatalogPageSelected: (Int) -> Unit,
    onBatchDownloadChapters: (List<RemoteBookItem>) -> Unit,
    hierarchyResolver: RemoteBookHierarchyResolver = RoutingRemoteBookHierarchyResolver.default,
) {
    val pageStateHolder = rememberSaveableStateHolder()
    pageStateHolder.SaveableStateProvider(route.pageStateKey()) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            var showAddSourceDialog by rememberSaveable { mutableStateOf(false) }
            val currentBookRoute = route as? RemoteCatalogRoute.Book
            BackHandler(enabled = route.parent() != null) {
                route.parent()?.let(onRouteChange)
            }
            if (currentBookRoute != null) {
                WebNovelBookRoutePage(
                    route = currentBookRoute,
                    coverThumbnails = coverThumbnails,
                    translatedItems = translatedItems,
                    displayMode = displayMode,
                    busy = busy,
                    targetLanguage = targetLanguage,
                    canTranslate = canTranslate,
                    batchDownloadProgress = batchDownloadProgress,
                    hierarchyResolver = hierarchyResolver,
                    onBackToCatalog = {
                        route.parent()?.let(onRouteChange)
                    },
                    onReadOriginalChapter = { chapter ->
                        onImportItem(chapter)
                    },
                    onReadChapter = { chapter ->
                        onReadAndTranslateItem(chapter)
                    },
                    onBatchDownloadChapters = onBatchDownloadChapters,
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
                        onRouteChange(RemoteCatalogRoute.Catalog(url))
                    },
                )
            }

            val currentCatalogPage = (route as? RemoteCatalogRoute.Catalog)?.catalogUrl
            if (currentCatalogPage != null) {
                WebCatalogPagePanel(
                    catalogUrl = catalogUrl,
                    query = query,
                    selectedGenreKey = selectedGenreKey,
                    catalogCapabilities = catalogCapabilities,
                    items = items,
                    coverThumbnails = coverThumbnails,
                    displayMode = displayMode,
                    busy = busy,
                    statusText = statusText,
                    targetLanguage = targetLanguage,
                    canTranslate = canTranslate,
                    translatedItems = translatedItems,
                    catalogTranslationProgress = catalogTranslationProgress,
                    remotePaging = remotePaging,
                    catalogLoading = catalogLoading,
                    onQueryChange = onQueryChange,
                    onGenreSelected = onGenreSelected,
                    onSearch = onSearchCatalog,
                    onClearSearch = onClearCatalogSearch,
                    onRefreshCatalog = onRefreshCatalog,
                    onOpenDetail = { item ->
                        onRouteChange(RemoteCatalogRoute.Book(currentCatalogPage, item))
                    },
                    onImportItem = { item ->
                        onRouteChange(RemoteCatalogRoute.Book(currentCatalogPage, item))
                        onImportItem(item)
                    },
                    onTranslateCatalog = onTranslateCatalog,
                    onRemotePageSelected = onRemoteCatalogPageSelected,
                    onBackToSourceManager = { onRouteChange(RemoteCatalogRoute.SourceSystems) },
                )
                return@Column
            }

            // SourceSystems is the root route, so it opens the source chooser by default.
            var activeSubTab by rememberSaveable { mutableStateOf(1) } // 0: Catalog, 1: Sources & Filters
            var selectedLanguageFilter by rememberSaveable { mutableStateOf("All") }
            var selectedOrderByFilter by rememberSaveable { mutableStateOf("addition_date") }
            var selectedStatusFilter by rememberSaveable { mutableStateOf("all") }
            var sourceManagerSection by rememberSaveable { mutableStateOf(0) }
            var catalogFilterSection by rememberSaveable { mutableStateOf(0) }

            val filteredCatalogItems = remember(items, selectedLanguageFilter) {
                items.filter { item ->
                    when (selectedLanguageFilter) {
                        "All" -> true
                        "en" -> (item.language ?: "en").contains("en", ignoreCase = true)
                        "ko" -> (item.language ?: "").contains("ko", ignoreCase = true)
                        else -> true
                    }
                }
            }
            val rootCatalogPagingState = rememberEinkPagingState(
                catalogUrl,
                remotePaging?.currentPage ?: 1,
                selectedLanguageFilter,
            )

            var showDirectUrlDialog by rememberSaveable { mutableStateOf(false) }

            if (showDirectUrlDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showDirectUrlDialog = false },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (catalogUrl.isNotBlank()) {
                                    onSaveSourceAccount()
                                    onLoadCatalog()
                                    val directNovel = com.dongholab.pagetuner.source.RemoteBookItem(
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
                                    val parentCatalogUrl = sourceAccounts
                                        .firstOrNull { it.sourceType == directNovel.identity.sourceType }
                                        ?.endpoint
                                        ?: catalogUrl
                                    onRouteChange(RemoteCatalogRoute.Book(parentCatalogUrl, directNovel))
                                    showDirectUrlDialog = false
                                }
                            },
                            enabled = !busy && catalogUrl.isNotBlank(),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = EinkInk,
                                contentColor = EinkPanel,
                            ),
                            shape = RoundedCornerShape(2.dp),
                        ) {
                            Text("Load Novel & Open Overview 🚀", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDirectUrlDialog = false }) {
                            Text("Cancel", style = MaterialTheme.typography.labelSmall, color = EinkMuted)
                        }
                    },
                    title = {
                        Text("Direct Web Novel URL Access 🌐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = EinkInk)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Enter WTR-Lab or Web Novel URL directly:", style = MaterialTheme.typography.bodySmall, color = EinkMuted)
                            OutlinedTextField(
                                value = catalogUrl,
                                onValueChange = onCatalogUrlChange,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                label = { Text("https://wtr-lab.com/...") },
                                singleLine = true,
                            )
                        }
                    },
                    containerColor = EinkPaper,
                    shape = RoundedCornerShape(6.dp),
                )
            }

            // Top Header Bar with 1-Click Direct URL Modal Launcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EinkSegmentedControl(
                    options = listOf(0, 1),
                    selected = activeSubTab,
                    onSelect = { activeSubTab = it },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    itemHeight = 42.dp,
                    label = { tab ->
                        if (tab == 0) {
                            val countLabel = remotePaging?.totalItems?.toString()
                                ?: remotePaging?.totalPages?.let { "$it pages" }
                                ?: items.size.toString()
                            "Catalog ($countLabel)"
                        } else {
                            "Sources & Filters"
                        }
                    },
                )
                Button(
                    onClick = { showDirectUrlDialog = true },
                    enabled = !busy,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = EinkInk,
                        contentColor = EinkPanel,
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(38.dp),
                ) {
                    Text("🌐 Direct URL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            if (activeSubTab == 0) {
                EinkStablePageContent(
                    content = {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            com.dongholab.pagetuner.ui.common.AdaptiveCollection(
                                items = filteredCatalogItems,
                                estimatedPagedItemHeight = 104.dp,
                                fallbackPageSize = 3,
                                busy = busy,
                                pagingState = rootCatalogPagingState,
                                modifier = Modifier.weight(1f),
                                emptyContent = {
                                    Text(
                                        text = if (items.isEmpty()) stringResource(R.string.web_catalog_empty) else "No novels match your language filter.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EinkMuted,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                },
                            ) { item ->
                                RemoteBookRow(
                                    item = item,
                                    translation = translatedItems[item.translationKey()],
                                    coverBytes = item.coverUrl?.let { coverThumbnails[it] },
                                    displayMode = displayMode,
                                    busy = busy,
                                    onOpenDetail = {
                                        onRouteChange(RemoteCatalogRoute.Book(catalogUrl, item))
                                    },
                                    onImportItem = { bookItem ->
                                        onRouteChange(RemoteCatalogRoute.Book(catalogUrl, bookItem))
                                        onImportItem(bookItem)
                                    },
                                )
                            }
                        }
                    },
                    overlay = {
                        WebCatalogOperationIndicator(
                            loading = catalogLoading,
                            translationProgress = catalogTranslationProgress,
                            busy = busy,
                            statusText = statusText,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                )
            } else {
                // Sub-Tab 2: ⚙️ Sources & Filters View
                RemoteSourcesHeader()
                EinkSegmentedControl(
                    options = listOf(0, 1),
                    selected = sourceManagerSection,
                    onSelect = { sourceManagerSection = it },
                    enabled = !busy,
                    label = { section -> if (section == 0) "Saved sources" else "Catalog filters" },
                )

                if (sourceManagerSection == 0) {
                    // Saved Web Novel Catalogs Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Saved Web Novel Catalog Sources (${sourceAccounts.size})",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = EinkInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        androidx.compose.material3.IconButton(
                            onClick = { showAddSourceDialog = true },
                            enabled = !busy,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add New Source Catalog", tint = EinkInk, modifier = Modifier.size(22.dp))
                        }
                    }
                    if (cachedCatalogs.isNotEmpty()) {
                        CachedCatalogsRow(
                            cachedCatalogs = cachedCatalogs,
                            busy = busy,
                            onLoadCachedCatalog = { cached ->
                                onLoadCachedCatalog(cached)
                                onRouteChange(RemoteCatalogRoute.Catalog(cached.url))
                            },
                        )
                    }
                    if (sourceAccounts.isNotEmpty()) {
                        SourceAccountsRow(
                            sourceAccounts = sourceAccounts,
                            busy = busy,
                            modifier = Modifier.weight(1f),
                            onLoadSourceAccount = onLoadSourceAccount,
                            onDeleteSourceAccount = onDeleteSourceAccount,
                            onOpenCatalogPage = { account ->
                                onRouteChange(RemoteCatalogRoute.Catalog(account.endpoint))
                            },
                        )
                    }
                } else {
                    EinkRemoteCatalogPagerSlot(
                        paging = remotePaging,
                        busy = busy,
                        onPageSelected = onRemoteCatalogPageSelected,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onTranslateCatalog,
                            enabled = !busy && canTranslate && filteredCatalogItems.isNotEmpty(),
                        ) {
                            Text("Translate list → ${targetLanguage.uppercase()}", fontWeight = FontWeight.Bold)
                        }
                    }
                    EinkSegmentedControl(
                        options = if (catalogCapabilities.providerAdvancedControls) listOf(0, 1) else listOf(0),
                        selected = catalogFilterSection,
                        onSelect = { catalogFilterSection = it },
                        enabled = !busy,
                        label = { section -> if (section == 0) "Search" else "Sort & status" },
                    )

                    if (catalogFilterSection == 0 || !catalogCapabilities.providerAdvancedControls) {
                        WebCatalogSearchControls(
                            query = query,
                            genreOptions = catalogCapabilities.genreOptions,
                            selectedGenreKey = selectedGenreKey,
                            busy = busy,
                            onQueryChange = onQueryChange,
                            onGenreSelected = onGenreSelected,
                            onSearch = onSearchCatalog,
                            onClear = onClearCatalogSearch,
                        )
                    } else {
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
                                            val newUrl = com.dongholab.pagetuner.source.WtrLabCatalogQueryParams
                                                .fromUrl(catalogUrl)
                                                .copy(
                                                    orderBy = key,
                                                    status = selectedStatusFilter,
                                                    query = query,
                                                    genreId = selectedGenreKey?.toIntOrNull(),
                                                    page = 1,
                                                )
                                                .buildUrl(catalogUrl)
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
                                com.dongholab.pagetuner.source.WtrLabCatalogQueryParams.STATUS_OPTIONS.forEach { (key, label) ->
                                    val isSel = selectedStatusFilter == key
                                    androidx.compose.material3.FilterChip(
                                        selected = isSel,
                                        onClick = {
                                            selectedStatusFilter = key
                                            val newUrl = com.dongholab.pagetuner.source.WtrLabCatalogQueryParams
                                                .fromUrl(catalogUrl)
                                                .copy(
                                                    orderBy = selectedOrderByFilter,
                                                    status = key,
                                                    query = query,
                                                    genreId = selectedGenreKey?.toIntOrNull(),
                                                    page = 1,
                                                )
                                                .buildUrl(catalogUrl)
                                            onCatalogUrlChange(newUrl)
                                            onLoadCatalog()
                                        },
                                        enabled = !busy,
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }
    }
    }
}

@Composable
private fun SourceAccountsRow(
    sourceAccounts: List<RemoteSourceAccount>,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onLoadSourceAccount: (RemoteSourceAccount) -> Unit,
    onDeleteSourceAccount: (RemoteSourceAccount) -> Unit,
    onOpenCatalogPage: (RemoteSourceAccount) -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.remote_source_accounts_title, sourceAccounts.size),
            style = MaterialTheme.typography.labelLarge,
            color = EinkInk,
        )
        com.dongholab.pagetuner.ui.common.AdaptiveCollection(
            items = sourceAccounts,
            modifier = Modifier.weight(1f),
            estimatedPagedItemHeight = 112.dp,
            fallbackPageSize = 3,
            busy = busy,
        ) { account ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
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
    translation: CatalogItemTranslation? = null,
    coverBytes: ByteArray?,
    displayMode: DisplayMode,
    busy: Boolean,
    onOpenDetail: () -> Unit,
    onImportItem: (RemoteBookItem) -> Unit,
) {
    val scrollLayout = LocalListLayoutMode.current == ListLayoutMode.Scroll
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (scrollLayout) Modifier.heightIn(min = 104.dp)
                else Modifier.height(104.dp),
            ),
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
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = translation?.title ?: item.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    color = EinkInk,
                    fontWeight = FontWeight.Bold,
                    maxLines = if (scrollLayout) 5 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (translation != null) {
                        "${item.title} · ${translation.targetLanguage.uppercase()}"
                    } else {
                        "${item.authors.firstOrNull() ?: "WTR-Lab"} • ${item.language ?: "en"}"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = EinkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.downloadUrl.contains("/novel/") || item.downloadUrl.contains("/book/")) {
                        TextButton(
                            onClick = { onOpenDetail() },
                            enabled = !busy,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                        ) {
                            Text("Details", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(
                        onClick = { onImportItem(item) },
                        enabled = !busy,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 1.dp),
                    ) {
                        Text("Import", style = MaterialTheme.typography.labelSmall)
                    }
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
