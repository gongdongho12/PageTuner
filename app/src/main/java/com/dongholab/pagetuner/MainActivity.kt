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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.dongholab.pagetuner.source.RemoteCatalogRoute
import com.dongholab.pagetuner.source.RemoteSourceAccountStore
import com.dongholab.pagetuner.source.WebCatalogEvent
import com.dongholab.pagetuner.source.WebCatalogViewModel
import com.dongholab.pagetuner.source.WebNovelPageRuntime
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import com.dongholab.pagetuner.translation.JsonFileTranslationCache
import com.dongholab.pagetuner.translation.TranslationProviderFactory
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationRepository
import com.dongholab.pagetuner.translation.TranslationRuntimeSecrets
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.TranslationStatus
import com.dongholab.pagetuner.translation.TranslationViewModel
import com.dongholab.pagetuner.translation.glossary.BookGlossaryStore
import com.dongholab.pagetuner.translation.glossary.BookGlossaryViewModel
import com.dongholab.pagetuner.translation.glossary.GlossaryTranslationProvider
import com.dongholab.pagetuner.ui.LanguagePreset
import com.dongholab.pagetuner.ui.common.AppTab
import com.dongholab.pagetuner.ui.common.AppTabNavigation
import com.dongholab.pagetuner.ui.common.ComingSoonPanel
import com.dongholab.pagetuner.ui.common.LocalListLayoutMode
import com.dongholab.pagetuner.ui.common.StatusStrip
import com.dongholab.pagetuner.ui.reader.DocumentDetailsDialog
import com.dongholab.pagetuner.ui.reader.ReaderAnnotationPanel
import com.dongholab.pagetuner.ui.reader.ReaderBookmarkPanel
import com.dongholab.pagetuner.ui.reader.ReaderHeader
import com.dongholab.pagetuner.ui.reader.ReaderFullscreenSystemBars
import com.dongholab.pagetuner.ui.reader.ReaderPager
import com.dongholab.pagetuner.ui.reader.ReaderSearchPanel
import com.dongholab.pagetuner.ui.reader.ReaderSurface
import com.dongholab.pagetuner.ui.reader.forReaderPage
import com.dongholab.pagetuner.ui.reader.isReaderPageTurnBlocked
import com.dongholab.pagetuner.ui.reader.readerViewportPolicy
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
    LaunchedEffect(context) {
        WebNovelPageRuntime.install(context)
        OfflineNovelStorageStore.install(context)
    }

    // — Stores (singleton per context)
    val settingsStore = remember(context) { ReaderSettingsStore(context) }
    val localLibraryStore = remember(context) { LocalLibraryStore(context) }
    val remoteCatalogCache = remember(context) { RemoteCatalogCache(context) }
    val remoteSourceAccountStore = remember(context) { RemoteSourceAccountStore(context) }
    val favoriteStore = remember(context) {
        com.dongholab.pagetuner.source.WebNovelFavoriteStore(context.filesDir.resolve("favorites.json"))
    }
    val glossaryStore = remember(context) { BookGlossaryStore(context) }

    // — ViewModels
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(settingsStore))
    val readerViewModel: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory(context.sampleDocument()))
    val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(localLibraryStore))
    val webCatalogViewModel: WebCatalogViewModel = viewModel(
        factory = WebCatalogViewModel.Factory(cache = remoteCatalogCache, accountStore = remoteSourceAccountStore),
    )
    val translationViewModel: TranslationViewModel = viewModel()
    val glossaryViewModel: BookGlossaryViewModel = viewModel(
        factory = BookGlossaryViewModel.Factory(glossaryStore),
    )

    // — State observation
    val readerSettings by settingsViewModel.settings.collectAsState(initial = ReaderSettings())
    val readerState by readerViewModel.uiState.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    val webCatalogState by webCatalogViewModel.uiState.collectAsState()
    val translationState by translationViewModel.uiState.collectAsState()
    val glossaryState by glossaryViewModel.uiState.collectAsState()

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
    var readerReturnTab by remember { mutableStateOf(AppTab.Local) }
    var webCatalogRoute by remember { mutableStateOf<RemoteCatalogRoute>(RemoteCatalogRoute.SourceSystems) }
    var readerFullscreen by rememberSaveable { mutableStateOf(false) }

    // — Derived reader state
    val localBooks = libraryState.books
    val document = readerState.document
    val pageIndex = readerState.safePageIndex
    val currentPage = readerState.currentPage
    val pdfSourceUri = readerState.pdfSourceUri
    val currentBookId = readerState.currentBookId
    val currentBook = localBooks.firstOrNull { it.id == currentBookId }
    val currentContentAlreadyTranslated = currentBook?.contentIsTranslated == true
    val controlsVisible = readerState.controlsVisible
    val showDocumentDetails = readerState.showDocumentDetails
    val bookmarks = readerState.bookmarks
    val annotations = readerState.annotations
    val readerFullscreenActive = readerFullscreen &&
        !controlsVisible &&
        readerSubPage == com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER

    // Back button history restoration handler
    BackHandler(enabled = readerFullscreenActive || !controlsVisible || navHistoryStack.isNotEmpty()) {
        if (readerFullscreenActive) {
            readerFullscreen = false
            return@BackHandler
        }
        if (!controlsVisible) {
            readerViewModel.showControls()
            selectedTab = readerReturnTab
            readerSubPage = com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER
            navHistoryStack.clear()
            return@BackHandler
        }
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
    val manualApiKey = translationState.apiKey
    val usesLocalDeepSeekSecret = providerKind == TranslationProviderKind.DEEPSEEK &&
        TranslationRuntimeSecrets.hasLocalDeepSeekKey
    val apiKey = when {
        usesLocalDeepSeekSecret -> TranslationRuntimeSecrets.deepSeekApiKey
        else -> manualApiKey
    }
    val activeLlmEndpoint = when (providerKind) {
        TranslationProviderKind.DEEPSEEK -> TranslationRuntimeSecrets.deepSeekApiUrl
        else -> readerSettings.llmEndpoint
    }
    val activeLlmModel = when (providerKind) {
        TranslationProviderKind.DEEPSEEK -> TranslationRuntimeSecrets.deepSeekModel
        else -> readerSettings.llmModel
    }
    val busy = libraryState.busy || translationState.busy || webCatalogState.busy
    val readerPageTurnBlocked = isReaderPageTurnBlocked(
        libraryBusy = libraryState.busy,
        translationBusy = translationState.busy,
        catalogBusy = webCatalogState.busy,
    )
    val viewportPolicy = readerViewportPolicy(
        fullScreen = readerFullscreenActive,
        configuredPageMarginDp = readerSettings.readerPageMarginDp,
    )
    val progress = translationState.progress

    // — Translation derived state
    val cache = remember(context, currentBook?.relativePath) {
        JsonFileTranslationCache(context, currentBook?.relativePath)
    }
    val settings = TranslationSettings(
        providerKind = providerKind,
        apiKey = apiKey,
        llmEndpoint = activeLlmEndpoint,
        llmModel = activeLlmModel,
        sourceLanguage = readerSettings.sourceLanguage,
        targetLanguage = readerSettings.targetLanguage,
        readingWordsPerMinute = readerSettings.readingWordsPerMinute,
        batchSize = readerSettings.translationBatchSize,
        paceMode = readerSettings.paceMode,
    )
    val activeGlossary = glossaryState.glossary
    val repository = remember(settings, cache, activeGlossary?.translationFingerprint) {
        val provider = TranslationProviderFactory.create(
            settings = settings,
            initialCharacterAliases = activeGlossary?.characterAliases.orEmpty(),
            onCharacterAliases = activeGlossary?.let {
                { suggestions -> glossaryViewModel.mergeLlmCharacterAliases(suggestions) }
            },
        )
        TranslationRepository(
            provider = activeGlossary
                ?.takeIf { it.activeEntries.isNotEmpty() }
                ?.let { GlossaryTranslationProvider(provider, it) }
                ?: provider,
            cache = cache,
        )
    }
    val tableOfContents = document.tableOfContents
    val currentChapterIndex = tableOfContents.indexOfLast { it.pageIndex <= currentPage.index }
    val canTranslateCurrentPage = settings.isProviderConfigured && currentPage.hasText
    val canRetryCurrentPageTranslation =
        translationState.status is TranslationStatus.Error && canTranslateCurrentPage
    val translationCacheStatus = translationState.cacheStatus
    val currentPageTranslation = translationState.translation.forReaderPage(currentPage)
    val currentReaderTranslationLoad = translationState.readerLoad.takeIf {
        it.matches(document.id, pageIndex)
    }
    val readerTranslationLoading = !currentContentAlreadyTranslated &&
        com.dongholab.pagetuner.translation.shouldShowInitialReaderTranslationLoading(
            currentDocumentId = document.id,
            currentPageIndex = pageIndex,
            pendingTranslationDocumentId = pendingTranslationDocumentId,
            pageHasText = currentPage.hasText,
            hasTranslation = currentPageTranslation != null,
            readerLoad = translationState.readerLoad,
        )
    val statusText = when (val s = translationState.status) {
        TranslationStatus.Ready -> appStatusText
        else -> s.localizedMessage(context)
    }
    val webCatalogStatusText = webCatalogState.status.localizedMessage(context)
    val providerStatusText = when {
        settingsProviderConfigured(providerKind, apiKey, activeLlmEndpoint, activeLlmModel) ->
            stringResource(R.string.provider_status_ready)
        providerKind == com.dongholab.pagetuner.translation.TranslationProviderKind.GOOGLE_CLOUD ->
            stringResource(R.string.provider_status_missing_google_key)
        providerKind == com.dongholab.pagetuner.translation.TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML ->
            stringResource(R.string.provider_status_google_web_no_key_required)
        providerKind == TranslationProviderKind.DEEPSEEK ->
            stringResource(R.string.provider_status_missing_deepseek_key)
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
        readerReturnTab = AppTab.Local
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
    LaunchedEffect(currentBookId) { glossaryViewModel.selectBook(currentBookId) }

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
                        selectedTab = readerReturnTab
                        readerSubPage = com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER
                        navHistoryStack.clear()
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
                    readerReturnTab = AppTab.WebNovel
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
        translationViewModel.startRollingPrefetch(
            document = document,
            currentPageIndex = pageIndex,
            settings = settings,
            repository = repository,
        )
    }

    LaunchedEffect(
        document.id,
        pendingTranslationDocumentId,
        translationState.status,
        currentPageTranslation,
    ) {
        if (com.dongholab.pagetuner.translation.shouldClearPendingReaderTranslation(
                currentDocumentId = document.id,
                pendingTranslationDocumentId = pendingTranslationDocumentId,
                hasTranslation = currentPageTranslation != null,
                status = translationState.status,
            )
        ) {
            pendingTranslationDocumentId = null
        }
    }

    LaunchedEffect(document.id, pageIndex, settings, repository, pendingTranslationDocumentId) {
        if (currentContentAlreadyTranslated) return@LaunchedEffect
        val shouldLoadCache = com.dongholab.pagetuner.translation.shouldLoadCachedReaderTranslation(
            currentDocumentId = document.id,
            pendingTranslationDocumentId = pendingTranslationDocumentId,
            status = translationState.status,
        )
        if (!shouldLoadCache) return@LaunchedEffect
        translationViewModel.loadCachedPage(document, currentPage, settings, repository, showMissingStatus = false)
    }

    LaunchedEffect(document.id, pageIndex, settings, repository) {
        translationViewModel.onReaderPageChanged(
            document = document,
            currentPageIndex = pageIndex,
            settings = settings,
            repository = repository,
        )
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
    ReaderFullscreenSystemBars(readerFullscreenActive)
    CompositionLocalProvider(LocalListLayoutMode provides readerSettings.listLayoutMode) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || readerPageTurnBlocked) return@onPreviewKeyEvent false
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
                .padding(if (readerFullscreenActive) PaddingValues(0.dp) else innerPadding)
                .padding(viewportPolicy.rootPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(
                if (readerFullscreenActive) 0.dp else 10.dp,
            ),
        ) {
            if (viewportPolicy.showChrome) {
                ReaderHeader(
                    document = document,
                    page = currentPage,
                    controlsVisible = controlsVisible,
                    onOpen = {
                        readerReturnTab = AppTab.Local
                        actions.openFilePicker()
                    },
                    onToggleControls = {
                        readerFullscreen = false
                        navHistoryStack.add(NavigationHistoryFrame.ControlsVisibilityFrame(controlsVisible))
                        readerViewModel.toggleControls()
                    },
                    onManualRefresh = actions.requestManualRefresh,
                    onShowDetails = readerViewModel::showDocumentDetails,
                    onEnterFullscreen = {
                        readerReturnTab = selectedTab
                        readerSubPage = com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER
                        if (controlsVisible) readerViewModel.toggleControls()
                        readerFullscreen = true
                    },
                )
            }

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
                                readerReturnTab = AppTab.Local
                                navHistoryStack.clear()
                                actions.openLocalBook(book)
                            },
                            onDeleteBook = actions.deleteLocalBook,
                            onUpdateBookOrganization = libraryViewModel::updateOrganization,
                            onImportFile = { file ->
                                readerReturnTab = AppTab.Local
                                libraryViewModel.importBook(Uri.fromFile(file))
                            },
                        )
                        AppTab.Favorites -> FavoritesScreen(
                            favorites = favoritesList,
                            displayMode = displayMode,
                            busy = busy,
                            onOpenNovelDetail = { novel ->
                                navHistoryStack.add(NavigationHistoryFrame.TabFrame(selectedTab))
                                val parentCatalogUrl = webCatalogState.sourceAccounts
                                    .firstOrNull { it.id == novel.identity.accountId }
                                    ?.endpoint
                                    ?: webCatalogState.catalogUrl
                                webCatalogRoute = RemoteCatalogRoute.Book(parentCatalogUrl, novel)
                                webCatalogViewModel.updateCatalogUrl(parentCatalogUrl)
                                selectedTab = AppTab.WebNovel
                                webCatalogViewModel.loadCatalog()
                            },
                            onRemoveFavorite = { novel -> favoritesList = favoriteStore.toggleFavorite(novel) },
                        )
                        AppTab.WebNovel -> WebNovelScreen(
                            state = webCatalogState,
                            route = webCatalogRoute,
                            onRouteChange = { route -> webCatalogRoute = route },
                            displayMode = displayMode,
                            busy = busy,
                            statusText = webCatalogStatusText,
                            targetLanguage = settings.normalizedTargetLanguage,
                            canTranslate = settings.isProviderConfigured,
                            onCatalogUrlChange = webCatalogViewModel::updateCatalogUrl,
                            onQueryChange = webCatalogViewModel::updateQuery,
                            onGenreSelected = webCatalogViewModel::updateGenreSelection,
                            onSearchCatalog = webCatalogViewModel::submitSearch,
                            onClearCatalogSearch = webCatalogViewModel::clearSearch,
                            onLoadCatalog = webCatalogViewModel::loadCatalog,
                            onRefreshCatalog = webCatalogViewModel::refreshCatalog,
                            onSaveSourceAccount = webCatalogViewModel::saveCurrentCatalogAccount,
                            onLoadSourceAccount = webCatalogViewModel::loadSourceAccount,
                            onDeleteSourceAccount = webCatalogViewModel::deleteSourceAccount,
                            onLoadCachedCatalog = webCatalogViewModel::loadCachedCatalog,
                            onImportItem = { item ->
                                readerReturnTab = AppTab.WebNovel
                                webCatalogViewModel.importItem(item)
                            },
                            onReadAndTranslateItem = { item ->
                                readerReturnTab = AppTab.WebNovel
                                webCatalogViewModel.importItem(
                                    item = item,
                                    translateAfterImport = true,
                                    preferredOfflineLanguage = settings.normalizedTargetLanguage,
                                )
                            },
                            onTranslateCatalog = {
                                webCatalogViewModel.translateVisibleCatalog(context, settings)
                            },
                            onRemoteCatalogPageSelected = webCatalogViewModel::loadRemoteCatalogPage,
                            onBatchDownloadChapters = { chapters ->
                                webCatalogViewModel.downloadChaptersForOffline(
                                    context = context,
                                    chapters = chapters,
                                    settings = settings,
                                    includeTranslation = settings.isProviderConfigured,
                                )
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
                            apiKey = manualApiKey,
                            usesLocalDeepSeekSecret = usesLocalDeepSeekSecret,
                            busy = busy,
                            canTranslate = canTranslateCurrentPage,
                            canRetryTranslation = canRetryCurrentPageTranslation,
                            canClearCache = (translationCacheStatus?.cachedSegments ?: 0) > 0,
                            providerStatusText = providerStatusText,
                            providerHealthText = providerHealthText,
                            translationCacheStatusText = translationCacheStatusText,
                            translationQueueStatusText = translationQueueStatusText,
                            onDisplayModeChange = { pdfPageBitmap = null; pdfPageCache = emptyMap(); settingsViewModel.updateDisplayMode(it) },
                            onListLayoutModeChange = settingsViewModel::updateListLayoutMode,
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
                if (viewportPolicy.showChrome) {
                    com.dongholab.pagetuner.ui.reader.ReaderSubPageSelector(
                        selectedPage = readerSubPage,
                        busy = busy,
                        onSelectPage = { page ->
                            if (page != readerSubPage) {
                                readerFullscreen = false
                                navHistoryStack.add(NavigationHistoryFrame.ReaderSubPageFrame(readerSubPage))
                                readerSubPage = page
                            }
                        },
                    )

                    com.dongholab.pagetuner.ui.common.EinkOperationIndicator(
                    visible = readerTranslationLoading || translationState.busy ||
                        translationState.rolling.flagFor(pageIndex) in setOf(
                            com.dongholab.pagetuner.translation.TranslationPageFlag.Queued,
                            com.dongholab.pagetuner.translation.TranslationPageFlag.Translating,
                        ),
                    title = when {
                        currentReaderTranslationLoad == null || currentReaderTranslationLoad.stage ==
                            com.dongholab.pagetuner.translation.ReaderTranslationLoadStage.CheckingCache ->
                            stringResource(R.string.translation_initial_loading_title)
                        currentReaderTranslationLoad?.stage ==
                            com.dongholab.pagetuner.translation.ReaderTranslationLoadStage.Queued ->
                            stringResource(R.string.translation_queued_title)
                        translationState.rolling.enabled -> stringResource(R.string.translation_rolling_title)
                        else -> stringResource(R.string.translation_current_page_title)
                    },
                    detail = when {
                        currentReaderTranslationLoad == null || currentReaderTranslationLoad.stage ==
                            com.dongholab.pagetuner.translation.ReaderTranslationLoadStage.CheckingCache ->
                            stringResource(R.string.translation_initial_loading_detail)
                        currentReaderTranslationLoad?.stage ==
                            com.dongholab.pagetuner.translation.ReaderTranslationLoadStage.Queued ->
                            stringResource(R.string.translation_queued_detail)
                        translationState.rolling.enabled -> stringResource(
                            R.string.translation_rolling_detail,
                            translationState.rolling.windowStartIndex + 1,
                            translationState.rolling.windowEndExclusive,
                            translationState.rolling.readyPageCount,
                            translationState.rolling.windowPageCount,
                        )
                        else -> statusText
                    },
                    progress = if (translationState.rolling.enabled) {
                        translationState.rolling.fraction.takeIf { it > 0f }
                    } else {
                        translationState.progress.takeIf { it > 0f }
                    },
                    )

                    if (
                        readerSubPage == com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER &&
                        !readerTranslationLoading &&
                        !translationState.busy &&
                        currentPageTranslation == null &&
                        !currentContentAlreadyTranslated
                    ) {
                        com.dongholab.pagetuner.ui.translation.ReaderTranslationStatusBar(
                        targetLanguage = settings.normalizedTargetLanguage,
                        providerConfigured = canTranslateCurrentPage,
                        hasError = translationState.status is TranslationStatus.Error,
                        inProgress = translationState.rolling.flagFor(pageIndex) in setOf(
                            com.dongholab.pagetuner.translation.TranslationPageFlag.Queued,
                            com.dongholab.pagetuner.translation.TranslationPageFlag.Translating,
                        ),
                        errorMessage = statusText,
                        onTranslate = {
                            settingsViewModel.updateTranslationDisplayMode(
                                com.dongholab.pagetuner.translation.TranslationDisplayMode.TranslationOnly,
                            )
                            actions.translateCurrentPage()
                        },
                        )
                    }
                }

                when (readerSubPage) {
                    com.dongholab.pagetuner.ui.reader.ReaderSubPage.READER -> {
                        ReaderSurface(
                            modifier = Modifier.weight(1f),
                            page = currentPage,
                            documentFormat = document.format,
                            pdfPageBitmap = pdfPageBitmap,
                            pdfFitMode = readerSettings.pdfFitMode,
                            displayMode = displayMode,
                            translation = currentPageTranslation,
                            glossaryEntries = activeGlossary?.activeEntries.orEmpty(),
                            translationDisplayMode = readerSettings.translationDisplayMode,
                            pageTurnMode = readerSettings.pageTurnMode,
                            pageTurningEnabled = !readerPageTurnBlocked,
                            fontSizeSp = readerSettings.readerFontSizeSp,
                            lineSpacing = readerSettings.readerLineSpacing,
                            pageMarginDp = viewportPolicy.pageMarginDp,
                            onPreviousPage = actions.previousPage,
                            onNextPage = actions.nextPage,
                            fullScreen = readerFullscreenActive,
                            onExitFullscreen = { readerFullscreen = false },
                        )
                        if (viewportPolicy.showChrome) ReaderPager(
                            pageIndex = pageIndex,
                            pageCount = document.pageCount,
                            currentChapterTitle = tableOfContents.getOrNull(currentChapterIndex)?.title,
                            canPreviousChapter = currentChapterIndex > 0,
                            canNextChapter = when {
                                tableOfContents.isEmpty() -> false
                                currentChapterIndex == -1 -> true
                                else -> currentChapterIndex < tableOfContents.lastIndex
                            },
                            busy = readerPageTurnBlocked,
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
                    com.dongholab.pagetuner.ui.reader.ReaderSubPage.GLOSSARY -> {
                        com.dongholab.pagetuner.ui.translation.BookGlossaryPanel(
                            modifier = Modifier.weight(1f),
                            bookTitle = currentBook?.title,
                            entries = activeGlossary?.entries.orEmpty(),
                            busy = glossaryState.busy,
                            error = glossaryState.error,
                            sharePayload = activeGlossary?.takeIf { it.entries.isNotEmpty() }?.let { glossary ->
                                com.dongholab.pagetuner.translation.glossary.BookGlossaryShareCodec.encode(
                                    glossary = glossary,
                                    bookTitle = currentBook?.title.orEmpty(),
                                )
                            },
                            onSave = glossaryViewModel::upsert,
                            onDelete = { glossaryViewModel.delete(it.id) },
                            onImportSharedDictionary = glossaryViewModel::importSharedDictionary,
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
    translationViewModel.resetForDocument(
        documentId = loaded.document.id,
        pageIndex = requestedPageIndex.coerceIn(0, loaded.document.pageCount - 1),
    )
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
fun PageTurnerPreview() {
    PageTurnerTheme(darkTheme = false, dynamicColor = false) {
        PageTurnerApp()
    }
}
