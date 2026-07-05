package com.dongholab.pagetuner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.display.servicePalette
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.LoadedReaderDocument
import com.dongholab.pagetuner.document.PdfDocumentReader
import com.dongholab.pagetuner.document.sampleDocument
import com.dongholab.pagetuner.library.LibraryEvent
import com.dongholab.pagetuner.library.LibraryViewModel
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.library.LocalLibraryStore
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.ReaderPageMoveResult
import com.dongholab.pagetuner.reader.ReaderViewModel
import com.dongholab.pagetuner.settings.ReaderSettings
import com.dongholab.pagetuner.settings.ReaderSettingsStore
import com.dongholab.pagetuner.settings.SettingsViewModel
import com.dongholab.pagetuner.source.RemoteCatalogCache
import com.dongholab.pagetuner.source.WebCatalogEvent
import com.dongholab.pagetuner.source.WebCatalogStatus
import com.dongholab.pagetuner.source.WebCatalogViewModel
import com.dongholab.pagetuner.translation.JsonFileTranslationCache
import com.dongholab.pagetuner.translation.ProviderHealthCheck
import com.dongholab.pagetuner.translation.ProviderHealthState
import com.dongholab.pagetuner.translation.TranslationQueueState
import com.dongholab.pagetuner.translation.TranslationProviderFactory
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationRepository
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.TranslationStatus
import com.dongholab.pagetuner.translation.TranslationViewModel
import com.dongholab.pagetuner.ui.common.StatusStrip
import com.dongholab.pagetuner.ui.library.LocalLibraryPanel
import com.dongholab.pagetuner.ui.reader.DocumentDetailsDialog
import com.dongholab.pagetuner.ui.reader.ReaderHeader
import com.dongholab.pagetuner.ui.reader.ReaderPager
import com.dongholab.pagetuner.ui.reader.ReaderSurface
import com.dongholab.pagetuner.ui.settings.DisplaySettingsPanel
import com.dongholab.pagetuner.ui.settings.PageTurnSettingsPanel
import com.dongholab.pagetuner.ui.settings.ReaderPreferencesPanel
import com.dongholab.pagetuner.ui.source.RemoteSourcesTodoPanel
import com.dongholab.pagetuner.ui.text.localizedLabel
import com.dongholab.pagetuner.ui.theme.PageTurnerTheme
import com.dongholab.pagetuner.ui.theme.paperColor
import com.dongholab.pagetuner.ui.translation.TranslationControls
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PageTurnerTheme(darkTheme = false, dynamicColor = false) {
                PageTurnerApp()
            }
        }
    }
}

private data class PdfPageCacheKey(
    val sourceUri: String,
    val pageIndex: Int,
    val displayMode: DisplayMode,
)

