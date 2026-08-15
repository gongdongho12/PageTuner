package com.dongholab.pagetuner.source

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.common.DiagnosticLogger
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import com.dongholab.pagetuner.translation.ContentTranslationServiceFactory
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationSettings
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val DefaultCatalogUrl = "https://wtr-lab.com/en/novel-list"
private const val MaxThumbnailBytes = 2 * 1024 * 1024

data class CatalogTranslationProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentTitle: String,
    val failedItems: Int = 0,
) {
    val fraction: Float
        get() = if (totalItems == 0) 1f else completedItems.toFloat() / totalItems
}

private data class ImportPayload(
    val bytes: ByteArray,
    val language: String,
    val usedOfflinePackage: Boolean,
    val usedOfflineTranslation: Boolean,
)

enum class WebCatalogLoadPhase {
    CheckingCache,
    FetchingPage,
    ParsingDom,
    ApplyingResults,
}

data class WebCatalogLoading(
    val phase: WebCatalogLoadPhase,
    val page: Int = 1,
)

data class RemoteCatalogPagingState(
    val currentPage: Int = 1,
    val totalPages: Int? = null,
    val totalItems: Int? = null,
    val pageItemCount: Int = 0,
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
)

private data class LoadedWebNovelCatalog(
    val catalog: PageTurnerCatalog,
    val paging: RemoteCatalogPagingState,
)

data class WebCatalogUiState(
    val catalogUrl: String = DefaultCatalogUrl,
    val query: String = "",
    val selectedGenreKey: String? = null,
    val catalogCapabilities: com.dongholab.pagetuner.source.webnovel.WebNovelCatalogCapabilities =
        com.dongholab.pagetuner.source.webnovel.WtrLabSiteAdapter().catalogCapabilities,
    val catalog: PageTurnerCatalog? = null,
    val visibleItems: List<RemoteBookItem> = emptyList(),
    val coverThumbnails: Map<String, ByteArray> = emptyMap(),
    val cachedCatalogs: List<CachedWebCatalog> = emptyList(),
    val sourceAccounts: List<RemoteSourceAccount> = defaultWebNovelAccounts(),
    val translatedItems: Map<String, CatalogItemTranslation> = emptyMap(),
    val catalogTranslationProgress: CatalogTranslationProgress? = null,
    val batchDownloadProgress: BatchDownloadProgress? = null,
    val remotePaging: RemoteCatalogPagingState? = null,
    val catalogLoading: WebCatalogLoading? = null,
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
        val currentPage: Int? = null,
        val totalPages: Int? = null,
        val totalItems: Int? = null,
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

    data class OfflineSaved(
        val savedItems: Int,
        val translationFailedItems: Int,
    ) : WebCatalogStatus

    data class SavedAccount(
        val title: String,
    ) : WebCatalogStatus

    data class DeletedAccount(
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
        val translateAfterImport: Boolean,
    ) : WebCatalogEvent
}

