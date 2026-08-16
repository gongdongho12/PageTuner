package com.dongholab.pagetuner.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslationUiState(
    val apiKey: String = "",
    val translation: PageTranslation? = null,
    val cacheStatus: TranslationCacheStatus? = null,
    val providerHealth: ProviderHealthCheck = ProviderHealthCheck(),
    val queue: TranslationQueueState = TranslationQueueState(),
    val rolling: RollingTranslationState = RollingTranslationState(),
    val readerLoad: ReaderTranslationLoadState = ReaderTranslationLoadState(),
    val status: TranslationStatus = TranslationStatus.Ready,
    val progress: Float = 0f,
    val busy: Boolean = false,
)

sealed interface TranslationStatus {
    data object Ready : TranslationStatus
    data object LoadedCached : TranslationStatus
    data object NoCached : TranslationStatus
    data object ServedFromCache : TranslationStatus
    data object PreparingOfflineCache : TranslationStatus
    data object OfflineCacheReady : TranslationStatus
    data object PrefetchPaused : TranslationStatus
    data object PrefetchCancelled : TranslationStatus

    data class Starting(
        val paceMode: TranslationPaceMode,
    ) : TranslationStatus

    data class CachedSegments(
        val cachedSegments: Int,
        val totalSegments: Int,
    ) : TranslationStatus

    data class TranslatedSegments(
        val completedSegments: Int,
        val totalSegments: Int,
    ) : TranslationStatus

    data class TranslatedSavedPage(
        val pageNumber: Int,
    ) : TranslationStatus

    data class PrefetchPreparingPage(
        val activePageNumber: Int,
        val totalPages: Int,
    ) : TranslationStatus

    data class PrefetchSavedPage(
        val activePageNumber: Int,
        val totalPages: Int,
    ) : TranslationStatus

    data class PrefetchFailedPage(
        val pageNumber: Int,
        val detail: String?,
        val providerFailure: TranslationProviderFailure? = null,
    ) : TranslationStatus

    data class PrefetchCompletedWithFailures(
        val failedPages: Int,
        val totalPages: Int,
    ) : TranslationStatus

    data class RetryingPage(
        val pageNumber: Int,
        val attemptNumber: Int,
    ) : TranslationStatus

    data class ClearedCache(
        val deletedSegments: Int,
    ) : TranslationStatus

    data class Error(
        val detail: String?,
        val providerFailure: TranslationProviderFailure? = null,
    ) : TranslationStatus
}

class TranslationViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TranslationUiState())
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()
    private var prefetchJob: Job? = null
    private var pageContentJob: Job? = null
    private var rollingPrefetchJob: Job? = null
    private var documentRevision: Long = 0L
    private var pageContentRequestId: Long = 0L
    private val rollingPolicy = RollingTranslationPolicy()
    private val rollingPendingPageIndexes = ArrayDeque<Int>()
    private var rollingDocumentId: String? = null
    private var rollingRepository: TranslationRepository? = null
    private var rollingVisiblePageIndex: Int = 0

    fun updateApiKey(apiKey: String) {
        _uiState.update { state -> state.copy(apiKey = apiKey) }
    }

    fun checkProviderHealth(settings: TranslationSettings) {
        _uiState.update { state ->
            state.copy(providerHealth = settings.checkProviderHealth())
        }
    }

    fun clearStatus() {
        _uiState.update { state -> state.copy(status = TranslationStatus.Ready) }
    }

    fun clearPageTranslation() {
        pageContentRequestId += 1L
        pageContentJob?.cancel()
        pageContentJob = null
        _uiState.update { state ->
            state.copy(
                translation = null,
                progress = 0f,
                busy = false,
                status = TranslationStatus.Ready,
                readerLoad = ReaderTranslationLoadState(),
            )
        }
    }

    fun resetForDocument(documentId: String? = null, pageIndex: Int = 0) {
        documentRevision += 1L
        pageContentRequestId += 1L
        pageContentJob?.cancel()
        pageContentJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        rollingPrefetchJob?.cancel()
        rollingPrefetchJob = null
        rollingPendingPageIndexes.clear()
        rollingDocumentId = null
        rollingRepository = null
        _uiState.update { state ->
            TranslationUiState(
                apiKey = state.apiKey,
                providerHealth = state.providerHealth,
                readerLoad = if (documentId == null) {
                    ReaderTranslationLoadState()
                } else {
                    ReaderTranslationLoadState(
                        documentId = documentId,
                        pageIndex = pageIndex,
                        stage = ReaderTranslationLoadStage.CheckingCache,
                    )
                },
            )
        }
    }

    fun refreshCacheStatus(
        document: ReaderDocument,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        val revision = documentRevision
        viewModelScope.launch {
            runCatching {
                repository.cacheStatus(document, settings)
            }.onSuccess { cacheStatus ->
                if (revision != documentRevision) return@onSuccess
                _uiState.update { state -> state.copy(cacheStatus = cacheStatus) }
            }.onFailure { error ->
                if (error is CancellationException || revision != documentRevision) return@onFailure
                _uiState.update { state -> state.copy(status = error.toTranslationErrorStatus()) }
            }
        }
    }

    fun loadCachedPage(
        document: ReaderDocument,
        page: ReaderPage,
        settings: TranslationSettings,
        repository: TranslationRepository,
        showMissingStatus: Boolean,
    ) {
        val revision = documentRevision
        val requestId = ++pageContentRequestId
        pageContentJob?.cancel()
        _uiState.update { state ->
            state.copy(
                busy = false,
                progress = 0f,
                readerLoad = ReaderTranslationLoadState(
                    documentId = document.id,
                    pageIndex = page.index,
                    stage = ReaderTranslationLoadStage.CheckingCache,
                ),
            )
        }
        pageContentJob = viewModelScope.launch {
            runCatching {
                val cached = repository.loadCachedPage(document, page, settings)
                val cacheStatus = repository.cacheStatus(document, settings)
                cached to cacheStatus
            }.onSuccess { (cached, cacheStatus) ->
                if (revision != documentRevision || requestId != pageContentRequestId) return@onSuccess
                _uiState.update { state ->
                    state.copy(
                        translation = cached,
                        cacheStatus = cacheStatus,
                        progress = if (cached != null) 1f else 0f,
                        status = when {
                            cached != null && showMissingStatus -> TranslationStatus.LoadedCached
                            showMissingStatus -> TranslationStatus.NoCached
                            else -> TranslationStatus.Ready
                        },
                        readerLoad = ReaderTranslationLoadState(
                            documentId = document.id,
                            pageIndex = page.index,
                            stage = if (cached != null) {
                                ReaderTranslationLoadStage.Ready
                            } else {
                                ReaderTranslationLoadStage.Missing
                            },
                        ),
                    )
                }
            }.onFailure { error ->
                if (
                    error is CancellationException ||
                    revision != documentRevision ||
                    requestId != pageContentRequestId
                ) return@onFailure
                _uiState.update { state ->
                    state.copy(
                        status = error.toTranslationErrorStatus(),
                        readerLoad = ReaderTranslationLoadState(
                            documentId = document.id,
                            pageIndex = page.index,
                            stage = ReaderTranslationLoadStage.Failed,
                        ),
                    )
                }
            }
        }
    }

    fun translatePage(
        document: ReaderDocument,
        page: ReaderPage,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        if (_uiState.value.busy) return
        val revision = documentRevision
        val requestId = ++pageContentRequestId
        pageContentJob?.cancel()
        pageContentJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    busy = true,
                    progress = 0f,
                    translation = null,
                    status = TranslationStatus.Starting(settings.paceMode),
                    readerLoad = ReaderTranslationLoadState(
                        documentId = document.id,
                        pageIndex = page.index,
                        stage = ReaderTranslationLoadStage.Translating,
                    ),
                )
            }

            com.dongholab.pagetuner.common.DiagnosticLogger.log("[STEP 3: TRANSLATION START]", "Page ${page.index + 1}/${document.pageCount}, Provider: ${settings.providerKind}, Lang: ${settings.sourceLanguage}->${settings.targetLanguage}")

            runCatching {
                val result = translatePageWithRetry(
                    document = document,
                    page = page,
                    settings = settings,
                    repository = repository,
                    pageNumber = page.index + 1,
                ) { update ->
                    if (requestId == pageContentRequestId) {
                        updateCurrentPageProgress(page, settings, update)
                    }
                }
                val cacheStatus = repository.cacheStatus(document, settings)
                result to cacheStatus
            }.onSuccess { (result, cacheStatus) ->
                if (revision != documentRevision || requestId != pageContentRequestId) return@onSuccess
                com.dongholab.pagetuner.common.DiagnosticLogger.log("[STEP 3: TRANSLATION SUCCESS]", "Page ${page.index + 1} Translated (${result.segments.size} segments, FromCache: ${result.completedFromCache})")
                _uiState.update { state ->
                    state.copy(
                        translation = result,
                        cacheStatus = cacheStatus,
                        progress = 1f,
                        busy = false,
                        status = if (result.completedFromCache) {
                            TranslationStatus.ServedFromCache
                        } else {
                            TranslationStatus.TranslatedSavedPage(page.index + 1)
                        },
                        readerLoad = ReaderTranslationLoadState(
                            documentId = document.id,
                            pageIndex = page.index,
                            stage = ReaderTranslationLoadStage.Ready,
                        ),
                    )
                }
            }.onFailure { error ->
                if (
                    error is CancellationException ||
                    revision != documentRevision ||
                    requestId != pageContentRequestId
                ) return@onFailure
                com.dongholab.pagetuner.common.DiagnosticLogger.log("[STEP 3: TRANSLATION FAILURE]", "Page ${page.index + 1} Error: ${error.message}")
                _uiState.update { state ->
                    state.copy(
                        busy = false,
                        status = error.toTranslationErrorStatus(),
                        readerLoad = ReaderTranslationLoadState(
                            documentId = document.id,
                            pageIndex = page.index,
                            stage = ReaderTranslationLoadStage.Failed,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Starts a non-blocking 10-page translation window. The current page is
     * translated first, then the remaining pages are cached in the background.
     */
    fun startRollingPrefetch(
        document: ReaderDocument,
        currentPageIndex: Int,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        if (!settings.isProviderConfigured || document.pages.isEmpty()) return
        val safePageIndex = currentPageIndex.coerceIn(0, document.pages.lastIndex)
        val sameSession = rollingDocumentId == document.id && rollingRepository === repository
        if (!sameSession) {
            rollingPrefetchJob?.cancel()
            rollingPrefetchJob = null
            rollingPendingPageIndexes.clear()
            rollingDocumentId = document.id
            rollingRepository = repository
            _uiState.update { state -> state.copy(rolling = RollingTranslationState()) }
        }
        rollingVisiblePageIndex = safePageIndex
        if (_uiState.value.translation == null) {
            updateReaderLoad(document.id, safePageIndex, ReaderTranslationLoadStage.Queued)
        }
        val window = rollingPolicy.initialWindow(safePageIndex, document.pageCount) ?: return
        enqueueRollingWindow(document, window, settings, repository)
    }

    /** Called by the reader on every page change to maintain the look-ahead window. */
    fun onReaderPageChanged(
        document: ReaderDocument,
        currentPageIndex: Int,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        if (document.pages.isEmpty()) return
        rollingVisiblePageIndex = currentPageIndex.coerceIn(0, document.pages.lastIndex)
        val rolling = _uiState.value.rolling
        if (!rolling.enabled) return
        val pageFlag = rolling.flagFor(rollingVisiblePageIndex)
        when (pageFlag) {
            TranslationPageFlag.Queued -> updateReaderLoad(
                document.id,
                rollingVisiblePageIndex,
                ReaderTranslationLoadStage.Queued,
            )
            TranslationPageFlag.Translating -> updateReaderLoad(
                document.id,
                rollingVisiblePageIndex,
                ReaderTranslationLoadStage.Translating,
            )
            TranslationPageFlag.Failed -> updateReaderLoad(
                document.id,
                rollingVisiblePageIndex,
                ReaderTranslationLoadStage.Failed,
            )
            TranslationPageFlag.Ready, null -> Unit // Passive cache loading owns these states.
        }
        if (rollingDocumentId != document.id || rollingRepository !== repository) {
            startRollingPrefetch(document, rollingVisiblePageIndex, settings, repository)
            return
        }
        val nextWindow = rollingPolicy.nextWindow(
            currentPageIndex = rollingVisiblePageIndex,
            totalPages = document.pageCount,
            state = rolling,
        ) ?: return
        enqueueRollingWindow(document, nextWindow, settings, repository)
    }

    private fun enqueueRollingWindow(
        document: ReaderDocument,
        window: RollingTranslationWindow,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        val existingFlags = _uiState.value.rolling.pageFlags
        val requested = window.pageIndexes.filter { pageIndex ->
            document.pages[pageIndex].hasText &&
                existingFlags[pageIndex] !in setOf(
                    TranslationPageFlag.Queued,
                    TranslationPageFlag.Translating,
                    TranslationPageFlag.Ready,
                )
        }
        requested.forEach { pageIndex ->
            if (pageIndex !in rollingPendingPageIndexes) rollingPendingPageIndexes.addLast(pageIndex)
        }
        _uiState.update { state ->
            val flags = state.rolling.pageFlags.toMutableMap().apply {
                requested.forEach { pageIndex -> put(pageIndex, TranslationPageFlag.Queued) }
            }
            state.copy(
                rolling = state.rolling.copy(
                    enabled = true,
                    running = rollingPendingPageIndexes.isNotEmpty() || rollingPrefetchJob?.isActive == true,
                    windowSize = rollingPolicy.windowSize,
                    triggerOffset = rollingPolicy.triggerOffset,
                    windowStartIndex = window.startIndex,
                    windowEndExclusive = window.endExclusive,
                    nextWindowStartIndex = window.nextWindowStartIndex,
                    triggerPageIndex = window.triggerPageIndex,
                    pageFlags = flags,
                    lastError = null,
                ),
            )
        }
        launchRollingWorker(document, settings, repository)
    }

    private fun launchRollingWorker(
        document: ReaderDocument,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        if (rollingPrefetchJob?.isActive == true || rollingPendingPageIndexes.isEmpty()) return
        val revision = documentRevision
        rollingPrefetchJob = viewModelScope.launch {
            val prefetchSettings = settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH)
            try {
                while (rollingPendingPageIndexes.isNotEmpty()) {
                    if (revision != documentRevision || rollingDocumentId != document.id) {
                        throw CancellationException()
                    }
                    val pageIndexes = buildList {
                        while (size < RollingRequestPageCount && rollingPendingPageIndexes.isNotEmpty()) {
                            add(rollingPendingPageIndexes.removeFirst())
                        }
                    }
                    val pages = pageIndexes.map(document.pages::get)
                    val firstPageIndex = pageIndexes.first()
                    _uiState.update { state ->
                        val translatingFlags = state.rolling.pageFlags.toMutableMap().apply {
                            pageIndexes.forEach { pageIndex ->
                                put(pageIndex, TranslationPageFlag.Translating)
                            }
                        }
                        val visiblePageIsInRequest = rollingVisiblePageIndex in pageIndexes
                        state.copy(
                            rolling = state.rolling.copy(
                                running = true,
                                activePageIndex = firstPageIndex,
                                pageFlags = translatingFlags,
                            ),
                            status = if (visiblePageIsInRequest) {
                                TranslationStatus.Starting(prefetchSettings.paceMode)
                            } else {
                                state.status
                            },
                            readerLoad = if (visiblePageIsInRequest) {
                                ReaderTranslationLoadState(
                                    documentId = document.id,
                                    pageIndex = rollingVisiblePageIndex,
                                    stage = ReaderTranslationLoadStage.Translating,
                                )
                            } else {
                                state.readerLoad
                            },
                        )
                    }

                    runCatching {
                        translatePagesWithRetry(document, pages, prefetchSettings, repository)
                    }.onSuccess { results ->
                        if (revision != documentRevision) return@onSuccess
                        _uiState.update { state ->
                            val resultsByPageIndex = results.associateBy { result -> result.page.index }
                            val visibleResult = resultsByPageIndex[rollingVisiblePageIndex]
                            val readyFlags = state.rolling.pageFlags.toMutableMap().apply {
                                pageIndexes.forEach { pageIndex ->
                                    put(pageIndex, TranslationPageFlag.Ready)
                                }
                            }
                            state.copy(
                                translation = visibleResult ?: state.translation,
                                progress = if (visibleResult != null) 1f else state.progress,
                                status = if (visibleResult != null) {
                                    TranslationStatus.TranslatedSavedPage(rollingVisiblePageIndex + 1)
                                } else {
                                    state.status
                                },
                                rolling = state.rolling.copy(
                                    pageFlags = readyFlags,
                                    lastError = null,
                                ),
                                readerLoad = if (visibleResult != null) {
                                    ReaderTranslationLoadState(
                                        documentId = document.id,
                                        pageIndex = rollingVisiblePageIndex,
                                        stage = ReaderTranslationLoadStage.Ready,
                                    )
                                } else {
                                    state.readerLoad
                                },
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        _uiState.update { state ->
                            val visiblePageIsInRequest = rollingVisiblePageIndex in pageIndexes
                            val failedFlags = state.rolling.pageFlags.toMutableMap().apply {
                                pageIndexes.forEach { pageIndex ->
                                    put(pageIndex, TranslationPageFlag.Failed)
                                }
                            }
                            state.copy(
                                status = if (visiblePageIsInRequest) {
                                    error.toTranslationErrorStatus()
                                } else {
                                    state.status
                                },
                                rolling = state.rolling.copy(
                                    pageFlags = failedFlags,
                                    lastError = error.message,
                                ),
                                readerLoad = if (visiblePageIsInRequest) {
                                    ReaderTranslationLoadState(
                                        documentId = document.id,
                                        pageIndex = rollingVisiblePageIndex,
                                        stage = ReaderTranslationLoadStage.Failed,
                                    )
                                } else {
                                    state.readerLoad
                                },
                            )
                        }
                    }
                }
                val cacheStatus = repository.cacheStatus(document, settings)
                _uiState.update { state ->
                    state.copy(
                        cacheStatus = cacheStatus,
                        rolling = state.rolling.copy(running = false, activePageIndex = null),
                    )
                }
            } catch (_: CancellationException) {
                // Document reset owns the replacement state.
            } finally {
                rollingPrefetchJob = null
                if (revision == documentRevision && rollingPendingPageIndexes.isNotEmpty()) {
                    launchRollingWorker(document, settings, repository)
                }
            }
        }
    }

    private suspend fun translatePagesWithRetry(
        document: ReaderDocument,
        pages: List<ReaderPage>,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ): List<PageTranslation> {
        var lastError: Throwable? = null
        repeat(MaxTranslationAttempts) { attempt ->
            if (attempt > 0) delay(RetryDelayMillis * attempt)
            try {
                return repository.translatePages(document, pages, settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Rolling translation failed.")
    }

    fun prefetchDocument(
        document: ReaderDocument,
        currentPage: ReaderPage,
        startPageIndex: Int,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        if (_uiState.value.busy) return
        rollingPrefetchJob?.cancel()
        rollingPrefetchJob = null
        rollingPendingPageIndexes.clear()
        val orderedPages = document.pages.drop(startPageIndex) + document.pages.take(startPageIndex)
        startPrefetchQueue(
            document = document,
            currentPage = currentPage,
            pages = orderedPages,
            settings = settings,
            repository = repository,
            retrying = false,
        )
    }

    fun pausePrefetch() {
        _uiState.update { state ->
            if (!state.queue.canPause) {
                state
            } else {
                state.copy(
                    queue = state.queue.copy(paused = true),
                    status = TranslationStatus.PrefetchPaused,
                )
            }
        }
    }

    fun resumePrefetch() {
        _uiState.update { state ->
            if (!state.queue.canResume) {
                state
            } else {
                state.copy(
                    queue = state.queue.copy(paused = false),
                    status = TranslationStatus.PreparingOfflineCache,
                )
            }
        }
    }

    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        _uiState.update { state ->
            val cancelledItems = state.queue.items.map { item ->
                if (item.status == TranslationQueueItemStatus.Pending ||
                    item.status == TranslationQueueItemStatus.Active
                ) {
                    item.copy(status = TranslationQueueItemStatus.Cancelled)
                } else {
                    item
                }
            }
            state.copy(
                busy = false,
                queue = state.queue.copy(
                    items = cancelledItems,
                    running = false,
                    paused = false,
                    cancelled = true,
                ),
                status = TranslationStatus.PrefetchCancelled,
            )
        }
    }

    fun retryFailedPrefetch(
        document: ReaderDocument,
        currentPage: ReaderPage,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        if (_uiState.value.busy) return
        val failedPageIndexes = _uiState.value.queue.items
            .filter { it.status == TranslationQueueItemStatus.Failed }
            .map { it.pageIndex }
            .toSet()
        if (failedPageIndexes.isEmpty()) return

        startPrefetchQueue(
            document = document,
            currentPage = currentPage,
            pages = document.pages.filter { it.index in failedPageIndexes },
            settings = settings,
            repository = repository,
            retrying = true,
        )
    }

    private fun startPrefetchQueue(
        document: ReaderDocument,
        currentPage: ReaderPage,
        pages: List<ReaderPage>,
        settings: TranslationSettings,
        repository: TranslationRepository,
        retrying: Boolean,
    ) {
        if (pages.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val initialItems = pages.map { page ->
                TranslationQueueItem(
                    pageIndex = page.index,
                    pageNumber = page.index + 1,
                )
            }
            _uiState.update { state ->
                state.copy(
                    busy = true,
                    progress = 0f,
                    queue = TranslationQueueState(
                        items = initialItems,
                        running = true,
                        retrying = retrying,
                    ),
                    status = TranslationStatus.PreparingOfflineCache,
                )
            }

            try {
                val prefetchSettings = settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH)
                pages.chunked(RollingRequestPageCount).forEach { pageGroup ->
                    waitIfPrefetchPaused()
                    if (prefetchJob?.isActive != true) throw CancellationException()

                    _uiState.update { state ->
                        val queue = pageGroup.fold(state.queue) { queue, page ->
                            queue.updateItem(page.index) { item ->
                                item.copy(status = TranslationQueueItemStatus.Active)
                            }
                        }
                        state.copy(
                            queue = queue,
                            status = TranslationStatus.PrefetchPreparingPage(
                                activePageNumber = pageGroup.first().index + 1,
                                totalPages = pages.size,
                            ),
                        )
                    }

                    runCatching {
                        translatePagesWithRetry(
                            document = document,
                            pages = pageGroup,
                            settings = prefetchSettings,
                            repository = repository,
                        )
                    }.onSuccess {
                        _uiState.update { state ->
                            val queue = pageGroup.fold(state.queue) { queue, page ->
                                queue.updateItem(page.index) { item ->
                                    item.copy(
                                        status = TranslationQueueItemStatus.Saved,
                                        attempts = item.attempts.coerceAtLeast(1),
                                        error = null,
                                    )
                                }
                            }
                            state.copy(
                                queue = queue,
                                progress = queue.fraction,
                                status = TranslationStatus.PrefetchSavedPage(
                                    activePageNumber = pageGroup.last().index + 1,
                                    totalPages = pages.size,
                                ),
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        _uiState.update { state ->
                            val queue = pageGroup.fold(state.queue) { queue, page ->
                                queue.updateItem(page.index) { item ->
                                    item.copy(
                                        status = TranslationQueueItemStatus.Failed,
                                        attempts = item.attempts.coerceAtLeast(1),
                                        error = error.message,
                                    )
                                }
                            }
                            state.copy(
                                queue = queue,
                                progress = queue.fraction,
                                status = TranslationStatus.PrefetchFailedPage(
                                    pageNumber = pageGroup.first().index + 1,
                                    detail = error.message,
                                    providerFailure = error.providerFailureOrNull(),
                                ),
                            )
                        }
                    }
                }
                val cacheStatus = repository.cacheStatus(document, settings)
                val cached = repository.loadCachedPage(document, currentPage, settings)
                val failedPages = _uiState.value.queue.failedPages
                _uiState.update { state ->
                    state.copy(
                        translation = cached,
                        cacheStatus = cacheStatus,
                        progress = state.queue.fraction,
                        busy = false,
                        queue = state.queue.copy(running = false, paused = false),
                        status = if (failedPages > 0) {
                            TranslationStatus.PrefetchCompletedWithFailures(
                                failedPages = failedPages,
                                totalPages = state.queue.totalPages,
                            )
                        } else {
                            TranslationStatus.OfflineCacheReady
                        },
                    )
                }
            } catch (_: CancellationException) {
                _uiState.update { state ->
                    state.copy(
                        busy = false,
                        queue = state.queue.copy(
                            running = false,
                            paused = false,
                            cancelled = true,
                        ),
                        status = TranslationStatus.PrefetchCancelled,
                    )
                }
            } finally {
                prefetchJob = null
            }
        }
    }

    private suspend fun waitIfPrefetchPaused() {
        while (_uiState.value.queue.paused) {
            delay(250)
        }
    }

    private suspend fun translatePageWithRetry(
        document: ReaderDocument,
        page: ReaderPage,
        settings: TranslationSettings,
        repository: TranslationRepository,
        pageNumber: Int,
        onProgress: suspend (TranslationProgress) -> Unit = {},
    ): PageTranslation {
        var lastError: Throwable? = null
        repeat(MaxTranslationAttempts) { attempt ->
            if (attempt > 0) {
                _uiState.update { state ->
                    state.copy(
                        status = TranslationStatus.RetryingPage(
                            pageNumber = pageNumber,
                            attemptNumber = attempt + 1,
                        ),
                    )
                }
                delay(RetryDelayMillis * attempt)
            }

            try {
                return repository.translatePage(
                    document = document,
                    page = page,
                    settings = settings,
                    onProgress = onProgress,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw lastError ?: IllegalStateException("Translation failed.")
    }

    private fun updateCurrentPageProgress(
        page: ReaderPage,
        settings: TranslationSettings,
        update: TranslationProgress,
    ) {
        _uiState.update { state ->
            state.copy(
                progress = update.fraction,
                status = TranslationStatus.TranslatedSegments(
                    completedSegments = update.completedSegments,
                    totalSegments = update.totalSegments,
                ),
                translation = update.currentText.takeIf(String::isNotBlank)?.let { currentText ->
                    PageTranslation(
                        page = page,
                        sourceLanguage = settings.normalizedSourceLanguage,
                        targetLanguage = settings.normalizedTargetLanguage,
                        segments = currentText.split("\n\n").mapIndexed { index, text ->
                            val segmentId = page.segments.getOrNull(index)?.id ?: "progress-$index"
                            TranslatedSegment(segmentId, text)
                        },
                        completedFromCache = false,
                    )
                } ?: state.translation,
            )
        }
    }

    private fun updateReaderLoad(
        documentId: String,
        pageIndex: Int,
        stage: ReaderTranslationLoadStage,
    ) {
        _uiState.update { state ->
            state.copy(
                readerLoad = ReaderTranslationLoadState(
                    documentId = documentId,
                    pageIndex = pageIndex,
                    stage = stage,
                ),
            )
        }
    }

    private fun TranslationQueueState.updateItem(
        pageIndex: Int,
        transform: (TranslationQueueItem) -> TranslationQueueItem,
    ): TranslationQueueState {
        return copy(
            items = items.map { item ->
                if (item.pageIndex == pageIndex) transform(item) else item
            },
        )
    }

    fun clearTranslationCache(
        document: ReaderDocument,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        if (_uiState.value.busy) return
        rollingPrefetchJob?.cancel()
        rollingPrefetchJob = null
        rollingPendingPageIndexes.clear()
        viewModelScope.launch {
            _uiState.update { state -> state.copy(busy = true) }
            runCatching {
                val deleted = repository.clearDocumentCache(document, settings)
                val cacheStatus = repository.cacheStatus(document, settings)
                deleted to cacheStatus
            }.onSuccess { (deleted, cacheStatus) ->
                _uiState.update { state ->
                    state.copy(
                        translation = null,
                        cacheStatus = cacheStatus,
                        progress = 0f,
                        busy = false,
                        rolling = RollingTranslationState(),
                        status = TranslationStatus.ClearedCache(deleted),
                    )
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        busy = false,
                        status = error.toTranslationErrorStatus(),
                    )
                }
            }
        }
    }

    private companion object {
        const val MaxTranslationAttempts = 2
        const val RetryDelayMillis = 500L
        const val RollingRequestPageCount = 10
    }
}

private fun Throwable.toTranslationErrorStatus(): TranslationStatus.Error {
    return TranslationStatus.Error(
        detail = message,
        providerFailure = providerFailureOrNull(),
    )
}
