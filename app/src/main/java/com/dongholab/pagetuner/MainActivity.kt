package com.dongholab.pagetuner

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dongholab.pagetuner.display.servicePalette
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.LoadedReaderDocument
import com.dongholab.pagetuner.document.PdfDocumentReader
import com.dongholab.pagetuner.document.sampleDocument
import com.dongholab.pagetuner.library.LibraryEvent
import com.dongholab.pagetuner.library.LibraryViewModel
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.library.LocalLibraryStore
import com.dongholab.pagetuner.library.toLocalBookAnnotation
import com.dongholab.pagetuner.library.toLocalBookBookmark
import com.dongholab.pagetuner.library.toReaderAnnotation
import com.dongholab.pagetuner.library.toReaderBookmark
import com.dongholab.pagetuner.reader.ReaderViewModel
import com.dongholab.pagetuner.settings.ReaderSettings
import com.dongholab.pagetuner.settings.ReaderSettingsStore
import com.dongholab.pagetuner.settings.SettingsViewModel
import com.dongholab.pagetuner.source.RemoteCatalogCache
import com.dongholab.pagetuner.source.RemoteSourceAccountStore
import com.dongholab.pagetuner.source.WebCatalogEvent
import com.dongholab.pagetuner.source.WebCatalogViewModel
import com.dongholab.pagetuner.source.WebNovelPageRuntime
import com.dongholab.pagetuner.translation.JsonFileTranslationCache
import com.dongholab.pagetuner.translation.TranslationProviderFactory
import com.dongholab.pagetuner.translation.TranslationRepository
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.TranslationStatus
import com.dongholab.pagetuner.translation.TranslationViewModel
import com.dongholab.pagetuner.ui.LanguagePreset
import com.dongholab.pagetuner.ui.common.AppTab
import com.dongholab.pagetuner.ui.common.AppTabNavigation
import com.dongholab.pagetuner.ui.common.ComingSoonPanel
import com.dongholab.pagetuner.ui.common.StatusStrip
import com.dongholab.pagetuner.ui.reader.DocumentDetailsDialog
import com.dongholab.pagetuner.ui.reader.ReaderAnnotationPanel
import com.dongholab.pagetuner.ui.reader.ReaderBookmarkPanel
import com.dongholab.pagetuner.ui.reader.ReaderHeader
import com.dongholab.pagetuner.ui.reader.ReaderPager
import com.dongholab.pagetuner.ui.reader.ReaderSearchPanel
import com.dongholab.pagetuner.ui.reader.ReaderSurface
import com.dongholab.pagetuner.ui.screen.FavoritesScreen
import com.dongholab.pagetuner.ui.screen.LocalScreen
import com.dongholab.pagetuner.ui.screen.ReaderActions
import com.dongholab.pagetuner.ui.screen.SettingsScreen
import com.dongholab.pagetuner.ui.screen.WebNovelScreen
import com.dongholab.pagetuner.ui.screen.buildReaderActions
import com.dongholab.pagetuner.ui.text.localizedMessage
import com.dongholab.pagetuner.ui.text.readableMessage
import com.dongholab.pagetuner.ui.text.settingsProviderConfigured
import com.dongholab.pagetuner.ui.theme.PageTurnerTheme
import com.dongholab.pagetuner.ui.theme.paperColor
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

// ─────────────────────────────────────────────
// Cache key for PDF page rendering
// ─────────────────────────────────────────────
private data class PdfPageCacheKey(
    val sourceUri: String,
    val pageIndex: Int,
    val displayMode: com.dongholab.pagetuner.display.DisplayMode,
)

// Navigation History Frame for E-Ink Back Button Navigation
private sealed interface NavigationHistoryFrame {
    data class TabFrame(val tab: AppTab) : NavigationHistoryFrame
    data class ReaderSubPageFrame(val subPage: com.dongholab.pagetuner.ui.reader.ReaderSubPage) : NavigationHistoryFrame
    data class PageJumpFrame(val pageIndex: Int) : NavigationHistoryFrame
    data class ControlsVisibilityFrame(val visible: Boolean) : NavigationHistoryFrame
}