@Composable
fun PageTurnerApp() {
    val context = LocalContext.current
    val cache = remember(context) { JsonFileTranslationCache(context) }
    val settingsStore = remember(context) { ReaderSettingsStore(context) }
    val localLibraryStore = remember(context) { LocalLibraryStore(context) }
    val remoteCatalogCache = remember(context) { RemoteCatalogCache(context) }
    val initialDocument = remember(context) { context.sampleDocument() }
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(settingsStore),
    )
    val readerViewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModel.Factory(initialDocument),
    )
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.Factory(localLibraryStore),
    )
    val webCatalogViewModel: WebCatalogViewModel = viewModel(
        factory = WebCatalogViewModel.Factory(remoteCatalogCache),
    )
    val translationViewModel: TranslationViewModel = viewModel()
    val readerSettings by settingsViewModel.settings.collectAsState(initial = ReaderSettings())
    val readerState by readerViewModel.uiState.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    val webCatalogState by webCatalogViewModel.uiState.collectAsState()
    val translationState by translationViewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val initialStatus = stringResource(R.string.status_ready)

    var pdfPageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPageCache by remember { mutableStateOf<Map<PdfPageCacheKey, Bitmap>>(emptyMap()) }
    var appStatusText by rememberSaveable(initialStatus) { mutableStateOf(initialStatus) }

    val localBooks = libraryState.books
    val document = readerState.document
    val pageIndex = readerState.safePageIndex
    val currentPage = readerState.currentPage
    val pdfSourceUri = readerState.pdfSourceUri
    val currentBookId = readerState.currentBookId
    val controlsVisible = readerState.controlsVisible
    val showDocumentDetails = readerState.showDocumentDetails
    val providerKind = readerSettings.providerKind
    val paceMode = readerSettings.paceMode
    val pageTurnMode = readerSettings.pageTurnMode
    val displayMode = readerSettings.displayMode
    val paperColor = displayMode.servicePalette().paperColor()
    val sourceLanguage = readerSettings.sourceLanguage
    val targetLanguage = readerSettings.targetLanguage
    val apiKey = translationState.apiKey
    val translation = translationState.translation
    val translationCacheStatus = translationState.cacheStatus
    val busy = libraryState.busy || translationState.busy || webCatalogState.busy
    val progress = translationState.progress
    val providerHealthText = translationState.providerHealth.localizedMessage(context)
    val translationQueueStatusText = translationState.queue.localizedMessage(context)
    val webCatalogStatusText = webCatalogState.status.localizedMessage(context)
    val statusText = when (val status = translationState.status) {
        TranslationStatus.Ready -> appStatusText
        else -> status.localizedMessage(context)
    }
    val tableOfContents = document.tableOfContents
    val currentChapterIndex = tableOfContents.indexOfLast { outline ->
        outline.pageIndex <= currentPage.index
    }
    val currentChapterTitle = tableOfContents.getOrNull(currentChapterIndex)?.title
        ?: currentPage.chapterTitle
    val canPreviousChapter = currentChapterIndex > 0
    val canNextChapter = when {
        tableOfContents.isEmpty() -> false
        currentChapterIndex == -1 -> true
        else -> currentChapterIndex < tableOfContents.lastIndex
    }
    val currentBook = localBooks.firstOrNull { it.id == currentBookId }
    val providerStatusText = when {
        settingsProviderConfigured(
            providerKind = providerKind,
            apiKey = apiKey,
            llmEndpoint = readerSettings.llmEndpoint,
            llmModel = readerSettings.llmModel,
        ) -> stringResource(R.string.provider_status_ready)
        providerKind == TranslationProviderKind.GOOGLE_CLOUD ->
            stringResource(R.string.provider_status_missing_google_key)
        else -> stringResource(R.string.provider_status_missing_llm_settings)
    }
    val translationCacheStatusText = translationCacheStatus?.let { cacheStatus ->
        if (cacheStatus.totalSegments == 0) {
            stringResource(R.string.translation_cache_status_empty)
        } else {
            stringResource(
                R.string.translation_cache_status,
                cacheStatus.cachedSegments,
                cacheStatus.totalSegments,
            )
        }
    } ?: stringResource(R.string.translation_cache_status_empty)
    val settings = TranslationSettings(
        providerKind = providerKind,
        apiKey = apiKey,
        llmEndpoint = readerSettings.llmEndpoint,
        llmModel = readerSettings.llmModel,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        readingWordsPerMinute = readerSettings.readingWordsPerMinute,
        paceMode = paceMode,
    )
    val repository = remember(settings, cache) {
        TranslationRepository(
            provider = TranslationProviderFactory.create(settings),
            cache = cache,
        )
    }

    fun applyLoadedDocument(
        loaded: LoadedReaderDocument,
        localBook: LocalBook?,
        requestedPageIndex: Int,
    ) {
        readerViewModel.applyLoadedDocument(
            loaded = loaded,
            localBookId = localBook?.id,
            requestedPageIndex = requestedPageIndex,
        )
        pdfPageBitmap = null
        pdfPageCache = emptyMap()
        translationViewModel.resetForDocument()
    }

    fun openLocalBook(book: LocalBook) {
        if (busy) return
        translationViewModel.clearStatus()
        appStatusText = context.getString(R.string.status_opening_document)
        libraryViewModel.openBook(book)
    }

    fun deleteLocalBook(book: LocalBook) {
        if (busy) return
        translationViewModel.clearStatus()
        libraryViewModel.deleteBook(
            book = book,
            wasCurrentBook = currentBookId == book.id,
        )
    }

    fun changePage(targetIndex: Int) {
        when (readerViewModel.changePage(targetIndex)) {
            ReaderPageMoveResult.Moved -> {
                translationViewModel.clearPageTranslation()
            }
            ReaderPageMoveResult.FirstPage -> {
                translationViewModel.clearStatus()
                appStatusText = context.getString(R.string.status_first_page)
            }
            ReaderPageMoveResult.LastPage -> {
                translationViewModel.clearStatus()
                appStatusText = context.getString(R.string.status_last_page)
            }
        }
    }

    fun previousPage() {
        changePage(pageIndex - 1)
    }

    fun nextPage() {
        changePage(pageIndex + 1)
    }

    fun requestManualRefresh() {
        readerViewModel.requestManualRefresh()
        pdfPageBitmap = null
        pdfPageCache = emptyMap()
        translationViewModel.clearStatus()
        appStatusText = context.getString(R.string.status_manual_refresh_requested)
    }

    fun previousChapter() {
        if (!canPreviousChapter) {
            translationViewModel.clearStatus()
            appStatusText = context.getString(R.string.status_first_chapter)
            return
        }
        changePage(tableOfContents[currentChapterIndex - 1].pageIndex)
    }

    fun nextChapter() {
        val nextChapterIndex = when {
            tableOfContents.isEmpty() -> null
            currentChapterIndex == -1 -> 0
            currentChapterIndex < tableOfContents.lastIndex -> currentChapterIndex + 1
            else -> null
        }

        if (nextChapterIndex == null) {
            translationViewModel.clearStatus()
            appStatusText = context.getString(R.string.status_last_chapter)
            return
        }

        changePage(tableOfContents[nextChapterIndex].pageIndex)
    }

    fun handleReaderKey(key: Key): Boolean {
        if (busy) return false
        return when (key) {
            Key.DirectionLeft,
            Key.PageUp -> {
                previousPage()
                true
            }
            Key.DirectionRight,
            Key.PageDown,
            Key.Spacebar -> {
                nextPage()
                true
            }
            else -> false
        }
    }

    fun loadCachedCurrentPage() {
        translationViewModel.loadCachedPage(
            document = document,
            page = currentPage,
            settings = settings,
            repository = repository,
            showMissingStatus = true,
        )
    }

    fun translateCurrentPage() {
        if (busy) return
        translationViewModel.translatePage(
            document = document,
            page = currentPage,
            settings = settings,
            repository = repository,
        )
    }

    fun prefetchDocument() {
        if (busy) return
        translationViewModel.prefetchDocument(
            document = document,
            currentPage = currentPage,
            startPageIndex = pageIndex,
            settings = settings,
            repository = repository,
        )
    }

    fun clearTranslationCache() {
        if (busy) return
        translationViewModel.clearTranslationCache(
            document = document,
            settings = settings,
            repository = repository,
        )
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        translationViewModel.clearStatus()
        appStatusText = context.getString(R.string.status_opening_document)
        libraryViewModel.importBook(uri)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(libraryViewModel) {
        launch {
            libraryViewModel.events.collect { event ->
                translationViewModel.clearStatus()
                when (event) {
                    is LibraryEvent.OpenedLocalBook -> {
                        applyLoadedDocument(
                            loaded = event.result.loadedDocument,
                            localBook = event.result.book,
                            requestedPageIndex = event.result.book.safeCurrentPageIndex,
                        )
                        appStatusText = context.getString(
                            R.string.status_opened_local_book,
                            event.result.book.title,
                        )
                    }
                    is LibraryEvent.ImportedBook -> {
                        applyLoadedDocument(
                            loaded = event.result.loadedDocument,
                            localBook = event.result.book,
                            requestedPageIndex = event.result.book.safeCurrentPageIndex,
                        )
                        appStatusText = if (event.result.wasDuplicateImport) {
                            context.getString(
                                R.string.status_duplicate_book,
                                event.result.book.title,
                            )
                        } else {
                            context.getString(
                                R.string.status_imported_book,
                                event.result.book.title,
                            )
                        }
                    }
                    is LibraryEvent.DeletedBook -> {
                        if (event.wasCurrentBook) {
                            readerViewModel.resetDocument(context.sampleDocument())
                            pdfPageBitmap = null
                            pdfPageCache = emptyMap()
                            translationViewModel.resetForDocument()
                        }
                        appStatusText = context.getString(
                            R.string.status_deleted_book,
                            event.book.title,
                        )
                    }
                    is LibraryEvent.Error -> {
                        appStatusText = context.readableMessage(event.detail)
                    }
                }
            }
        }
        libraryViewModel.loadInitialLibrary()
    }

    LaunchedEffect(webCatalogViewModel) {
        launch {
            webCatalogViewModel.events.collect { event ->
                when (event) {
                    is WebCatalogEvent.ImportDownloaded -> {
                        translationViewModel.clearStatus()
                        appStatusText = context.getString(
                            R.string.status_web_catalog_downloaded,
                            event.item.title,
                        )
                        libraryViewModel.importRemoteBook(event.item, event.bytes)
                    }
                }
            }
        }
    }

    LaunchedEffect(currentBookId, pageIndex, document.id) {
        val bookId = currentBookId ?: return@LaunchedEffect
        libraryViewModel.updateProgress(bookId, pageIndex)
    }

    LaunchedEffect(document.id, settings, repository) {
        translationViewModel.refreshCacheStatus(document, settings, repository)
    }

    LaunchedEffect(document.id, pageIndex, pdfSourceUri, displayMode, readerState.manualRefreshToken) {
        pdfPageBitmap = null
        val source = pdfSourceUri
        if (document.format == DocumentFormat.PDF && source != null) {
            val currentKey = PdfPageCacheKey(source, pageIndex, displayMode)
            pdfPageBitmap = pdfPageCache[currentKey]

            runCatching {
                withContext(Dispatchers.IO) {
                    val targetPages = (pageIndex - 1..pageIndex + 1)
                        .filter { it in 0 until document.pageCount }

                    targetPages.associate { targetPage ->
                        val key = PdfPageCacheKey(source, targetPage, displayMode)
                        key to (pdfPageCache[key] ?: PdfDocumentReader.renderPage(
                            context = context,
                            uri = Uri.parse(source),
                            pageIndex = targetPage,
                            displayMode = displayMode,
                        ))
                    }
                }
            }.onSuccess { renderedPages ->
                pdfPageCache = (pdfPageCache + renderedPages)
                    .filterKeys { key ->
                        key.sourceUri == source &&
                            key.displayMode == displayMode &&
                            abs(key.pageIndex - pageIndex) <= 1
                    }
                pdfPageBitmap = renderedPages[currentKey] ?: pdfPageCache[currentKey]
            }.onFailure { error ->
                translationViewModel.clearStatus()
                appStatusText = error.readableMessage(context)
            }
        }
    }

    LaunchedEffect(document.id, pageIndex, settings, repository) {
        translationViewModel.loadCachedPage(
            document = document,
            page = currentPage,
            settings = settings,
            repository = repository,
            showMissingStatus = false,
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && handleReaderKey(event.key)
            },
        containerColor = paperColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(paperColor)
                .padding(innerPadding)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReaderHeader(
                document = document,
                page = currentPage,
                controlsVisible = controlsVisible,
                onOpen = {
                    openDocumentLauncher.launch(
                        arrayOf(
                            "text/*",
                            "text/markdown",
                            "application/pdf",
                            "application/epub+zip",
                            "application/octet-stream",
                        ),
                    )
                },
                onToggleControls = readerViewModel::toggleControls,
                onManualRefresh = ::requestManualRefresh,
                onShowDetails = readerViewModel::showDocumentDetails,
            )
            if (controlsVisible) {
                LocalLibraryPanel(
                    books = localBooks,
                    currentBookId = currentBookId,
                    busy = busy,
                    onOpenBook = ::openLocalBook,
                    onDeleteBook = ::deleteLocalBook,
                )
            }
            ReaderPager(
                pageIndex = pageIndex,
                pageCount = document.pageCount,
                busy = busy,
                currentChapterTitle = currentChapterTitle,
                canPreviousChapter = canPreviousChapter,
                canNextChapter = canNextChapter,
                onPrevious = ::previousPage,
                onNext = ::nextPage,
                onPreviousChapter = ::previousChapter,
                onNextChapter = ::nextChapter,
            )
            ReaderSurface(
                page = currentPage,
                documentFormat = document.format,
                pdfPageBitmap = pdfPageBitmap,
                pdfFitMode = readerSettings.pdfFitMode,
                displayMode = displayMode,
                translation = translation,
                translationDisplayMode = readerSettings.translationDisplayMode,
                pageTurnMode = pageTurnMode,
                pageTurningEnabled = !busy,
                fontSizeSp = readerSettings.readerFontSizeSp,
                lineSpacing = readerSettings.readerLineSpacing,
                pageMarginDp = readerSettings.readerPageMarginDp,
                onPreviousPage = ::previousPage,
                onNextPage = ::nextPage,
                modifier = Modifier.weight(1f),
            )
            if (controlsVisible) {
                DisplaySettingsPanel(
                    displayMode = displayMode,
                    busy = busy,
                    onDisplayModeChange = {
                        pdfPageBitmap = null
                        pdfPageCache = emptyMap()
                        settingsViewModel.updateDisplayMode(it)
                    },
                )
                PageTurnSettingsPanel(
                    pageTurnMode = pageTurnMode,
                    busy = busy,
                    onPageTurnModeChange = settingsViewModel::updatePageTurnMode,
                )
                ReaderPreferencesPanel(
                    pdfFitMode = readerSettings.pdfFitMode,
                    fontSizeSp = readerSettings.readerFontSizeSp,
                    lineSpacing = readerSettings.readerLineSpacing,
                    pageMarginDp = readerSettings.readerPageMarginDp,
                    busy = busy,
                    onPdfFitModeChange = settingsViewModel::updatePdfFitMode,
                    onFontSizeChange = settingsViewModel::updateReaderFontSize,
                    onLineSpacingChange = settingsViewModel::updateReaderLineSpacing,
                    onPageMarginChange = settingsViewModel::updateReaderPageMargin,
                )
                TranslationControls(
                    providerKind = providerKind,
                    onProviderKindChange = settingsViewModel::updateProviderKind,
                    apiKey = apiKey,
                    onApiKeyChange = translationViewModel::updateApiKey,
                    llmEndpoint = readerSettings.llmEndpoint,
                    onLlmEndpointChange = settingsViewModel::updateLlmEndpoint,
                    llmModel = readerSettings.llmModel,
                    onLlmModelChange = settingsViewModel::updateLlmModel,
                    sourceLanguage = sourceLanguage,
                    onSourceLanguageChange = settingsViewModel::updateSourceLanguage,
                    targetLanguage = targetLanguage,
                    onTargetLanguageChange = settingsViewModel::updateTargetLanguage,
                    readingWpm = readerSettings.readingWordsPerMinute.toFloat(),
                    onReadingWpmChange = {
                        settingsViewModel.updateReadingWordsPerMinute(it.roundToInt())
                    },
                    paceMode = paceMode,
                    onPaceModeChange = settingsViewModel::updatePaceMode,
                    translationDisplayMode = readerSettings.translationDisplayMode,
                    onTranslationDisplayModeChange = settingsViewModel::updateTranslationDisplayMode,
                    providerStatusText = providerStatusText,
                    providerHealthText = providerHealthText,
                    translationCacheStatusText = translationCacheStatusText,
                    translationQueueStatusText = translationQueueStatusText,
                    busy = busy,
                    canTranslate = settings.isProviderConfigured && currentPage.hasText,
                    canClearCache = (translationCacheStatus?.cachedSegments ?: 0) > 0,
                    canPausePrefetch = translationState.queue.canPause,
                    canResumePrefetch = translationState.queue.canResume,
                    canCancelPrefetch = translationState.queue.canCancel,
                    canRetryPrefetch = translationState.queue.canRetry,
                    onLanguagePreset = { preset ->
                        settingsViewModel.updateLanguages(
                            sourceLanguage = preset.sourceLanguage,
                            targetLanguage = preset.targetLanguage,
                        )
                    },
                    onCheckProvider = { translationViewModel.checkProviderHealth(settings) },
                    onTranslate = ::translateCurrentPage,
                    onPrefetch = ::prefetchDocument,
                    onPausePrefetch = translationViewModel::pausePrefetch,
                    onResumePrefetch = translationViewModel::resumePrefetch,
                    onCancelPrefetch = translationViewModel::cancelPrefetch,
                    onRetryPrefetch = {
                        translationViewModel.retryFailedPrefetch(
                            document = document,
                            currentPage = currentPage,
                            settings = settings,
                            repository = repository,
                        )
                    },
                    onLoadCached = ::loadCachedCurrentPage,
                    onClearCache = ::clearTranslationCache,
                )
                RemoteSourcesTodoPanel(
                    catalogUrl = webCatalogState.catalogUrl,
                    query = webCatalogState.query,
                    items = webCatalogState.visibleItems,
                    cachedCatalogs = webCatalogState.cachedCatalogs,
                    busy = busy,
                    statusText = webCatalogStatusText,
                    onCatalogUrlChange = webCatalogViewModel::updateCatalogUrl,
                    onQueryChange = webCatalogViewModel::updateQuery,
                    onLoadCatalog = webCatalogViewModel::loadCatalog,
                    onRefreshCatalog = webCatalogViewModel::refreshCatalog,
                    onLoadCachedCatalog = webCatalogViewModel::loadCachedCatalog,
                    onImportItem = webCatalogViewModel::importItem,
                )
                StatusStrip(
                    statusText = statusText,
                    progress = progress,
                    busy = busy,
                )
            }
        }
    }

    if (showDocumentDetails) {
        DocumentDetailsDialog(
            document = document,
            currentBook = currentBook,
            pageIndex = pageIndex,
            onDismiss = readerViewModel::hideDocumentDetails,
        )
    }
}

