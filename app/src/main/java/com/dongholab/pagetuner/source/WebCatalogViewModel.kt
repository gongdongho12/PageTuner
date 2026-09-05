package com.dongholab.pagetuner.source

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.common.DiagnosticLogger
import com.dongholab.pagetuner.core.paging.PageMetadata
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import com.dongholab.pagetuner.translation.ContentTranslationServiceFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DefaultCatalogUrl = "https://wtr-lab.com/en/novel-list"

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

typealias RemoteCatalogPagingState = PageMetadata

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
    private val pageService: WebCatalogPageService = DefaultWebCatalogPageService(),
) : ViewModel() {
    private val offlineDownloadCoordinator = OfflineBookDownloadCoordinator(viewModelScope)
    private val catalogTranslationCoordinator = CatalogTranslationCoordinator(viewModelScope)
    private val coverRepository = CoverThumbnailRepository()
    private var catalogPreloadJob: Job? = null
    private var coverThumbnailJob: Job? = null
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
                pageService.load(
                    WebCatalogPageRequest(
                        url = DefaultCatalogUrl,
                        accountId = defaultWtrLabAccount().id,
                        pageNumber = 1,
                    ),
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
        viewModelScope.launch { applyCachedCatalog(cached, busy = false) }
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
                        pageService.load(
                            request = WebCatalogPageRequest(
                                url = account.endpoint,
                                accountId = account.id,
                                pageNumber = page,
                            ),
                            onStep = ::updateCatalogLoadStep,
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
        val appContext = context.applicationContext
        catalogTranslationCoordinator.start(
            items = _uiState.value.visibleItems,
            settings = settings,
            createService = {
                DefaultRemoteCatalogTranslationService(ContentTranslationServiceFactory.create(appContext, settings))
            },
            onUpdate = { update ->
                _uiState.update { state ->
                    when (update) {
                        is CatalogTranslationUpdate.Running -> state.copy(
                            busy = true, catalogTranslationProgress = update.progress,
                        )
                        is CatalogTranslationUpdate.Completed -> state.copy(
                            busy = false, catalogTranslationProgress = null,
                            translatedItems = state.translatedItems + update.translations,
                        )
                        is CatalogTranslationUpdate.Failed -> state.copy(
                            busy = false, catalogTranslationProgress = null,
                            status = update.error.toWebCatalogStatus(),
                        )
                        CatalogTranslationUpdate.Cancelled -> state.copy(
                            busy = false, catalogTranslationProgress = null,
                        )
                    }
                }
            },
        )
    }

    fun cancelCatalogTranslation() {
        catalogTranslationCoordinator.cancel()
    }

    fun downloadChaptersForOffline(
        context: Context,
        chapters: List<RemoteBookItem>,
        settings: TranslationSettings,
        includeTranslation: Boolean = true,
    ) {
        if (_uiState.value.busy || chapters.isEmpty()) return
        val appContext = context.applicationContext
        val snapshot = chapters.toList()
        offlineDownloadCoordinator.start(
            download = { onProgress ->
                WebNovelBatchDownloader.downloadChaptersInBackground(
                    context = appContext,
                    chapters = snapshot,
                    settings = settings,
                    includeTranslation = includeTranslation,
                    onProgress = onProgress,
                )
            },
            onUpdate = { update ->
                _uiState.update { state -> when (update) {
                    OfflineDownloadUpdate.Started -> state.copy(busy = true, batchDownloadProgress = null)
                    is OfflineDownloadUpdate.Progress -> state.copy(batchDownloadProgress = update.value)
                    is OfflineDownloadUpdate.Completed -> state.copy(
                        busy = false,
                        status = if (update.result.failedItems > 0) {
                            WebCatalogStatus.Error(
                                update.result.failureMessages.firstOrNull()
                                    ?: "${update.result.failedItems} chapter(s) could not be saved.",
                            )
                        } else {
                            WebCatalogStatus.OfflineSaved(
                                savedItems = update.result.savedItems,
                                translationFailedItems = update.result.translationFailedItems,
                            )
                        },
                    )
                    is OfflineDownloadUpdate.Failed -> state.copy(busy = false, status = update.error.toWebCatalogStatus())
                    OfflineDownloadUpdate.Cancelled -> state.copy(busy = false, batchDownloadProgress = null)
                } }
            },
        )
    }

    fun cancelOfflineDownload() {
        offlineDownloadCoordinator.cancel()
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

    private suspend fun applyCachedCatalog(cached: CachedWebCatalog, busy: Boolean) {
        val catalog = runCatching {
            withContext(Dispatchers.Default) {
                if (cached.storageFormat == RemoteCatalogSnapshotJson.StorageFormat) {
                    RemoteCatalogSnapshotJson.decode(cached.rawJson)
                } else {
                    PageTurnerWebCatalogParser.parse(
                        rawJson = cached.rawJson,
                        catalogUrl = cached.url,
                    )
                }
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
            val startedAtNanos = System.nanoTime()
            DiagnosticLogger.log(
                "[WEB CATALOG START]",
                "provider=${adapter?.id ?: "unknown"} page=$page forceRefresh=$forceRefresh",
            )
            runCatching {
                pageService.load(
                    request = WebCatalogPageRequest(
                        url = url,
                        accountId = accountId,
                        pageNumber = page,
                        forceRefresh = forceRefresh,
                    ),
                    onStep = ::updateCatalogLoadStep,
                )
            }.onSuccess { loaded ->
                val logStep = if (loaded.fromMemoryCache) {
                    "[WEB CATALOG CACHE HIT]"
                } else {
                    "[WEB CATALOG SUCCESS]"
                }
                DiagnosticLogger.log(
                    logStep,
                    "provider=${loaded.providerId} page=${loaded.paging.currentPage}/${loaded.paging.totalPages} items=${loaded.catalog.items.size} total=${loaded.paging.totalItems} durationMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}",
                )
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
        loaded: WebCatalogPageData,
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

    private fun prefetchCoverThumbnails(items: List<RemoteBookItem>) {
        coverThumbnailJob?.cancel()
        val urls = items.mapNotNull { it.coverUrl }
        if (urls.isEmpty()) {
            _uiState.update { it.copy(coverThumbnails = emptyMap()) }
            return
        }
        coverThumbnailJob = viewModelScope.launch {
            val loaded = coverRepository.load(urls)
            _uiState.update { state ->
                state.copy(coverThumbnails = loaded)
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
