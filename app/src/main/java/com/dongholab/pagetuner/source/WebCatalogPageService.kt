package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.paging.PageMetadata
import com.dongholab.pagetuner.core.paging.metadata
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WebCatalogPageRequest(
    val url: String,
    val accountId: String,
    val pageNumber: Int,
    val forceRefresh: Boolean = false,
)

data class WebCatalogPageData(
    val catalog: PageTurnerCatalog,
    val paging: PageMetadata,
    val providerId: String,
    val fromMemoryCache: Boolean,
)

/** UI-free boundary for fetching, parsing, mapping, and retaining one remote catalog page. */
interface WebCatalogPageService {
    suspend fun load(
        request: WebCatalogPageRequest,
        onStep: (RemoteCatalogLoadStep) -> Unit = {},
    ): WebCatalogPageData
}

class DefaultWebCatalogPageService(
    private val adapterRegistry: WebNovelSiteAdapterRegistry = WebNovelSiteAdapterRegistry.default,
    private val sourceFactory: (accountId: String, url: String) -> PaginatedRemoteBookSource =
        { accountId, url -> WebNovelRemoteBookSource(accountId, url) },
) : WebCatalogPageService {
    private val memoryPages = ConcurrentHashMap<String, WebCatalogPageData>()

    override suspend fun load(
        request: WebCatalogPageRequest,
        onStep: (RemoteCatalogLoadStep) -> Unit,
    ): WebCatalogPageData = withContext(Dispatchers.Default) {
        val adapter = adapterRegistry.resolve(request.url)
        val pageUrl = adapter.catalogPageUrl(request.url, request.pageNumber)
        val cacheKey = "${request.accountId}|$pageUrl"
        if (request.forceRefresh) memoryPages.remove(cacheKey)
        memoryPages[cacheKey]?.let { cached ->
            return@withContext cached.copy(fromMemoryCache = true)
        }

        val remotePage = sourceFactory(request.accountId, request.url).loadCatalogPage(
            page = request.pageNumber,
            onStep = onStep,
        )
        WebCatalogPageData(
            catalog = PageTurnerCatalog(
                version = PageTurnerWebCatalogParser.Version,
                id = request.accountId,
                title = remotePage.title,
                items = remotePage.items,
            ),
            paging = remotePage.metadata(),
            providerId = adapter.id,
            fromMemoryCache = false,
        ).also { loaded -> memoryPages[cacheKey] = loaded }
    }
}
