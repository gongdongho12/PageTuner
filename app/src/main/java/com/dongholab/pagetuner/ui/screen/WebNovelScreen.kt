package com.dongholab.pagetuner.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.CachedWebCatalog
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteCatalogRoute
import com.dongholab.pagetuner.source.RemoteSourceAccount
import com.dongholab.pagetuner.source.WebCatalogUiState
import com.dongholab.pagetuner.ui.source.RemoteSourcesTodoPanel

@Composable
fun WebNovelScreen(
    state: WebCatalogUiState,
    route: RemoteCatalogRoute,
    onRouteChange: (RemoteCatalogRoute) -> Unit,
    displayMode: DisplayMode,
    busy: Boolean,
    statusText: String,
    targetLanguage: String,
    canTranslate: Boolean,
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
    modifier: Modifier = Modifier,
) {
    val webNovelBusy = busy || state.catalogLoading != null
    RemoteSourcesTodoPanel(
        catalogUrl = state.catalogUrl,
        query = state.query,
        selectedGenreKey = state.selectedGenreKey,
        catalogCapabilities = state.catalogCapabilities,
        items = state.visibleItems,
        coverThumbnails = state.coverThumbnails,
        cachedCatalogs = state.cachedCatalogs,
        sourceAccounts = state.sourceAccounts,
        displayMode = displayMode,
        busy = webNovelBusy,
        statusText = statusText,
        targetLanguage = targetLanguage,
        canTranslate = canTranslate,
        translatedItems = state.translatedItems,
        catalogTranslationProgress = state.catalogTranslationProgress,
        remotePaging = state.remotePaging,
        catalogLoading = state.catalogLoading,
        batchDownloadProgress = state.batchDownloadProgress,
        route = route,
        onRouteChange = onRouteChange,
        onCatalogUrlChange = onCatalogUrlChange,
        onQueryChange = onQueryChange,
        onGenreSelected = onGenreSelected,
        onSearchCatalog = onSearchCatalog,
        onClearCatalogSearch = onClearCatalogSearch,
        onLoadCatalog = onLoadCatalog,
        onRefreshCatalog = onRefreshCatalog,
        onSaveSourceAccount = onSaveSourceAccount,
        onLoadSourceAccount = onLoadSourceAccount,
        onDeleteSourceAccount = onDeleteSourceAccount,
        onLoadCachedCatalog = onLoadCachedCatalog,
        onImportItem = onImportItem,
        onReadAndTranslateItem = onReadAndTranslateItem,
        onTranslateCatalog = onTranslateCatalog,
        onRemoteCatalogPageSelected = onRemoteCatalogPageSelected,
        onBatchDownloadChapters = onBatchDownloadChapters,
    )
}