private fun TranslationStatus.localizedMessage(context: Context): String {
    return when (this) {
        TranslationStatus.Ready -> context.getString(R.string.status_ready)
        TranslationStatus.LoadedCached -> context.getString(R.string.status_loaded_cached)
        TranslationStatus.NoCached -> context.getString(R.string.status_no_cached)
        TranslationStatus.ServedFromCache -> context.getString(R.string.status_served_from_cache)
        TranslationStatus.PreparingOfflineCache -> context.getString(R.string.status_preparing_offline_cache)
        TranslationStatus.OfflineCacheReady -> context.getString(R.string.status_offline_cache_ready)
        TranslationStatus.PrefetchPaused -> context.getString(R.string.status_prefetch_paused)
        TranslationStatus.PrefetchCancelled -> context.getString(R.string.status_prefetch_cancelled)
        is TranslationStatus.Starting -> context.getString(
            R.string.status_starting_translation,
            paceMode.localizedLabel(context).lowercase(),
        )
        is TranslationStatus.CachedSegments -> context.getString(
            R.string.status_cached_segments,
            cachedSegments,
            totalSegments,
        )
        is TranslationStatus.TranslatedSegments -> context.getString(
            R.string.status_translated_segments,
            completedSegments,
            totalSegments,
        )
        is TranslationStatus.TranslatedSavedPage -> context.getString(
            R.string.status_translated_saved_page,
            pageNumber,
        )
        is TranslationStatus.PrefetchPreparingPage -> context.getString(
            R.string.status_prefetch_preparing_page,
            activePageNumber,
            totalPages,
        )
        is TranslationStatus.PrefetchSavedPage -> context.getString(
            R.string.status_prefetch_saved_page,
            activePageNumber,
            totalPages,
        )
        is TranslationStatus.PrefetchFailedPage -> context.getString(
            R.string.status_prefetch_failed_page,
            pageNumber,
            detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.status_generic_error),
        )
        is TranslationStatus.PrefetchCompletedWithFailures -> context.getString(
            R.string.status_prefetch_completed_with_failures,
            failedPages,
            totalPages,
        )
        is TranslationStatus.RetryingPage -> context.getString(
            R.string.status_retrying_translation_page,
            pageNumber,
            attemptNumber,
        )
        is TranslationStatus.ClearedCache -> context.getString(
            R.string.status_cleared_translation_cache,
            deletedSegments,
        )
        is TranslationStatus.Error -> context.getString(
            R.string.status_translation_error,
            detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.status_generic_error),
        )
    }
}

