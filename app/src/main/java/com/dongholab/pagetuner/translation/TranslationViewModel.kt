package com.dongholab.pagetuner.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslationUiState(
    val apiKey: String = "",
    val translation: PageTranslation? = null,
    val cacheStatus: TranslationCacheStatus? = null,
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

    data class ClearedCache(
        val deletedSegments: Int,
    ) : TranslationStatus

    data class Error(
        val detail: String?,
    ) : TranslationStatus
}

class TranslationViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TranslationUiState())
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    fun updateApiKey(apiKey: String) {
        _uiState.update { state -> state.copy(apiKey = apiKey) }
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
        _uiState.update { state ->
            TranslationUiState(apiKey = state.apiKey)
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
                _uiState.update { state -> state.copy(status = TranslationStatus.Error(error.message)) }
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
                _uiState.update { state -> state.copy(status = TranslationStatus.Error(error.message)) }
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
                val result = repository.translatePage(document, page, settings) { update ->
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
                        status = TranslationStatus.Error(error.message),
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
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    busy = true,
                    progress = 0f,
                    status = TranslationStatus.PreparingOfflineCache,
                )
            }

            runCatching {
                val prefetchSettings = settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH)
                repository.prefetchDocument(
                    document = document,
                    startPageIndex = startPageIndex,
                    settings = prefetchSettings,
                ) { update: PrefetchProgress ->
                    _uiState.update { state ->
                        state.copy(
                            progress = update.fraction,
                            status = when (update.stage) {
                                PrefetchStage.PREPARING -> TranslationStatus.PrefetchPreparingPage(
                                    activePageNumber = update.activePageNumber,
                                    totalPages = update.totalPages,
                                )
                                PrefetchStage.SAVED -> TranslationStatus.PrefetchSavedPage(
                                    activePageNumber = update.activePageNumber,
                                    totalPages = update.totalPages,
                                )
                            },
                        )
                    }
                }
                val cacheStatus = repository.cacheStatus(document, settings)
                val cached = repository.loadCachedPage(document, currentPage, settings)
                cacheStatus to cached
            }.onSuccess { (cacheStatus, cached) ->
                _uiState.update { state ->
                    state.copy(
                        translation = cached,
                        cacheStatus = cacheStatus,
                        progress = 1f,
                        busy = false,
                        status = TranslationStatus.OfflineCacheReady,
                    )
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        busy = false,
                        status = TranslationStatus.Error(error.message),
                    )
                }
            }
        }
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
                        status = TranslationStatus.Error(error.message),
                    )
                }
            }
        }
    }
}
