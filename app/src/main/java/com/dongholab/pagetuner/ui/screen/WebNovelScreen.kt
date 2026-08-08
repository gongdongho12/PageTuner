package com.dongholab.pagetuner.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.CachedWebCatalog
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteSourceAccount
import com.dongholab.pagetuner.source.WebCatalogUiState
import com.dongholab.pagetuner.ui.source.RemoteSourcesTodoPanel

@Composable
fun WebNovelScreen(
    state: WebCatalogUiState,
    displayMode: DisplayMode,
    busy: Boolean,
    statusText: String,
    targetLanguage: String,
    canTranslate: Boolean,
    onCatalogUrlChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onLoadCatalog: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onSaveSourceAccount: () -> Unit,
    onLoadSourceAccount: (RemoteSourceAccount) -> Unit,
    onDeleteSourceAccount: (RemoteSourceAccount) -> Unit,
    onLoadCachedCatalog: (CachedWebCatalog) -> Unit,
    onImportItem: (RemoteBookItem) -> Unit,
    onReadAndTranslateItem: (RemoteBookItem) -> Unit,
    onTranslateCatalog: () -> Unit,
    onBatchDownloadChapters: (List<RemoteBookItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    RemoteSourcesTodoPanel(
        catalogUrl = state.catalogUrl,
        query = state.query,
        items = state.visibleItems,
        coverThumbnails = state.coverThumbnails,
        cachedCatalogs = state.cachedCatalogs,
        sourceAccounts = state.sourceAccounts,
        displayMode = displayMode,
        busy = busy,
        statusText = statusText,
        targetLanguage = targetLanguage,
        canTranslate = canTranslate,
        translatedItems = state.translatedItems,
        catalogTranslationProgress = state.catalogTranslationProgress,
        batchDownloadProgress = state.batchDownloadProgress,
        onCatalogUrlChange = onCatalogUrlChange,
        onQueryChange = onQueryChange,
        onLoadCatalog = onLoadCatalog,
        onRefreshCatalog = onRefreshCatalog,
        onSaveSourceAccount = onSaveSourceAccount,
        onLoadSourceAccount = onLoadSourceAccount,
        onDeleteSourceAccount = onDeleteSourceAccount,
        onLoadCachedCatalog = onLoadCachedCatalog,
        onImportItem = onImportItem,
        onReadAndTranslateItem = onReadAndTranslateItem,
        onTranslateCatalog = onTranslateCatalog,
        onBatchDownloadChapters = onBatchDownloadChapters,
    )
}