private fun ProviderHealthCheck.localizedMessage(context: Context): String {
    return when (state) {
        ProviderHealthState.NotChecked -> context.getString(R.string.provider_health_not_checked)
        ProviderHealthState.Ready -> context.getString(R.string.provider_health_ready)
        ProviderHealthState.MissingConfiguration -> when (providerKind) {
            TranslationProviderKind.GOOGLE_CLOUD ->
                context.getString(R.string.provider_health_missing_google_key)
            TranslationProviderKind.OPENAI_COMPATIBLE_LLM ->
                context.getString(R.string.provider_health_missing_llm_settings)
            null -> context.getString(R.string.provider_health_missing_settings)
        }
        ProviderHealthState.InvalidConfiguration ->
            context.getString(R.string.provider_health_invalid_llm_endpoint)
    }
}

private fun WebCatalogStatus.localizedMessage(context: Context): String {
    return when (this) {
        WebCatalogStatus.Idle -> context.getString(R.string.status_web_catalog_idle)
        WebCatalogStatus.Loading -> context.getString(R.string.status_web_catalog_loading)
        WebCatalogStatus.MissingCatalogUrl ->
            context.getString(R.string.status_web_catalog_missing_url)
        is WebCatalogStatus.LoadedRemote -> context.getString(
            R.string.status_web_catalog_loaded_remote,
            title,
            itemCount,
        )
        is WebCatalogStatus.LoadedCached -> context.getString(
            R.string.status_web_catalog_loaded_cached,
            title,
            itemCount,
        )
        is WebCatalogStatus.Importing -> context.getString(
            R.string.status_web_catalog_importing,
            title,
        )
        is WebCatalogStatus.Downloaded -> context.getString(
            R.string.status_web_catalog_downloaded,
            title,
        )
        is WebCatalogStatus.Error -> context.getString(
            R.string.status_web_catalog_error,
            detail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.status_generic_error),
        )
    }
}