class WebCatalogViewModel(
    private val cache: RemoteCatalogCache,
    private val accountStore: RemoteSourceAccountStore,
) : ViewModel() {
    private var offlineDownloadJob: Job? = null
    private var catalogTranslationJob: Job? = null
    private var catalogPreloadJob: Job? = null
    private val catalogPageMemoryCache = mutableMapOf<String, LoadedWebNovelCatalog>()
    private val _uiState = MutableStateFlow(WebCatalogUiState())
    val uiState: StateFlow<WebCatalogUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WebCatalogEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<WebCatalogEvent> = _events.asSharedFlow()

    init {
        refreshCachedCatalogs()
        refreshSourceAccounts()
        preloadDefaultCatalog()
    }

    fun updateCatalogUrl(url: String) {
        _uiState.update { state ->
            val adapter = webNovelAdapter(url)
            val request = adapter?.catalogRequest(url)
            if (request != null && adapter.catalogSearchUrl(url, request) != null) {
                state.copy(
                    catalogUrl = url,
                    query = request.query,
                    selectedGenreKey = adapter.catalogCapabilities.genreFilterKey
                        ?.let(request.filters::get),
                    catalogCapabilities = adapter.catalogCapabilities,
                )
            } else {
                state.copy(
                    catalogUrl = url,
                    catalogCapabilities = adapter?.catalogCapabilities
                        ?: com.dongholab.pagetuner.source.webnovel.WebNovelCatalogCapabilities(),
                )
            }
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                query = query,
                visibleItems = if (state.catalogUrl.hasRemoteCatalogSearch()) {
                    state.catalog?.items.orEmpty()
                } else {
                    state.catalog.filterItems(query)
                },
            )
        }
        prefetchCoverThumbnails(_uiState.value.visibleItems)
    }

    fun updateGenreSelection(genreKey: String?) {
        _uiState.update { state -> state.copy(selectedGenreKey = genreKey) }
    }

    fun submitSearch() {
        val state = _uiState.value
        if (state.busy || state.catalogLoading != null) return
        val adapter = webNovelAdapter(state.catalogUrl)
        val currentRequest = adapter?.catalogRequest(state.catalogUrl)
        val request = currentRequest?.copy(
            query = state.query.trim(),
            page = 1,
            filters = currentRequest.filters.toMutableMap().apply {
                adapter.catalogCapabilities.genreFilterKey?.let { genreFilterKey ->
                    state.selectedGenreKey?.let { put(genreFilterKey, it) } ?: remove(genreFilterKey)
                }
            },
        )
        val searchUrl = request?.let { adapter.catalogSearchUrl(state.catalogUrl, it) }
        if (searchUrl == null) {
            _uiState.update { current ->
                current.copy(
                    visibleItems = current.catalog.filterItems(current.query),
                )
            }
            return
        }
        _uiState.update { currentState ->
            currentState.copy(
                catalogUrl = searchUrl,
            )
        }
        catalogPreloadJob?.cancel()
        loadWebNovelPage(
            url = searchUrl,
            accountId = accountIdForCatalog(searchUrl),
            page = 1,
            forceRefresh = false,
        )
    }

    fun clearSearch() {
        _uiState.update { state ->
            state.copy(
                query = "",
                selectedGenreKey = null,
            )
        }
        submitSearch()
    }

    fun loadCatalog() {
        loadCatalog(forceRefresh = false)
    }

    fun refreshCatalog() {
        catalogPreloadJob?.cancel()
        loadCatalog(forceRefresh = true)
    }

    fun loadRemoteCatalogPage(page: Int) {
        val state = _uiState.value
        if (state.busy || state.catalogLoading != null || state.remotePaging == null) return
        val targetPage = page.coerceIn(1, state.remotePaging.totalPages ?: Int.MAX_VALUE)
        if (targetPage == state.remotePaging.currentPage) return
        catalogPreloadJob?.cancel()
        loadWebNovelPage(
            url = state.catalogUrl,
            accountId = accountIdForCatalog(state.catalogUrl),
            page = targetPage,
            forceRefresh = false,
        )
    }

    /** Warms the default WTR-Lab catalog without requiring the Web Novel tab to be opened. */
    fun preloadDefaultCatalog() {
        if (catalogPreloadJob?.isActive == true) return
        catalogPreloadJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    catalogLoading = WebCatalogLoading(WebCatalogLoadPhase.CheckingCache, page = 1),
                    status = WebCatalogStatus.Loading,
                )
            }
            val cached = cache.get(DefaultCatalogUrl)
            if (cached != null && _uiState.value.catalog == null) {
                applyCachedCatalog(cached, busy = false)
            }
            runCatching {
                loadWebNovelCatalog(
                    url = DefaultCatalogUrl,
                    accountId = defaultWtrLabAccount().id,
                    page = 1,
                    onStep = ::updateCatalogLoadStep,
                ).also { loaded -> cache.saveStructured(DefaultCatalogUrl, loaded.catalog) }
            }.onSuccess { loaded ->
                if (_uiState.value.catalogUrl == DefaultCatalogUrl) {
                    applyLoadedWebNovelCatalog(loaded, cachedCatalogs = cache.list())
                }
            }.onFailure { error ->
                if (cached == null && error !is CancellationException) {
                    DiagnosticLogger.log(
                        "[CATALOG PRELOAD FAILED]",
                        error.message ?: error.javaClass.simpleName,
                    )
                    _uiState.update { state ->
                        state.copy(
                            catalogLoading = null,
                            status = error.toWebCatalogStatus(),
                        )
                    }
                } else {
                    _uiState.update { state -> state.copy(catalogLoading = null) }
                }
            }
            catalogPreloadJob = null
        }
    }

    fun loadCachedCatalog(cached: CachedWebCatalog) {
        if (_uiState.value.busy) return
        applyCachedCatalog(cached, busy = false)
    }

    fun loadSourceAccount(account: RemoteSourceAccount) {
        if (_uiState.value.busy) return
        updateCatalogUrl(account.endpoint)
        when (account.sourceType) {
            RemoteSourceType.PageTurnerWebCatalog -> {
                loadCatalog(forceRefresh = false)
            }
            RemoteSourceType.WebNovel -> {
                viewModelScope.launch {
                    val page = com.dongholab.pagetuner.source.webnovel.WebNovelCatalogPageUrls.currentPage(account.endpoint)
                    _uiState.update { state ->
                        state.copy(
                            catalogLoading = WebCatalogLoading(WebCatalogLoadPhase.CheckingCache, page),
                            status = WebCatalogStatus.Loading,
                        )
                    }
                    runCatching {
                        loadWebNovelCatalog(
                            account.endpoint,
                            account.id,
                            page,
                            ::updateCatalogLoadStep,
                        ).also { loaded ->
                            if (loaded.paging.currentPage == 1) {
                                cache.saveStructured(account.endpoint, loaded.catalog)
                            }
                        }
                    }.onSuccess { loaded ->
                        applyLoadedWebNovelCatalog(loaded)
                    }.onFailure { error ->
                        _uiState.update { state ->
                            state.copy(catalogLoading = null, status = error.toWebCatalogStatus())
                        }
                    }
                }
            }
            else -> {
                loadCatalog(forceRefresh = false)
            }
        }
    }

    fun saveCurrentCatalogAccount() {
        if (_uiState.value.busy) return
        val url = _uiState.value.catalogUrl.trim()
        if (url.isBlank()) {
            _uiState.update { state -> state.copy(status = WebCatalogStatus.MissingCatalogUrl) }
            return
        }
        val title = _uiState.value.catalog?.title ?: url
        viewModelScope.launch {
            runCatching {
                val adapter = webNovelAdapter(url)
                accountStore.upsert(
                    if (adapter != null && adapter.classify(url) ==
                        com.dongholab.pagetuner.source.webnovel.WebNovelPageKind.Catalog
                    ) {
                        webNovelSourceAccount(endpoint = url, title = title)
                    } else {
                        pageTurnerWebCatalogAccount(catalogUrl = url, title = title)
                    },
                )
            }.onSuccess { accounts ->
                _uiState.update { state ->
                    state.copy(
                        sourceAccounts = accounts,
                        status = WebCatalogStatus.SavedAccount(title),
                    )
                }
            }.onFailure { error ->
                _uiState.update { state -> state.copy(status = error.toWebCatalogStatus()) }
            }
        }
    }

    fun deleteSourceAccount(account: RemoteSourceAccount) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            runCatching {
                accountStore.delete(account.id)
            }.onSuccess { accounts ->
                _uiState.update { state ->
                    state.copy(
                        sourceAccounts = accounts,
                        status = WebCatalogStatus.DeletedAccount(account.title),
                    )
                }
            }.onFailure { error ->
                _uiState.update { state -> state.copy(status = error.toWebCatalogStatus()) }
            }
        }
    }

    fun importItem(
        item: RemoteBookItem,
        translateAfterImport: Boolean = false,
        preferredOfflineLanguage: String? = null,
    ) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    busy = true,
                    status = WebCatalogStatus.Importing(item.title),
                )
            }
            runCatching {
                val offline = if (item.identity.sourceType == RemoteSourceType.WebNovel) {
                    OfflineNovelStorageStore.globalOfflineStore.getOfflineChapter(item)
                } else {
                    null
                }
                if (offline != null) {
                    val (text, language) = offline.preferredText(preferredOfflineLanguage)
                    ImportPayload(
                        bytes = text.toByteArray(Charsets.UTF_8),
                        language = language,
                        usedOfflinePackage = true,
                        usedOfflineTranslation = preferredOfflineLanguage != null &&
                            offline.translations.containsKey(language.lowercase()),
                    )
                } else if (item.identity.sourceType == RemoteSourceType.WebNovel) {
                    val source = WebNovelRemoteBookSource(accountId = item.identity.accountId, endpointUrl = item.downloadUrl)
                    ImportPayload(source.download(item), item.language ?: "auto", false, false)
                } else {
                    ImportPayload(PageTurnerWebCatalogNetwork.fetchBytes(item.downloadUrl), item.language ?: "auto", false, false)
                }
            }.onSuccess { payload ->
                val importedItem = if (payload.usedOfflineTranslation) {
                    item.copy(
                        title = "${item.title} [${payload.language.uppercase()}]",
                        language = payload.language,
                        contentVariant = RemoteBookContentVariant.Translated,
                    )
                } else {
                    item
                }
                _events.emit(
                    WebCatalogEvent.ImportDownloaded(
                        importedItem,
                        payload.bytes,
                        translateAfterImport && !payload.usedOfflinePackage,
                    ),
                )
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

    fun translateVisibleCatalog(context: Context, settings: TranslationSettings) {
        if (_uiState.value.busy || !settings.isProviderConfigured) return
        val items = _uiState.value.visibleItems
        if (items.isEmpty()) return
        catalogTranslationJob?.cancel()
        catalogTranslationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    catalogTranslationProgress = CatalogTranslationProgress(0, items.size, items.first().title),
                )
            }
            val translator = DefaultRemoteCatalogTranslationService(
                ContentTranslationServiceFactory.create(context.applicationContext, settings),
            )
            runCatching {
                translator.translate(
                    items = items,
                    settings = settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH),
                    onProgress = { progress ->
                        val completedItems = ((progress.fraction * items.size).toInt()).coerceIn(0, items.size)
                        _uiState.update { state ->
                            state.copy(
                                catalogTranslationProgress = CatalogTranslationProgress(
                                    completedItems = completedItems,
                                    totalItems = items.size,
                                    currentTitle = items.getOrElse(completedItems.coerceAtMost(items.lastIndex)) { items.last() }.title,
                                ),
                            )
                        }
                    },
                )
            }.onSuccess { translations ->
                _uiState.update { state ->
                    state.copy(translatedItems = state.translatedItems + translations)
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _uiState.update { state ->
                    state.copy(
                        status = error.toWebCatalogStatus(),
                        catalogTranslationProgress = state.catalogTranslationProgress?.copy(
                            failedItems = items.size - (state.catalogTranslationProgress?.completedItems ?: 0),
                        ),
                    )
                }
            }
            _uiState.update { it.copy(busy = false, catalogTranslationProgress = null) }
        }
    }

    fun cancelCatalogTranslation() {
        catalogTranslationJob?.cancel()
        _uiState.update { it.copy(busy = false, catalogTranslationProgress = null) }
    }

    fun downloadChaptersForOffline(
        context: Context,
        chapters: List<RemoteBookItem>,
        settings: TranslationSettings,
        includeTranslation: Boolean = true,
    ) {
        if (_uiState.value.busy || chapters.isEmpty()) return
        offlineDownloadJob?.cancel()
        offlineDownloadJob = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, batchDownloadProgress = null) }
            runCatching {
                WebNovelBatchDownloader.downloadChaptersInBackground(
                    context = context.applicationContext,
                    chapters = chapters,
                    settings = settings,
                    includeTranslation = includeTranslation,
                    onProgress = { progress ->
                        _uiState.update { state -> state.copy(batchDownloadProgress = progress) }
                    },
                )
            }.onSuccess { result ->
                _uiState.update { state ->
                    state.copy(
                        status = if (result.failedItems > 0) {
                            WebCatalogStatus.Error(
                                result.failureMessages.firstOrNull()
                                    ?: "${result.failedItems} chapter(s) could not be saved.",
                            )
                        } else {
                            WebCatalogStatus.OfflineSaved(
                                savedItems = result.savedItems,
                                translationFailedItems = result.translationFailedItems,
                            )
                        },
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _uiState.update { state -> state.copy(status = error.toWebCatalogStatus()) }
            }
            _uiState.update { it.copy(busy = false) }
        }
    }

    fun cancelOfflineDownload() {
        offlineDownloadJob?.cancel()
        _uiState.update { it.copy(busy = false, batchDownloadProgress = null) }
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

    fun refreshSourceAccounts() {
        viewModelScope.launch {
            runCatching {
                accountStore.list()
            }.onSuccess { accounts ->
                val finalAccounts = (accounts + defaultWebNovelAccounts())
                    .distinctBy(RemoteSourceAccount::id)
                _uiState.update { state -> state.copy(sourceAccounts = finalAccounts) }
            }
        }
    }

    private fun loadCatalog(forceRefresh: Boolean) {
        if (_uiState.value.busy) return
        if (!forceRefresh && catalogPreloadJob?.isActive == true &&
            _uiState.value.catalogUrl.trim() == DefaultCatalogUrl
        ) return
        val url = _uiState.value.catalogUrl.trim()
        if (url.isBlank()) {
            _uiState.update { state ->
                state.copy(status = WebCatalogStatus.MissingCatalogUrl)
            }
            return
        }

        val isWebNovelCatalog = url.contains("wtr-lab", ignoreCase = true) ||
            url.contains("novel", ignoreCase = true) ||
            !url.lowercase().endsWith(".json")
        if (isWebNovelCatalog) {
            val requestedPage = _uiState.value.remotePaging?.currentPage
                ?: com.dongholab.pagetuner.source.webnovel.WebNovelCatalogPageUrls.currentPage(url)
            loadWebNovelPage(
                url = url,
                accountId = accountIdForCatalog(url),
                page = requestedPage,
                forceRefresh = forceRefresh,
            )
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
                        remotePaging = null,
                        catalogLoading = null,
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
                        catalogLoading = null,
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
                item.description.orEmpty().lowercase().contains(normalized) ||
                item.tags.any { tag -> tag.lowercase().contains(normalized) } ||
                item.language.orEmpty().lowercase().contains(normalized) ||
                item.format.name.lowercase().contains(normalized)
        }
    }

    private fun applyCachedCatalog(cached: CachedWebCatalog, busy: Boolean) {
        val catalog = runCatching {
            if (cached.storageFormat == RemoteCatalogSnapshotJson.StorageFormat) {
                RemoteCatalogSnapshotJson.decode(cached.rawJson)
            } else {
                PageTurnerWebCatalogParser.parse(
                    rawJson = cached.rawJson,
                    catalogUrl = cached.url,
                )
            }
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
            val visible = if (cached.url.hasRemoteCatalogSearch()) {
                catalog.items
            } else {
                catalog.filterItems(state.query)
            }
            state.copy(
                catalogUrl = cached.url,
                catalog = catalog,
                visibleItems = visible,
                remotePaging = null,
                catalogLoading = null,
                busy = busy,
                status = WebCatalogStatus.LoadedCached(
                    title = catalog.title,
                    itemCount = catalog.items.size,
                ),
            )
        }
        prefetchCoverThumbnails(_uiState.value.visibleItems)
    }

    private fun loadWebNovelPage(
        url: String,
        accountId: String,
        page: Int,
        forceRefresh: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    catalogLoading = WebCatalogLoading(WebCatalogLoadPhase.CheckingCache, page),
                    status = WebCatalogStatus.Loading,
                )
            }
            val adapter = runCatching {
                com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry.default.resolve(url)
            }.getOrNull()
            val cacheKey = "$accountId|${adapter?.catalogPageUrl(url, page) ?: "$url|$page"}"
            val inMemory = if (forceRefresh) {
                catalogPageMemoryCache.remove(cacheKey)
                null
            } else {
                catalogPageMemoryCache[cacheKey]
            }
            if (inMemory != null) {
                DiagnosticLogger.log(
                    "[WEB CATALOG CACHE HIT]",
                    "provider=${adapter?.id ?: "unknown"} page=$page items=${inMemory.catalog.items.size}",
                )
                applyLoadedWebNovelCatalog(inMemory)
                return@launch
            }

            val startedAtNanos = System.nanoTime()
            DiagnosticLogger.log(
                "[WEB CATALOG START]",
                "provider=${adapter?.id ?: "unknown"} page=$page forceRefresh=$forceRefresh",
            )
            runCatching {
                loadWebNovelCatalog(
                    url = url,
                    accountId = accountId,
                    page = page,
                    onStep = ::updateCatalogLoadStep,
                )
            }.onSuccess { loaded ->
                DiagnosticLogger.log(
                    "[WEB CATALOG SUCCESS]",
                    "provider=${adapter?.id ?: "unknown"} page=${loaded.paging.currentPage}/${loaded.paging.totalPages} items=${loaded.catalog.items.size} total=${loaded.paging.totalItems} durationMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}",
                )
                catalogPageMemoryCache[cacheKey] = loaded
                if (loaded.paging.currentPage == 1) {
                    cache.saveStructured(url, loaded.catalog)
                }
                applyLoadedWebNovelCatalog(loaded, cachedCatalogs = cache.list())
            }.onFailure { error ->
                if (error !is CancellationException) {
                    DiagnosticLogger.log(
                        "[WEB CATALOG FAILURE]",
                        "provider=${adapter?.id ?: "unknown"} page=$page durationMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L} ${error.javaClass.simpleName}: ${error.message}",
                    )
                    _uiState.update { state ->
                        state.copy(
                            catalogLoading = null,
                            status = error.toWebCatalogStatus(),
                        )
                    }
                }
            }
        }
    }

    private fun updateCatalogLoadStep(step: RemoteCatalogLoadStep) {
        _uiState.update { state ->
            val page = state.catalogLoading?.page ?: state.remotePaging?.currentPage ?: 1
            state.copy(
                catalogLoading = WebCatalogLoading(
                    phase = when (step) {
                        RemoteCatalogLoadStep.FetchingPage -> WebCatalogLoadPhase.FetchingPage
                        RemoteCatalogLoadStep.ParsingDom -> WebCatalogLoadPhase.ParsingDom
                    },
                    page = page,
                ),
            )
        }
    }

    private fun applyLoadedWebNovelCatalog(
        loaded: LoadedWebNovelCatalog,
        cachedCatalogs: List<CachedWebCatalog>? = null,
    ) {
        _uiState.update { state ->
            state.copy(catalogLoading = WebCatalogLoading(WebCatalogLoadPhase.ApplyingResults, loaded.paging.currentPage))
        }
        val currentState = _uiState.value
        val visible = if (currentState.catalogUrl.hasRemoteCatalogSearch()) {
            loaded.catalog.items
        } else {
            loaded.catalog.filterItems(currentState.query)
        }
        _uiState.update { state ->
            state.copy(
                catalog = loaded.catalog,
                visibleItems = visible,
                cachedCatalogs = cachedCatalogs ?: state.cachedCatalogs,
                remotePaging = loaded.paging,
                catalogLoading = null,
                status = WebCatalogStatus.LoadedRemote(
                    title = loaded.catalog.title,
                    itemCount = loaded.catalog.items.size,
                    currentPage = loaded.paging.currentPage,
                    totalPages = loaded.paging.totalPages,
                    totalItems = loaded.paging.totalItems,
                ),
            )
        }
        prefetchCoverThumbnails(visible)
    }

    private fun accountIdForCatalog(url: String): String {
        com.dongholab.pagetuner.source.webnovel.WebNovelProviderPlugins.findDiscoverableByUrl(url)
            ?.manifest
            ?.accountId
            ?.let { return it }
        return _uiState.value.sourceAccounts.firstOrNull { account ->
            account.endpoint.substringBefore('?').trimEnd('/') == url.substringBefore('?').trimEnd('/')
        }?.id ?: "web_novel"
    }

    private fun webNovelAdapter(url: String) = runCatching {
        com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry.default.resolve(url)
    }.getOrNull()

    private fun String.hasRemoteCatalogSearch(): Boolean {
        val adapter = webNovelAdapter(this) ?: return false
        val request = adapter.catalogRequest(this)
        return adapter.catalogSearchUrl(this, request) != null
    }

    private suspend fun loadWebNovelCatalog(
        url: String,
        accountId: String,
        page: Int,
        onStep: (RemoteCatalogLoadStep) -> Unit = {},
    ): LoadedWebNovelCatalog {
        val source = WebNovelRemoteBookSource(accountId = accountId, endpointUrl = url)
        val remotePage = source.loadCatalogPage(page, onStep)
        return LoadedWebNovelCatalog(
            catalog = PageTurnerCatalog(
                version = PageTurnerWebCatalogParser.Version,
                id = accountId,
                title = remotePage.title,
                items = remotePage.items,
            ),
            paging = RemoteCatalogPagingState(
                currentPage = remotePage.currentPage,
                totalPages = remotePage.totalPages,
                totalItems = remotePage.totalItems,
                pageItemCount = remotePage.items.size,
                hasPreviousPage = remotePage.hasPreviousPage,
                hasNextPage = remotePage.hasNextPage,
            ),
        )
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
                DiagnosticLogger.log("[COVER STEP 1: FETCH START]", "Requesting thumbnail: $url")
                runCatching {
                    PageTurnerWebCatalogNetwork.fetchBytes(
                        url = url,
                        maxBytes = MaxThumbnailBytes,
                    )
                }.onSuccess { bytes ->
                    DiagnosticLogger.log("[COVER STEP 2: FETCH OK]", "Downloaded ${bytes.size} bytes from $url")
                    // STEP 3: Verify BitmapFactory can decode
                    val bitmap = runCatching {
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                    if (bitmap != null) {
                        DiagnosticLogger.log("[COVER STEP 3: DECODE OK]", "Bitmap decoded ${bitmap.width}x${bitmap.height} from $url")
                    } else {
                        DiagnosticLogger.log("[COVER STEP 3: DECODE FAIL]", "BitmapFactory returned null for $url — bytes may not be a valid image")
                    }
                    _uiState.update { state ->
                        state.copy(coverThumbnails = state.coverThumbnails + (url to bytes))
                    }
                }.onFailure { error ->
                    DiagnosticLogger.log("[COVER STEP 2: FETCH FAIL]", "$url → ${error.javaClass.simpleName}: ${error.message}")
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
        private val accountStore: RemoteSourceAccountStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(WebCatalogViewModel::class.java)) {
                return WebCatalogViewModel(cache, accountStore) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