// ─────────────────────────────────────────────
// Root Composable — ViewModels + state setup only
// ─────────────────────────────────────────────
@Composable
fun PageTurnerApp() {
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(context) { WebNovelPageRuntime.install(context) }

    // — Stores (singleton per context)
    val settingsStore = remember(context) { ReaderSettingsStore(context) }
    val localLibraryStore = remember(context) { LocalLibraryStore(context) }
    val remoteCatalogCache = remember(context) { RemoteCatalogCache(context) }
    val remoteSourceAccountStore = remember(context) { RemoteSourceAccountStore(context) }
    val favoriteStore = remember(context) {
        com.dongholab.pagetuner.source.WebNovelFavoriteStore(context.filesDir.resolve("favorites.json"))
    }

    // — ViewModels
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(settingsStore))
    val readerViewModel: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory(context.sampleDocument()))
    val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(localLibraryStore))
    val webCatalogViewModel: WebCatalogViewModel = viewModel(
        factory = WebCatalogViewModel.Factory(cache = remoteCatalogCache, accountStore = remoteSourceAccountStore),
    )
    val translationViewModel: TranslationViewModel = viewModel()

    // — State observation
    val readerSettings by settingsViewModel.settings.collectAsState(initial = ReaderSettings())
    val readerState by readerViewModel.uiState.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    val webCatalogState by webCatalogViewModel.uiState.collectAsState()
    val translationState by translationViewModel.uiState.collectAsState()

    // — UI state
    val focusRequester = remember { FocusRequester() }
    val initialStatus = stringResource(R.string.status_ready)
    var isSplashing by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(AppTab.Local) }
    var readerSubPage by remember { mutableStateOf(com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER) }
    val navHistoryStack = remember { mutableStateListOf<NavigationHistoryFrame>() }
    var favoritesList by remember { mutableStateOf(favoriteStore.listFavorites()) }
    var appStatusText by rememberSaveable(initialStatus) { mutableStateOf(initialStatus) }
    var appErrorText by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfPageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPageCache by remember { mutableStateOf<Map<PdfPageCacheKey, Bitmap>>(emptyMap()) }
    var pendingReadAndTranslate by remember { mutableStateOf(false) }
    var pendingTranslationDocumentId by remember { mutableStateOf<String?>(null) }

    // — Derived reader state
    val localBooks = libraryState.books
    val document = readerState.document
    val pageIndex = readerState.safePageIndex
    val currentPage = readerState.currentPage
    val pdfSourceUri = readerState.pdfSourceUri
    val currentBookId = readerState.currentBookId
    val currentBook = localBooks.firstOrNull { it.id == currentBookId }
    val controlsVisible = readerState.controlsVisible
    val showDocumentDetails = readerState.showDocumentDetails
    val bookmarks = readerState.bookmarks
    val annotations = readerState.annotations

    // Back button history restoration handler
    BackHandler(enabled = navHistoryStack.isNotEmpty()) {
        val lastFrame = navHistoryStack.removeLastOrNull() ?: return@BackHandler
        when (lastFrame) {
            is NavigationHistoryFrame.TabFrame -> {
                selectedTab = lastFrame.tab
            }
            is NavigationHistoryFrame.ReaderSubPageFrame -> {
                readerSubPage = lastFrame.subPage
            }
            is NavigationHistoryFrame.PageJumpFrame -> {
                readerViewModel.changePage(lastFrame.pageIndex)
            }
            is NavigationHistoryFrame.ControlsVisibilityFrame -> {
                if (readerState.controlsVisible != lastFrame.visible) {
                    readerViewModel.toggleControls()
                }
            }
        }
    }

    // — Derived settings state
    val displayMode = readerSettings.displayMode
    val paperColor = displayMode.servicePalette().paperColor()
    val providerKind = readerSettings.providerKind
    val apiKey = translationState.apiKey
    val busy = libraryState.busy || translationState.busy || webCatalogState.busy
    val progress = translationState.progress

    // — Translation derived state
    val cache = remember(context, currentBook?.relativePath) {
        JsonFileTranslationCache(context, currentBook?.relativePath)
    }
    val settings = TranslationSettings(
        providerKind = providerKind,
        apiKey = apiKey,
        llmEndpoint = readerSettings.llmEndpoint,
        llmModel = readerSettings.llmModel,
        sourceLanguage = readerSettings.sourceLanguage,
        targetLanguage = readerSettings.targetLanguage,
        readingWordsPerMinute = readerSettings.readingWordsPerMinute,
        batchSize = readerSettings.translationBatchSize,
        paceMode = readerSettings.paceMode,
    )
    val repository = remember(settings, cache) {
        TranslationRepository(provider = TranslationProviderFactory.create(settings), cache = cache)
    }
    val tableOfContents = document.tableOfContents
    val currentChapterIndex = tableOfContents.indexOfLast { it.pageIndex <= currentPage.index }
    val canTranslateCurrentPage = settings.isProviderConfigured && currentPage.hasText
    val canRetryCurrentPageTranslation =
        translationState.status is TranslationStatus.Error && canTranslateCurrentPage
    val translationCacheStatus = translationState.cacheStatus
    val statusText = when (val s = translationState.status) {
        TranslationStatus.Ready -> appStatusText
        else -> s.localizedMessage(context)
    }
    val webCatalogStatusText = webCatalogState.status.localizedMessage(context)
    val providerStatusText = when {
        settingsProviderConfigured(providerKind, apiKey, readerSettings.llmEndpoint, readerSettings.llmModel) ->
            stringResource(R.string.provider_status_ready)
        providerKind == com.dongholab.pagetuner.translation.TranslationProviderKind.GOOGLE_CLOUD ->
            stringResource(R.string.provider_status_missing_google_key)
        providerKind == com.dongholab.pagetuner.translation.TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML ->
            stringResource(R.string.provider_status_google_web_no_key_required)
        else -> stringResource(R.string.provider_status_missing_llm_settings)
    }
    val providerHealthText = translationState.providerHealth.localizedMessage(context)
    val translationQueueStatusText = translationState.queue.localizedMessage(context)
    val translationCacheStatusText = translationCacheStatus?.let { cs ->
        if (cs.totalSegments == 0) stringResource(R.string.translation_cache_status_empty)
        else stringResource(R.string.translation_cache_status, cs.cachedSegments, cs.totalSegments)
    } ?: stringResource(R.string.translation_cache_status_empty)

    // — File picker launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        translationViewModel.clearStatus()
        appStatusText = resources.getString(R.string.status_opening_document)
        libraryViewModel.importBook(uri)
    }

    // — Build ReaderActions (all user action callbacks in one object)
    val actions = buildReaderActions(
        context = context,
        readerViewModel = readerViewModel,
        libraryViewModel = libraryViewModel,
        translationViewModel = translationViewModel,
        document = document,
        currentPage = currentPage,
        pageIndex = pageIndex,
        settings = settings,
        repository = repository,
        currentBookId = currentBookId,
        tableOfContents = tableOfContents,
        currentChapterIndex = currentChapterIndex,
        busy = busy,
        getAppStatusText = { appStatusText },
        setAppStatusText = { appStatusText = it },
        setAppErrorText = { appErrorText = it },
        resetPdfCache = { pdfPageBitmap = null; pdfPageCache = emptyMap() },
        openFilePicker = { openDocumentLauncher.launch(arrayOf("text/*", "text/markdown", "application/pdf", "application/epub+zip", "application/octet-stream")) },
    )

    // ─── Side effects ────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1000L)
        isSplashing = false
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(libraryViewModel) {
        launch {
            libraryViewModel.events.collect { event ->
                translationViewModel.clearStatus()
                when (event) {
                    is LibraryEvent.OpenedLocalBook -> {
                        applyLoadedDocument(event.result.loadedDocument, event.result.book, event.result.book.safeCurrentPageIndex, readerViewModel, translationViewModel) { pdfPageBitmap = null; pdfPageCache = emptyMap() }
                        appStatusText = resources.getString(R.string.status_opened_local_book, event.result.book.title)
                    }
                    is LibraryEvent.ImportedBook -> {
                        applyLoadedDocument(event.result.loadedDocument, event.result.book, event.result.book.safeCurrentPageIndex, readerViewModel, translationViewModel) { pdfPageBitmap = null; pdfPageCache = emptyMap() }
                        selectedTab = AppTab.Local
                        readerSubPage = com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER
                        if (pendingReadAndTranslate) {
                            pendingTranslationDocumentId = event.result.loadedDocument.document.id
                            pendingReadAndTranslate = false
                        }
                        appStatusText = if (event.result.wasDuplicateImport)
                            resources.getString(R.string.status_duplicate_book, event.result.book.title)
                        else resources.getString(R.string.status_imported_book, event.result.book.title)
                    }
                    is LibraryEvent.DeletedBook -> {
                        if (event.wasCurrentBook) {
                            readerViewModel.resetDocument(context.sampleDocument())
                            pdfPageBitmap = null; pdfPageCache = emptyMap()
                            translationViewModel.resetForDocument()
                        }
                        appStatusText = resources.getString(R.string.status_deleted_book, event.book.title)
                    }
                    is LibraryEvent.Error -> {
                        pendingReadAndTranslate = false
                        pendingTranslationDocumentId = null
                        val msg = event.cause?.readableMessage(context) ?: context.readableMessage(event.detail)
                        appStatusText = msg; appErrorText = msg
                    }
                }
            }
        }
        libraryViewModel.loadInitialLibrary()
    }

    LaunchedEffect(webCatalogViewModel) {
        webCatalogViewModel.events.collect { event ->
            when (event) {
                is WebCatalogEvent.ImportDownloaded -> {
                    translationViewModel.clearStatus()
                    pendingReadAndTranslate = event.translateAfterImport
                    appStatusText = resources.getString(R.string.status_web_catalog_downloaded, event.item.title)
                    libraryViewModel.importRemoteBook(event.item, event.bytes)
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

    LaunchedEffect(document.id, pendingTranslationDocumentId, settings, repository) {
        if (pendingTranslationDocumentId != document.id) return@LaunchedEffect
        settingsViewModel.updateTranslationDisplayMode(
            com.dongholab.pagetuner.translation.TranslationDisplayMode.TranslationOnly,
        )
        translationViewModel.translatePage(document, currentPage, settings, repository)
    }

    LaunchedEffect(
        document.id,
        pendingTranslationDocumentId,
        translationState.busy,
        translationState.status,
    ) {
        if (
            pendingTranslationDocumentId == document.id &&
            !translationState.busy &&
            translationState.status != TranslationStatus.Ready
        ) {
            pendingTranslationDocumentId = null
        }
    }

    LaunchedEffect(document.id, pageIndex, settings, repository, pendingTranslationDocumentId) {
        if (pendingTranslationDocumentId == document.id) return@LaunchedEffect
        translationViewModel.loadCachedPage(document, currentPage, settings, repository, showMissingStatus = false)
    }

    LaunchedEffect(document.id, pageIndex, pdfSourceUri, displayMode, readerState.manualRefreshToken) {
        pdfPageBitmap = null
        val source = pdfSourceUri ?: return@LaunchedEffect
        if (document.format != DocumentFormat.PDF) return@LaunchedEffect
        val currentKey = PdfPageCacheKey(source, pageIndex, displayMode)
        pdfPageBitmap = pdfPageCache[currentKey]
        runCatching {
            withContext(Dispatchers.IO) {
                (pageIndex - 1..pageIndex + 1)
                    .filter { it in 0 until document.pageCount }
                    .associate { tp ->
                        val key = PdfPageCacheKey(source, tp, displayMode)
                        key to (pdfPageCache[key] ?: PdfDocumentReader.renderPage(context, Uri.parse(source), tp, displayMode))
                    }
            }
        }.onSuccess { rendered ->
            pdfPageCache = (pdfPageCache + rendered).filterKeys { k ->
                k.sourceUri == source && k.displayMode == displayMode && abs(k.pageIndex - pageIndex) <= 1
            }
            pdfPageBitmap = rendered[currentKey] ?: pdfPageCache[currentKey]
        }.onFailure { error ->
            translationViewModel.clearStatus()
            val msg = error.readableMessage(context)
            appStatusText = msg; appErrorText = msg
        }
    }

    // ─── UI ──────────────────────────────────────────────────────────────
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || busy) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft, Key.PageUp -> { actions.previousPage(); true }
                    Key.DirectionRight, Key.PageDown, Key.Spacebar -> { actions.nextPage(); true }
                    else -> false
                }
            },
        containerColor = paperColor,
    ) { innerPadding ->
        if (isSplashing) {
            com.dongholab.pagetuner.ui.splash.PageTurnerSplashScreen()
            return@Scaffold
        }
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
                onOpen = { actions.openFilePicker() },
                onToggleControls = {
                    navHistoryStack.add(NavigationHistoryFrame.ControlsVisibilityFrame(controlsVisible))
                    readerViewModel.toggleControls()
                },
                onManualRefresh = actions.requestManualRefresh,
                onShowDetails = readerViewModel::showDocumentDetails,
            )

            if (controlsVisible) {
                AppTabNavigation(
                    selectedTab = selectedTab,
                    onSelectTab = { tab ->
                        if (tab != selectedTab) {
                            navHistoryStack.add(NavigationHistoryFrame.TabFrame(selectedTab))
                            selectedTab = tab
                        }
                    },
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (selectedTab) {
                        AppTab.Local -> LocalScreen(
                            books = localBooks,
                            currentBookId = currentBookId,
                            busy = busy,
                            onOpenBook = { book ->
                                navHistoryStack.add(NavigationHistoryFrame.TabFrame(selectedTab))
                                actions.openLocalBook(book)
                            },
                            onDeleteBook = actions.deleteLocalBook,
                            onUpdateBookOrganization = libraryViewModel::updateOrganization,
                            onImportFile = { file -> libraryViewModel.importBook(Uri.fromFile(file)) },
                        )
                        AppTab.Favorites -> FavoritesScreen(
                            favorites = favoritesList,
                            displayMode = displayMode,
                            busy = busy,
                            onOpenNovelDetail = { novel ->
                                navHistoryStack.add(NavigationHistoryFrame.TabFrame(selectedTab))
                                webCatalogViewModel.updateCatalogUrl(novel.downloadUrl)
                                selectedTab = AppTab.WebNovel
                                webCatalogViewModel.loadCatalog()
                            },
                            onRemoveFavorite = { novel -> favoritesList = favoriteStore.toggleFavorite(novel) },
                        )
                        AppTab.WebNovel -> WebNovelScreen(
                            state = webCatalogState,
                            displayMode = displayMode,
                            busy = busy,
                            statusText = webCatalogStatusText,
                            onCatalogUrlChange = webCatalogViewModel::updateCatalogUrl,
                            onQueryChange = webCatalogViewModel::updateQuery,
                            onLoadCatalog = webCatalogViewModel::loadCatalog,
                            onRefreshCatalog = webCatalogViewModel::refreshCatalog,
                            onSaveSourceAccount = webCatalogViewModel::saveCurrentCatalogAccount,
                            onLoadSourceAccount = webCatalogViewModel::loadSourceAccount,
                            onDeleteSourceAccount = webCatalogViewModel::deleteSourceAccount,
                            onLoadCachedCatalog = webCatalogViewModel::loadCachedCatalog,
                            onImportItem = { item -> webCatalogViewModel.importItem(item) },
                            onReadAndTranslateItem = { item ->
                                webCatalogViewModel.importItem(item, translateAfterImport = true)
                            },
                        )
                        AppTab.RemoteDrive -> ComingSoonPanel(
                            title = "Drive",
                            description = "Google Drive / FTP 연동 기능은 준비중입니다.",
                        )
                        AppTab.Settings -> SettingsScreen(
                            readerSettings = readerSettings,
                            translationState = translationState,
                            providerKind = providerKind,
                            apiKey = apiKey,
                            busy = busy,
                            canTranslate = canTranslateCurrentPage,
                            canRetryTranslation = canRetryCurrentPageTranslation,
                            canClearCache = (translationCacheStatus?.cachedSegments ?: 0) > 0,
                            providerStatusText = providerStatusText,
                            providerHealthText = providerHealthText,
                            translationCacheStatusText = translationCacheStatusText,
                            translationQueueStatusText = translationQueueStatusText,
                            onDisplayModeChange = { pdfPageBitmap = null; pdfPageCache = emptyMap(); settingsViewModel.updateDisplayMode(it) },
                            onPageTurnModeChange = settingsViewModel::updatePageTurnMode,
                            onPdfFitModeChange = settingsViewModel::updatePdfFitMode,
                            onFontSizeChange = settingsViewModel::updateReaderFontSize,
                            onLineSpacingChange = settingsViewModel::updateReaderLineSpacing,
                            onPageMarginChange = settingsViewModel::updateReaderPageMargin,
                            onProviderKindChange = settingsViewModel::updateProviderKind,
                            onApiKeyChange = translationViewModel::updateApiKey,
                            onLlmEndpointChange = settingsViewModel::updateLlmEndpoint,
                            onLlmModelChange = settingsViewModel::updateLlmModel,
                            onSourceLanguageChange = settingsViewModel::updateSourceLanguage,
                            onTargetLanguageChange = settingsViewModel::updateTargetLanguage,
                            onReadingWpmChange = { settingsViewModel.updateReadingWordsPerMinute(it.roundToInt()) },
                            onBatchSizeChange = { settingsViewModel.updateTranslationBatchSize(it.roundToInt()) },
                            onPaceModeChange = settingsViewModel::updatePaceMode,
                            onTranslationDisplayModeChange = settingsViewModel::updateTranslationDisplayMode,
                            onLanguagePreset = { preset ->
                                settingsViewModel.updateLanguages(preset.sourceLanguage, preset.targetLanguage)
                            },
                            onCheckProvider = { translationViewModel.checkProviderHealth(settings) },
                            onTranslate = actions.translateCurrentPage,
                            onRetryTranslation = actions.translateCurrentPage,
                            onPrefetch = actions.prefetchDocument,
                            onPausePrefetch = translationViewModel::pausePrefetch,
                            onResumePrefetch = translationViewModel::resumePrefetch,
                            onCancelPrefetch = translationViewModel::cancelPrefetch,
                            onRetryPrefetch = {
                                translationViewModel.retryFailedPrefetch(document, currentPage, settings, repository)
                            },
                            onLoadCached = actions.loadCachedCurrentPage,
                            onClearCache = actions.clearTranslationCache,
                        )
                    }
                }

                StatusStrip(statusText = statusText, progress = progress, busy = busy)
            } else {
                // Reader Mode: Sub-Page Navigation with History Tracking
                com.dongholab.pagetuner.ui.reader.ReaderSubPageSelector(
                    selectedPage = readerSubPage,
                    busy = busy,
                    onSelectPage = { page ->
                        if (page != readerSubPage) {
                            navHistoryStack.add(NavigationHistoryFrame.ReaderSubPageFrame(readerSubPage))
                            readerSubPage = page
                        }
                    },
                )

                com.dongholab.pagetuner.ui.common.EinkOperationIndicator(
                    visible = translationState.busy,
                    title = "Translating this page…",
                    detail = statusText,
                    progress = translationState.progress.takeIf { it > 0f },
                )

                when (readerSubPage) {
                    com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER -> {
                        ReaderSurface(
                            modifier = Modifier.weight(1f),
                            page = currentPage,
                            documentFormat = document.format,
                            pdfPageBitmap = pdfPageBitmap,
                            pdfFitMode = readerSettings.pdfFitMode,
                            displayMode = displayMode,
                            translation = translationState.translation,
                            translationDisplayMode = readerSettings.translationDisplayMode,
                            pageTurnMode = readerSettings.pageTurnMode,
                            pageTurningEnabled = !busy,
                            fontSizeSp = readerSettings.readerFontSizeSp,
                            lineSpacing = readerSettings.readerLineSpacing,
                            pageMarginDp = readerSettings.readerPageMarginDp,
                            onPreviousPage = actions.previousPage,
                            onNextPage = actions.nextPage,
                        )
                        ReaderPager(
                            pageIndex = pageIndex,
                            pageCount = document.pageCount,
                            currentChapterTitle = tableOfContents.getOrNull(currentChapterIndex)?.title,
                            canPreviousChapter = currentChapterIndex > 0,
                            canNextChapter = when {
                                tableOfContents.isEmpty() -> false
                                currentChapterIndex == -1 -> true
                                else -> currentChapterIndex < tableOfContents.lastIndex
                            },
                            busy = busy,
                            onPrevious = actions.previousPage,
                            onNext = actions.nextPage,
                            onPreviousChapter = {
                                navHistoryStack.add(NavigationHistoryFrame.PageJumpFrame(pageIndex))
                                actions.previousChapter()
                            },
                            onNextChapter = {
                                navHistoryStack.add(NavigationHistoryFrame.PageJumpFrame(pageIndex))
                                actions.nextChapter()
                            },
                        )
                    }
                    com.dongholab.pagetuner.ui.reader.ReaderSubPage.SEARCH -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            ReaderSearchPanel(
                                query = readerState.searchQuery,
                                resultCount = readerState.searchResults.size,
                                selectedResultNumber = readerState.selectedSearchResultNumber,
                                selectedPreview = readerState.selectedSearchMatch?.preview,
                                busy = busy,
                                onQueryChange = actions.updateSearchQuery,
                                onPreviousResult = {
                                    navHistoryStack.add(NavigationHistoryFrame.PageJumpFrame(pageIndex))
                                    navHistoryStack.add(NavigationHistoryFrame.ReaderSubPageFrame(readerSubPage))
                                    actions.previousSearchResult()
                                    readerSubPage = com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER
                                },
                                onNextResult = {
                                    navHistoryStack.add(NavigationHistoryFrame.PageJumpFrame(pageIndex))
                                    navHistoryStack.add(NavigationHistoryFrame.ReaderSubPageFrame(readerSubPage))
                                    actions.nextSearchResult()
                                    readerSubPage = com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER
                                },
                                onClearSearch = actions.clearSearch,
                            )
                        }
                    }
                    com.dongholab.pagetuner.ui.reader.ReaderSubPage.BOOKMARKS -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            ReaderBookmarkPanel(
                                draftLabel = readerState.bookmarkDraftLabel,
                                bookmarks = bookmarks,
                                currentPageIndex = pageIndex,
                                busy = busy,
                                onDraftLabelChange = readerViewModel::updateBookmarkDraftLabel,
                                onAddBookmark = actions.addBookmark,
                                onOpenBookmark = { bookmark ->
                                    navHistoryStack.add(NavigationHistoryFrame.PageJumpFrame(pageIndex))
                                    navHistoryStack.add(NavigationHistoryFrame.ReaderSubPageFrame(readerSubPage))
                                    actions.openBookmark(bookmark)
                                    readerSubPage = com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER
                                },
                                onRemoveBookmark = actions.removeBookmark,
                            )
                        }
                    }
                    com.dongholab.pagetuner.ui.reader.ReaderSubPage.ANNOTATIONS -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            ReaderAnnotationPanel(
                                noteDraft = readerState.noteDraftText,
                                annotations = annotations,
                                currentPageIndex = pageIndex,
                                busy = busy,
                                onNoteDraftChange = readerViewModel::updateNoteDraftText,
                                onAddHighlight = actions.addHighlight,
                                onAddNote = actions.addNote,
                                onOpenAnnotation = { annotation ->
                                    navHistoryStack.add(NavigationHistoryFrame.PageJumpFrame(pageIndex))
                                    navHistoryStack.add(NavigationHistoryFrame.ReaderSubPageFrame(readerSubPage))
                                    actions.openAnnotation(annotation)
                                    readerSubPage = com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER
                                },
                                onRemoveAnnotation = actions.removeAnnotation,
                                onExportAnnotations = actions.exportAnnotations,
                            )
                        }
                    }
                }
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

    appErrorText?.let { errorText ->
        AlertDialog(
            onDismissRequest = { appErrorText = null },
            title = { Text(stringResource(R.string.error_dialog_title)) },
            text = { Text(errorText) },
            confirmButton = {
                TextButton(onClick = { appErrorText = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

// ─────────────────────────────────────────────
// Helper: apply a loaded document to reader state
// ─────────────────────────────────────────────
private fun applyLoadedDocument(
    loaded: LoadedReaderDocument,
    localBook: LocalBook?,
    requestedPageIndex: Int,
    readerViewModel: ReaderViewModel,
    translationViewModel: TranslationViewModel,
    resetPdfCache: () -> Unit,
) {
    readerViewModel.applyLoadedDocument(
        loaded = loaded,
        localBookId = localBook?.id,
        requestedPageIndex = requestedPageIndex,
        bookmarks = localBook?.bookmarks.orEmpty().map { it.toReaderBookmark() },
        annotations = localBook?.annotations.orEmpty().map { it.toReaderAnnotation() },
    )
    resetPdfCache()
    translationViewModel.resetForDocument()
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
fun PageTurnerPreview() {
    PageTurnerTheme(darkTheme = false, dynamicColor = false) {
        PageTurnerApp()
    }
}