private fun TranslationQueueState.localizedMessage(context: Context): String {
    return when {
        running && paused -> context.getString(
            R.string.translation_queue_paused,
            completedPages,
            totalPages,
            activePageNumber ?: 0,
        )
        running -> context.getString(
            R.string.translation_queue_running,
            completedPages,
            totalPages,
            activePageNumber ?: 0,
        )
        cancelled -> context.getString(R.string.translation_queue_cancelled)
        failedPages > 0 -> context.getString(
            R.string.translation_queue_failed,
            failedPages,
            totalPages,
        )
        totalPages > 0 && completedPages == totalPages -> context.getString(
            R.string.translation_queue_complete,
            completedPages,
        )
        else -> context.getString(R.string.translation_queue_idle)
    }
}

private fun Throwable.readableMessage(context: Context): String {
    return context.readableMessage(message)
}

private fun Context.readableMessage(detail: String?): String {
    val safeDetail = detail?.takeIf { it.isNotBlank() }
        ?: getString(R.string.status_generic_error)
    return getString(R.string.status_translation_error, safeDetail)
}

private fun settingsProviderConfigured(
    providerKind: TranslationProviderKind,
    apiKey: String,
    llmEndpoint: String,
    llmModel: String,
): Boolean {
    return when (providerKind) {
        TranslationProviderKind.GOOGLE_CLOUD -> apiKey.isNotBlank()
        TranslationProviderKind.OPENAI_COMPATIBLE_LLM ->
            apiKey.isNotBlank() && llmEndpoint.isNotBlank() && llmModel.isNotBlank()
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
fun PageTurnerPreview() {
    PageTurnerTheme(darkTheme = false, dynamicColor = false) {
        PageTurnerApp()
    }
}
