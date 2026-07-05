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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.LoadedReaderDocument
import com.dongholab.pagetuner.document.PdfDocumentReader
import com.dongholab.pagetuner.document.sampleDocument
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.library.LocalLibraryStore
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.settings.ReaderSettings
import com.dongholab.pagetuner.settings.ReaderSettingsStore
import com.dongholab.pagetuner.translation.JsonFileTranslationCache
import com.dongholab.pagetuner.translation.PageTranslation
import com.dongholab.pagetuner.translation.PrefetchStage
import com.dongholab.pagetuner.translation.PrefetchProgress
import com.dongholab.pagetuner.translation.TranslatedSegment
import com.dongholab.pagetuner.translation.TranslationCacheStatus
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderFactory
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationRepository
import com.dongholab.pagetuner.translation.TranslationSettings
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
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.PageTurnerTheme
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
    val readerSettings by settingsStore.settings.collectAsState(initial = ReaderSettings())
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val initialStatus = stringResource(R.string.status_ready)

    var document by remember(context) { mutableStateOf(context.sampleDocument()) }
    var pdfSourceUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfPageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPageCache by remember { mutableStateOf<Map<PdfPageCacheKey, Bitmap>>(emptyMap()) }
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var currentBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var localBooks by remember { mutableStateOf<List<LocalBook>>(emptyList()) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var translation by remember { mutableStateOf<PageTranslation?>(null) }
    var translationCacheStatus by remember { mutableStateOf<TranslationCacheStatus?>(null) }
    var statusText by rememberSaveable(initialStatus) { mutableStateOf(initialStatus) }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showDocumentDetails by rememberSaveable { mutableStateOf(false) }

    val providerKind = readerSettings.providerKind
    val paceMode = readerSettings.paceMode
    val pageTurnMode = readerSettings.pageTurnMode
    val displayMode = readerSettings.displayMode
    val sourceLanguage = readerSettings.sourceLanguage
    val targetLanguage = readerSettings.targetLanguage
    val currentPage = document.pages[pageIndex.coerceIn(0, document.pages.lastIndex)]
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
        document = loaded.document
        pdfSourceUri = loaded.pdfSourceUri
        pdfPageBitmap = null
        pdfPageCache = emptyMap()
        pageIndex = requestedPageIndex.coerceIn(0, loaded.document.pageCount - 1)
        currentBookId = localBook?.id
        translation = null
        translationCacheStatus = null
        progress = 0f
    }

    fun openLocalBook(book: LocalBook) {
        if (busy) return
        scope.launch {
            busy = true
            statusText = context.getString(R.string.status_opening_document)
            try {
                val result = localLibraryStore.openBook(book.id)
                applyLoadedDocument(
                    loaded = result.loadedDocument,
                    localBook = result.book,
                    requestedPageIndex = result.book.safeCurrentPageIndex,
                )
                localBooks = localLibraryStore.listBooks()
                statusText = context.getString(R.string.status_opened_local_book, result.book.title)
            } catch (error: Exception) {
                statusText = error.readableMessage(context)
            } finally {
                busy = false
            }
        }
    }

    fun deleteLocalBook(book: LocalBook) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val deleted = localLibraryStore.deleteBook(book.id)
                localBooks = localLibraryStore.listBooks()
                if (deleted && currentBookId == book.id) {
                    document = context.sampleDocument()
                    pdfSourceUri = null
                    pdfPageBitmap = null
                    pdfPageCache = emptyMap()
                    pageIndex = 0
                    currentBookId = null
                    translation = null
                    translationCacheStatus = null
                    progress = 0f
                }
                statusText = context.getString(R.string.status_deleted_book, book.title)
            } catch (error: Exception) {
                statusText = error.readableMessage(context)
            } finally {
                busy = false
            }
        }
    }

    fun changePage(targetIndex: Int) {
        val boundedIndex = targetIndex.coerceIn(0, document.pageCount - 1)
        if (boundedIndex == pageIndex) {
            statusText = if (targetIndex < pageIndex) {
                context.getString(R.string.status_first_page)
            } else {
                context.getString(R.string.status_last_page)
            }
            return
        }

        pageIndex = boundedIndex
        translation = null
        progress = 0f
    }

    fun previousPage() {
        changePage(pageIndex - 1)
    }

    fun nextPage() {
        changePage(pageIndex + 1)
    }

    fun previousChapter() {
        if (!canPreviousChapter) {
            statusText = context.getString(R.string.status_first_chapter)
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
            statusText = context.getString(R.string.status_last_chapter)
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
        scope.launch {
            val cached = repository.loadCachedPage(document, currentPage, settings)
            translation = cached
            translationCacheStatus = repository.cacheStatus(document, settings)
            if (cached != null) {
                progress = 1f
                statusText = context.getString(R.string.status_loaded_cached)
            } else {
                progress = 0f
                statusText = context.getString(R.string.status_no_cached)
            }
        }
    }

    fun translateCurrentPage() {
        if (busy) return
        scope.launch {
            busy = true
            progress = 0f
            statusText = context.getString(
                R.string.status_starting_translation,
                paceMode.localizedLabel(context).lowercase(),
            )
            translation = null
            try {
                val result = repository.translatePage(document, currentPage, settings) { update ->
                    progress = update.fraction
                    statusText = context.getString(
                        R.string.status_translated_segments,
                        update.completedSegments,
                        update.totalSegments,
                    )
                    translation = PageTranslation(
                        page = currentPage,
                        sourceLanguage = settings.normalizedSourceLanguage,
                        targetLanguage = settings.normalizedTargetLanguage,
                        segments = update.currentText.split("\n\n").mapIndexed { index, text ->
                            val segmentId = currentPage.segments.getOrNull(index)?.id ?: "progress-$index"
                            TranslatedSegment(segmentId, text)
                        },
                        completedFromCache = false,
                    )
                }
                translation = result
                translationCacheStatus = repository.cacheStatus(document, settings)
                progress = 1f
                statusText = if (result.completedFromCache) {
                    context.getString(R.string.status_served_from_cache)
                } else {
                    context.getString(R.string.status_translated_saved_page, currentPage.index + 1)
                }
            } catch (error: Exception) {
                statusText = error.readableMessage(context)
            } finally {
                busy = false
            }
        }
    }

    fun prefetchDocument() {
        if (busy) return
        scope.launch {
            busy = true
            progress = 0f
            statusText = context.getString(R.string.status_preparing_offline_cache)
            try {
                repository.prefetchDocument(
                    document = document,
                    startPageIndex = pageIndex,
                    settings = settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH),
                ) { update: PrefetchProgress ->
                    progress = update.fraction
                    statusText = context.localizedPrefetchStatus(update)
                }
                statusText = context.getString(R.string.status_offline_cache_ready)
                translationCacheStatus = repository.cacheStatus(document, settings)
                progress = 1f
                loadCachedCurrentPage()
            } catch (error: Exception) {
                statusText = error.readableMessage(context)
            } finally {
                busy = false
            }
        }
    }

    fun clearTranslationCache() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val deleted = repository.clearDocumentCache(document, settings)
                translation = null
                translationCacheStatus = repository.cacheStatus(document, settings)
                progress = 0f
                statusText = context.getString(R.string.status_cleared_translation_cache, deleted)
            } catch (error: Exception) {
                statusText = error.readableMessage(context)
            } finally {
                busy = false
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            statusText = context.getString(R.string.status_opening_document)
            try {
                val imported = localLibraryStore.importBook(uri)
                applyLoadedDocument(
                    loaded = imported.loadedDocument,
                    localBook = imported.book,
                    requestedPageIndex = imported.book.safeCurrentPageIndex,
                )
                localBooks = localLibraryStore.listBooks()
                statusText = if (imported.wasDuplicateImport) {
                    context.getString(R.string.status_duplicate_book, imported.book.title)
                } else {
                    context.getString(R.string.status_imported_book, imported.book.title)
                }
            } catch (error: Exception) {
                statusText = error.readableMessage(context)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(localLibraryStore) {
        val books = localLibraryStore.listBooks()
        localBooks = books
        val lastOpened = books.firstOrNull()
        if (lastOpened != null) {
            runCatching { localLibraryStore.openBook(lastOpened.id) }
                .onSuccess { result ->
                    applyLoadedDocument(
                        loaded = result.loadedDocument,
                        localBook = result.book,
                        requestedPageIndex = result.book.safeCurrentPageIndex,
                    )
                    localBooks = localLibraryStore.listBooks()
                    statusText = context.getString(
                        R.string.status_opened_local_book,
                        result.book.title,
                    )
                }
                .onFailure { error ->
                    statusText = error.readableMessage(context)
                }
        }
    }

    LaunchedEffect(currentBookId, pageIndex, document.id) {
        val bookId = currentBookId ?: return@LaunchedEffect
        localLibraryStore.updateProgress(bookId, pageIndex)
        localBooks = localLibraryStore.listBooks()
    }

    LaunchedEffect(document.id, settings, repository) {
        translationCacheStatus = repository.cacheStatus(document, settings)
    }

    LaunchedEffect(document.id, pageIndex, pdfSourceUri, displayMode) {
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
                statusText = error.readableMessage(context)
            }
        }
    }

    LaunchedEffect(document.id, pageIndex, sourceLanguage, targetLanguage) {
        val cached = repository.loadCachedPage(document, currentPage, settings)
        translation = cached
        if (cached != null) {
            progress = 1f
            statusText = context.getString(
                R.string.status_cached_segments,
                currentPage.segments.size,
                currentPage.segments.size,
            )
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && handleReaderKey(event.key)
            },
        containerColor = EinkPaper,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EinkPaper)
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
                onToggleControls = { controlsVisible = !controlsVisible },
                onShowDetails = { showDocumentDetails = true },
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
                        scope.launch { settingsStore.updateDisplayMode(it) }
                    },
                )
                PageTurnSettingsPanel(
                    pageTurnMode = pageTurnMode,
                    busy = busy,
                    onPageTurnModeChange = { scope.launch { settingsStore.updatePageTurnMode(it) } },
                )
                ReaderPreferencesPanel(
                    pdfFitMode = readerSettings.pdfFitMode,
                    fontSizeSp = readerSettings.readerFontSizeSp,
                    lineSpacing = readerSettings.readerLineSpacing,
                    pageMarginDp = readerSettings.readerPageMarginDp,
                    busy = busy,
                    onPdfFitModeChange = { scope.launch { settingsStore.updatePdfFitMode(it) } },
                    onFontSizeChange = { scope.launch { settingsStore.updateReaderFontSize(it) } },
                    onLineSpacingChange = { scope.launch { settingsStore.updateReaderLineSpacing(it) } },
                    onPageMarginChange = { scope.launch { settingsStore.updateReaderPageMargin(it) } },
                )
                TranslationControls(
                    providerKind = providerKind,
                    onProviderKindChange = { scope.launch { settingsStore.updateProviderKind(it) } },
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    llmEndpoint = readerSettings.llmEndpoint,
                    onLlmEndpointChange = { scope.launch { settingsStore.updateLlmEndpoint(it) } },
                    llmModel = readerSettings.llmModel,
                    onLlmModelChange = { scope.launch { settingsStore.updateLlmModel(it) } },
                    sourceLanguage = sourceLanguage,
                    onSourceLanguageChange = { scope.launch { settingsStore.updateSourceLanguage(it) } },
                    targetLanguage = targetLanguage,
                    onTargetLanguageChange = { scope.launch { settingsStore.updateTargetLanguage(it) } },
                    readingWpm = readerSettings.readingWordsPerMinute.toFloat(),
                    onReadingWpmChange = {
                        scope.launch { settingsStore.updateReadingWordsPerMinute(it.roundToInt()) }
                    },
                    paceMode = paceMode,
                    onPaceModeChange = { scope.launch { settingsStore.updatePaceMode(it) } },
                    translationDisplayMode = readerSettings.translationDisplayMode,
                    onTranslationDisplayModeChange = {
                        scope.launch { settingsStore.updateTranslationDisplayMode(it) }
                    },
                    providerStatusText = providerStatusText,
                    translationCacheStatusText = translationCacheStatusText,
                    busy = busy,
                    canTranslate = settings.isProviderConfigured && currentPage.hasText,
                    canClearCache = (translationCacheStatus?.cachedSegments ?: 0) > 0,
                    onLanguagePreset = { preset ->
                        scope.launch {
                            settingsStore.updateLanguages(
                                sourceLanguage = preset.sourceLanguage,
                                targetLanguage = preset.targetLanguage,
                            )
                        }
                    },
                    onTranslate = ::translateCurrentPage,
                    onPrefetch = ::prefetchDocument,
                    onLoadCached = ::loadCachedCurrentPage,
                    onClearCache = ::clearTranslationCache,
                )
                RemoteSourcesTodoPanel()
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
            onDismiss = { showDocumentDetails = false },
        )
    }
}

private fun Context.localizedPrefetchStatus(update: PrefetchProgress): String {
    return when (update.stage) {
        PrefetchStage.PREPARING -> getString(
            R.string.status_prefetch_preparing_page,
            update.activePageNumber,
            update.totalPages,
        )
        PrefetchStage.SAVED -> getString(
            R.string.status_prefetch_saved_page,
            update.activePageNumber,
            update.totalPages,
        )
    }
}

private fun Throwable.readableMessage(context: Context): String {
    val detail = message?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.status_generic_error)
    return context.getString(R.string.status_translation_error, detail)
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
