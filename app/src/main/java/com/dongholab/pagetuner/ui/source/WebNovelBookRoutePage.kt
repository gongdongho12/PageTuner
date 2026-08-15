package com.dongholab.pagetuner.ui.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.source.BatchDownloadProgress
import com.dongholab.pagetuner.source.CatalogItemTranslation
import com.dongholab.pagetuner.source.RemoteBookHierarchy
import com.dongholab.pagetuner.source.RemoteBookHierarchyResolver
import com.dongholab.pagetuner.source.RemoteBookItem
import com.dongholab.pagetuner.source.RemoteCatalogRoute
import com.dongholab.pagetuner.source.translationKey

private sealed interface WebNovelBookPageLoadState {
    data class Loading(val seedBook: RemoteBookItem) : WebNovelBookPageLoadState
    data class Content(val hierarchy: RemoteBookHierarchy) : WebNovelBookPageLoadState
    data class Error(val seedBook: RemoteBookItem, val message: String) : WebNovelBookPageLoadState
}

/** Route-owned book page. Detail/chapter loading changes atomically instead of mutating UI fields. */
@Composable
fun WebNovelBookRoutePage(
    route: RemoteCatalogRoute.Book,
    coverThumbnails: Map<String, ByteArray>,
    translatedItems: Map<String, CatalogItemTranslation>,
    displayMode: DisplayMode,
    busy: Boolean,
    targetLanguage: String,
    canTranslate: Boolean,
    batchDownloadProgress: BatchDownloadProgress?,
    hierarchyResolver: RemoteBookHierarchyResolver,
    onBackToCatalog: () -> Unit,
    onReadOriginalChapter: (RemoteBookItem) -> Unit,
    onReadChapter: (RemoteBookItem) -> Unit,
    onBatchDownloadChapters: (List<RemoteBookItem>) -> Unit,
) {
    var loadState by remember(route.book.identity) {
        mutableStateOf<WebNovelBookPageLoadState>(WebNovelBookPageLoadState.Loading(route.book))
    }

    LaunchedEffect(route.book.identity) {
        loadState = WebNovelBookPageLoadState.Loading(route.book)
        loadState = runCatching { hierarchyResolver.resolve(route.book) }
            .fold(
                onSuccess = WebNovelBookPageLoadState::Content,
                onFailure = { error ->
                    WebNovelBookPageLoadState.Error(
                        seedBook = route.book,
                        message = error.message ?: "Unable to load this novel page.",
                    )
                },
            )
    }

    val rawBook = when (val current = loadState) {
        is WebNovelBookPageLoadState.Loading -> current.seedBook
        is WebNovelBookPageLoadState.Content -> current.hierarchy.book
        is WebNovelBookPageLoadState.Error -> current.seedBook
    }
    val translation = translatedItems[rawBook.translationKey()]
    val displayedBook = rawBook.copy(
        title = translation?.title ?: rawBook.title,
        description = translation?.description ?: rawBook.description,
    )
    val chapters = (loadState as? WebNovelBookPageLoadState.Content)?.hierarchy?.chapters.orEmpty()
    val loadError = (loadState as? WebNovelBookPageLoadState.Error)?.message

    WebNovelDetailPagePanel(
        novelItem = displayedBook,
        coverBytes = displayedBook.coverUrl?.let { coverThumbnails[it] },
        chapters = chapters,
        displayMode = displayMode,
        busy = busy || loadState is WebNovelBookPageLoadState.Loading,
        targetLanguage = targetLanguage,
        canTranslate = canTranslate,
        loadError = loadError,
        batchProgress = batchDownloadProgress,
        onBackToList = onBackToCatalog,
        onReadOriginalChapter = onReadOriginalChapter,
        onReadChapter = onReadChapter,
        onSaveChapterTxt = { chapter -> onBatchDownloadChapters(listOf(chapter)) },
        onBatchDownloadChapters = onBatchDownloadChapters,
    )
}
