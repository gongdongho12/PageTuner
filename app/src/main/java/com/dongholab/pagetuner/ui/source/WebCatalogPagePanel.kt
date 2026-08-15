package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.CatalogItemTranslation
import com.dongholab.pagetuner.source.CatalogTranslationProgress
import com.dongholab.pagetuner.source.RemoteCatalogPagingState
import com.dongholab.pagetuner.source.WebCatalogLoading
import com.dongholab.pagetuner.source.translationKey
import com.dongholab.pagetuner.source.webnovel.WebNovelCatalogCapabilities
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.common.EinkRemoteCatalogPagerSlot
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.common.EinkStablePageContent
import com.dongholab.pagetuner.ui.common.rememberEinkPagingState

private enum class CatalogPageSection {
    Books,
    SearchAndFilters,
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WebCatalogPagePanel(
    catalogUrl: String,
    query: String,
    selectedGenreKey: String?,
    catalogCapabilities: WebNovelCatalogCapabilities,
    items: List<RemoteBookItem>,
    coverThumbnails: Map<String, ByteArray>,
    displayMode: DisplayMode,
    busy: Boolean,
    statusText: String?,
    targetLanguage: String,
    canTranslate: Boolean,
    translatedItems: Map<String, CatalogItemTranslation>,
    catalogTranslationProgress: CatalogTranslationProgress?,
    remotePaging: RemoteCatalogPagingState?,
    catalogLoading: WebCatalogLoading?,
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onOpenDetail: (RemoteBookItem) -> Unit,
    onImportItem: (RemoteBookItem) -> Unit,
    onTranslateCatalog: () -> Unit,
    onRemotePageSelected: (Int) -> Unit,
    onBackToSourceManager: () -> Unit,
) {
    var selectedLanguageFilter by rememberSaveable { mutableStateOf("All") }
    var selectedSection by rememberSaveable { mutableStateOf(CatalogPageSection.Books) }
    val viewportPagingState = rememberEinkPagingState(
        catalogUrl,
        remotePaging?.currentPage ?: 1,
        selectedLanguageFilter,
    )

    val filteredItems = remember(items, selectedLanguageFilter) {
        items.filter { item ->
            when (selectedLanguageFilter) {
                "All" -> true
                "en" -> (item.language ?: "en").contains("en", ignoreCase = true)
                "ko" -> (item.language ?: "").contains("ko", ignoreCase = true)
                else -> true
            }
        }
    }
    val booksSectionLabel = stringResource(R.string.web_catalog_section_books)
    val searchFiltersSectionLabel = stringResource(R.string.web_catalog_section_search_filters)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackToSourceManager, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = EinkInk, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("◄ Back to Sources Manager", style = MaterialTheme.typography.labelLarge, color = EinkInk, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onRefreshCatalog,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EinkInk,
                        contentColor = EinkPanel,
                    ),
                    shape = RoundedCornerShape(2.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh Page 🔄", style = MaterialTheme.typography.labelSmall)
                }
            }

            EinkSegmentedControl(
                options = CatalogPageSection.entries,
                selected = selectedSection,
                onSelect = { selectedSection = it },
                enabled = !busy,
                itemHeight = 42.dp,
                label = {
                    when (it) {
                        CatalogPageSection.Books -> booksSectionLabel
                        CatalogPageSection.SearchAndFilters -> searchFiltersSectionLabel
                    }
                },
            )

            EinkStablePageContent(
                content = {
                    when (selectedSection) {
                        CatalogPageSection.Books -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.web_catalog_visible_count, filteredItems.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EinkMuted,
                                    )
                                    TextButton(
                                        onClick = onTranslateCatalog,
                                        enabled = !busy && canTranslate && filteredItems.isNotEmpty(),
                                    ) {
                                        Text(
                                            stringResource(R.string.web_catalog_translate_list, targetLanguage.uppercase()),
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                EinkRemoteCatalogPagerSlot(
                                    paging = remotePaging,
                                    busy = busy,
                                    onPageSelected = onRemotePageSelected,
                                )

                                com.dongholab.pagetuner.ui.common.EinkAutoFitPagingContainer(
                                    items = filteredItems,
                                    estimatedItemHeight = 104.dp,
                                    busy = busy,
                                    state = viewportPagingState,
                                    modifier = Modifier.weight(1f),
                                    emptyContent = {
                                        Text(
                                            text = if (items.isEmpty()) {
                                                stringResource(R.string.web_catalog_loading_items)
                                            } else {
                                                stringResource(R.string.web_catalog_no_filter_matches)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = EinkMuted,
                                        )
                                    },
                                ) { item ->
                                    RemoteBookRow(
                                        item = item,
                                        translation = translatedItems[item.translationKey()],
                                        coverBytes = item.coverUrl?.let { coverThumbnails[it] },
                                        displayMode = displayMode,
                                        busy = busy,
                                        onOpenDetail = { onOpenDetail(item) },
                                        onImportItem = onImportItem,
                                    )
                                }
                            }
                        }

                        CatalogPageSection.SearchAndFilters -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = catalogUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EinkMuted,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                WebCatalogSearchControls(
                                    query = query,
                                    genreOptions = catalogCapabilities.genreOptions,
                                    selectedGenreKey = selectedGenreKey,
                                    busy = busy,
                                    onQueryChange = onQueryChange,
                                    onGenreSelected = onGenreSelected,
                                    onSearch = {
                                        onSearch()
                                        selectedSection = CatalogPageSection.Books
                                    },
                                    onClear = {
                                        onClearSearch()
                                        selectedSection = CatalogPageSection.Books
                                    },
                                )
                                Text(
                                    stringResource(R.string.web_catalog_language_filter),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EinkMuted,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("All", "en", "ko").forEach { lang ->
                                        FilterChip(
                                            selected = selectedLanguageFilter == lang,
                                            onClick = {
                                                selectedLanguageFilter = lang
                                                selectedSection = CatalogPageSection.Books
                                            },
                                            enabled = !busy,
                                            label = {
                                                Text(
                                                    if (lang == "All") {
                                                        stringResource(R.string.web_catalog_all_languages)
                                                    } else {
                                                        lang.uppercase()
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                            }
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
        }
    }
}
