package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.paging.PageMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPagePrefetcherTest {
    private val request = WebCatalogPageRequest("https://wtr-lab.com/en/novel-list", "account", 1, true)
    private val page = WebCatalogPageData(
        PageTurnerCatalog("1", "account", "Catalog", items = emptyList()),
        PageMetadata(currentPage = 1, totalPages = 8, hasNextPage = true), "wtr-lab", false,
    )

    @Test
    fun onlyFollowingPageLoadsWithoutForceRefreshOrRecursion() = runTest {
        val requests = mutableListOf<WebCatalogPageRequest>()
        val prefetch = CatalogPagePrefetcher(this, service { requests += it; page })
        prefetch.schedule(request, page)
        advanceUntilIdle()
        assertEquals(listOf(request.copy(pageNumber = 2, forceRefresh = false)), requests)
    }

    @Test
    fun replacementLastPageAndStalePageCancelPendingPrefetch() = runTest {
        val requests = mutableListOf<WebCatalogPageRequest>()
        val prefetch = CatalogPagePrefetcher(this, service { requests += it; page })
        prefetch.schedule(request, page)
        prefetch.schedule(request, page.copy(paging = page.paging.copy(hasNextPage = false)))
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        prefetch.schedule(request, page.copy(isStale = true))
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun speculativeFailureDoesNotFailOwningScope() = runTest {
        val prefetch = CatalogPagePrefetcher(this, service { throw java.io.IOException("offline") })
        prefetch.schedule(request, page)
        advanceUntilIdle()
    }

    private fun service(fetch: suspend (WebCatalogPageRequest) -> WebCatalogPageData) = object : WebCatalogPageService {
        override suspend fun load(request: WebCatalogPageRequest, onStep: (RemoteCatalogLoadStep) -> Unit) = fetch(request)
    }
}
