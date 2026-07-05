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
        _uiState.update { state ->
            state.copy(
                translation = null,
                progress = 0f,
                status = TranslationStatus.Ready,
            )
        }
    }

    fun resetForDocument() {
        prefetchJob?.cancel()
        prefetchJob = null
        _uiState.update { state ->
            TranslationUiState(
                apiKey = state.apiKey,
                providerHealth = state.providerHealth,
            )
        }
    }

    fun refreshCacheStatus(
        document: ReaderDocument,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.cacheStatus(document, settings)
            }.onSuccess { cacheStatus ->
                _uiState.update { state -> state.copy(cacheStatus = cacheStatus) }
            }.onFailure { error ->
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
        viewModelScope.launch {
            runCatching {
                val cached = repository.loadCachedPage(document, page, settings)
                val cacheStatus = repository.cacheStatus(document, settings)
                cached to cacheStatus
            }.onSuccess { (cached, cacheStatus) ->
                _uiState.update { state ->
                    state.copy(
                        translation = cached,
                        cacheStatus = cacheStatus,
                        progress = if (cached != null) 1f else 0f,
                        status = when {
                            cached != null && showMissingStatus -> TranslationStatus.LoadedCached
                            cached != null -> TranslationStatus.CachedSegments(
                                cachedSegments = page.segments.size,
                                totalSegments = page.segments.size,
                            )
                            showMissingStatus -> TranslationStatus.NoCached
                            else -> TranslationStatus.Ready
                        },
                    )
                }
            }.onFailure { error ->
                _uiState.update { state -> state.copy(status = error.toTranslationErrorStatus()) }
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
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    busy = true,
                    progress = 0f,
                    translation = null,
                    status = TranslationStatus.Starting(settings.paceMode),
                )
            }

            runCatching {
                val result = translatePageWithRetry(
                    document = document,
                    page = page,
                    settings = settings,
                    repository = repository,
                    pageNumber = page.index + 1,
                ) { update ->
                    updateCurrentPageProgress(page, settings, update)
                }
                val cacheStatus = repository.cacheStatus(document, settings)
                result to cacheStatus
            }.onSuccess { (result, cacheStatus) ->
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

    fun prefetchDocument(
        document: ReaderDocument,
        currentPage: ReaderPage,
        startPageIndex: Int,
        settings: TranslationSettings,
        repository: TranslationRepository,
    ) {
        if (_uiState.value.busy) return
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
                pages.forEachIndexed { index, page ->
                    waitIfPrefetchPaused()
                    if (prefetchJob?.isActive != true) throw CancellationException()

                    _uiState.update { state ->
                        state.copy(
                            queue = state.queue.updateItem(page.index) { item ->
                                item.copy(status = TranslationQueueItemStatus.Active)
                            },
                            status = TranslationStatus.PrefetchPreparingPage(
                                activePageNumber = page.index + 1,
                                totalPages = pages.size,
                            ),
                        )
                    }

                    runCatching {
                        translatePageWithRetry(
                            document = document,
                            page = page,
                            settings = prefetchSettings,
                            repository = repository,
                            pageNumber = page.index + 1,
                        )
                    }.onSuccess {
                        _uiState.update { state ->
                            val queue = state.queue.updateItem(page.index) { item ->
                                item.copy(
                                    status = TranslationQueueItemStatus.Saved,
                                    attempts = item.attempts.coerceAtLeast(1),
                                    error = null,
                                )
                            }
                            state.copy(
                                queue = queue,
                                progress = queue.fraction,
                                status = TranslationStatus.PrefetchSavedPage(
                                    activePageNumber = page.index + 1,
                                    totalPages = pages.size,
                                ),
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        _uiState.update { state ->
                            val queue = state.queue.updateItem(page.index) { item ->
                                item.copy(
                                    status = TranslationQueueItemStatus.Failed,
                                    attempts = item.attempts.coerceAtLeast(1),
                                    error = error.message,
                                )
                            }
                            state.copy(
                                queue = queue,
                                progress = queue.fraction,
                                status = TranslationStatus.PrefetchFailedPage(
                                    pageNumber = page.index + 1,
                                    detail = error.message,
                                    providerFailure = error.providerFailureOrNull(),
                                ),
                            )
                        }
                    }

                    if (index < pages.lastIndex) waitIfPrefetchPaused()
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
                translation = PageTranslation(
                    page = page,
                    sourceLanguage = settings.normalizedSourceLanguage,
                    targetLanguage = settings.normalizedTargetLanguage,
                    segments = update.currentText.split("\n\n").mapIndexed { index, text ->
                        val segmentId = page.segments.getOrNull(index)?.id ?: "progress-$index"
                        TranslatedSegment(segmentId, text)
                    },
                    completedFromCache = false,
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
    }
}

private fun Throwable.toTranslationErrorStatus(): TranslationStatus.Error {
    return TranslationStatus.Error(
        detail = message,
        providerFailure = providerFailureOrNull(),
    )
}
