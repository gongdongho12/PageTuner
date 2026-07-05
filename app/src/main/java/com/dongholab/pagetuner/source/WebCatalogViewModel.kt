package com.dongholab.pagetuner.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DefaultCatalogUrl = "http://10.0.2.2:8088/catalog.json"
private const val MaxThumbnailBytes = 2 * 1024 * 1024

data class WebCatalogUiState(
    val catalogUrl: String = DefaultCatalogUrl,
    val query: String = "",
    val catalog: PageTurnerCatalog? = null,
    val visibleItems: List<RemoteBookItem> = emptyList(),
    val coverThumbnails: Map<String, ByteArray> = emptyMap(),
    val cachedCatalogs: List<CachedWebCatalog> = emptyList(),
    val busy: Boolean = false,
    val status: WebCatalogStatus = WebCatalogStatus.Idle,
)

sealed interface WebCatalogStatus {
    data object Idle : WebCatalogStatus
    data object Loading : WebCatalogStatus
    data object MissingCatalogUrl : WebCatalogStatus
    data class LoadedRemote(
        val title: String,
        val itemCount: Int,
    ) : WebCatalogStatus

    data class LoadedCached(
        val title: String,
        val itemCount: Int,
    ) : WebCatalogStatus

    data class Importing(
        val title: String,
    ) : WebCatalogStatus

    data class Downloaded(
        val title: String,
    ) : WebCatalogStatus

    data class Error(
        val detail: String?,
    ) : WebCatalogStatus

    data class NetworkUnavailable(
        val detail: String?,
    ) : WebCatalogStatus
}

sealed interface WebCatalogEvent {
    data class ImportDownloaded(
        val item: RemoteBookItem,
        val bytes: ByteArray,
    ) : WebCatalogEvent
}

class WebCatalogViewModel(
    private val cache: RemoteCatalogCache,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WebCatalogUiState())
    val uiState: StateFlow<WebCatalogUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WebCatalogEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<WebCatalogEvent> = _events.asSharedFlow()

    init {
        refreshCachedCatalogs()
    }

    fun updateCatalogUrl(url: String) {
        _uiState.update { state -> state.copy(catalogUrl = url) }
    }

    fun updateQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                query = query,
                visibleItems = state.catalog.filterItems(query),
            )
        }
        prefetchCoverThumbnails(_uiState.value.visibleItems)
    }

    fun loadCatalog() {
        loadCatalog(forceRefresh = false)
    }

    fun refreshCatalog() {
        loadCatalog(forceRefresh = true)
    }

    fun loadCachedCatalog(cached: CachedWebCatalog) {
        if (_uiState.value.busy) return
        applyCachedCatalog(cached, busy = false)
    }

    fun importItem(item: RemoteBookItem) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    busy = true,
                    status = WebCatalogStatus.Importing(item.title),
                )
            }
            runCatching {
                PageTurnerWebCatalogNetwork.fetchBytes(item.downloadUrl)
            }.onSuccess { bytes ->
                _events.emit(WebCatalogEvent.ImportDownloaded(item, bytes))
                _uiState.update { state ->
                    state.copy(
                        busy = false,
                        status = WebCatalogStatus.Downloaded(item.title),
                    )
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        busy = false,
                        status = error.toWebCatalogStatus(),
                    )
                }
            }
        }
    }

    fun refreshCachedCatalogs() {
        viewModelScope.launch {
            runCatching {
                cache.list()
            }.onSuccess { cachedCatalogs ->
                _uiState.update { state -> state.copy(cachedCatalogs = cachedCatalogs) }
            }
        }
    }

    private fun loadCatalog(forceRefresh: Boolean) {
        if (_uiState.value.busy) return
        val url = _uiState.value.catalogUrl.trim()
        if (url.isBlank()) {
            _uiState.update { state ->
                state.copy(status = WebCatalogStatus.MissingCatalogUrl)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    busy = true,
                    status = WebCatalogStatus.Loading,
                )
            }

            val cached = if (forceRefresh) null else cache.get(url)
            if (cached != null) {
                applyCachedCatalog(cached, busy = false)
                return@launch
            }

            runCatching {
                val rawJson = PageTurnerWebCatalogNetwork.fetchString(url)
                val catalog = PageTurnerWebCatalogParser.parse(
                    rawJson = rawJson,
                    catalogUrl = url,
                )
                cache.save(
                    url = url,
                    rawJson = rawJson,
                    catalog = catalog,
                )
                catalog
            }.onSuccess { catalog ->
                val cachedCatalogs = cache.list()
                _uiState.update { state ->
                    state.copy(
                        catalog = catalog,
                        visibleItems = catalog.filterItems(state.query),
                        cachedCatalogs = cachedCatalogs,
                        busy = false,
                        status = WebCatalogStatus.LoadedRemote(
                            title = catalog.title,
                            itemCount = catalog.items.size,
                        ),
                    )
                }
                prefetchCoverThumbnails(catalog.filterItems(_uiState.value.query))
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        busy = false,
                        status = error.toWebCatalogStatus(),
                    )
                }
            }
        }
    }

    private fun PageTurnerCatalog?.filterItems(query: String): List<RemoteBookItem> {
        val items = this?.items ?: return emptyList()
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return items
        return items.filter { item ->
            item.title.lowercase().contains(normalized) ||
                item.authors.any { author -> author.lowercase().contains(normalized) } ||
                item.language.orEmpty().lowercase().contains(normalized) ||
                item.format.name.lowercase().contains(normalized)
        }
    }

    private fun applyCachedCatalog(cached: CachedWebCatalog, busy: Boolean) {
        val catalog = runCatching {
            PageTurnerWebCatalogParser.parse(
                rawJson = cached.rawJson,
                catalogUrl = cached.url,
            )
        }.getOrElse { error ->
            _uiState.update { state ->
                state.copy(
                    busy = busy,
                    status = error.toWebCatalogStatus(),
                )
            }
            return
        }
        _uiState.update { state ->
            state.copy(
                catalogUrl = cached.url,
                catalog = catalog,
                visibleItems = catalog.filterItems(state.query),
                busy = busy,
                status = WebCatalogStatus.LoadedCached(
                    title = catalog.title,
                    itemCount = catalog.items.size,
                ),
            )
        }
        prefetchCoverThumbnails(catalog.filterItems(_uiState.value.query))
    }

    private fun prefetchCoverThumbnails(items: List<RemoteBookItem>) {
        val urls = items
            .take(5)
            .mapNotNull { it.coverUrl }
            .filter { url -> url !in _uiState.value.coverThumbnails }
            .distinct()
        if (urls.isEmpty()) return

        viewModelScope.launch {
            urls.forEach { url ->
                runCatching {
                    PageTurnerWebCatalogNetwork.fetchBytes(
                        url = url,
                        maxBytes = MaxThumbnailBytes,
                    )
                }.onSuccess { bytes ->
                    _uiState.update { state ->
                        state.copy(coverThumbnails = state.coverThumbnails + (url to bytes))
                    }
                }
            }
        }
    }

    private fun Throwable.toWebCatalogStatus(): WebCatalogStatus {
        return when (this) {
            is UnknownHostException,
            is SocketTimeoutException,
            is SocketException -> WebCatalogStatus.NetworkUnavailable(message)
            else -> WebCatalogStatus.Error(message)
        }
    }

    class Factory(
        private val cache: RemoteCatalogCache,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WebCatalogViewModel::class.java)) {
                return WebCatalogViewModel(cache) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
