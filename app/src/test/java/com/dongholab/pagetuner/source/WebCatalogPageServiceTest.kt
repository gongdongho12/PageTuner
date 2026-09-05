package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class WebCatalogPageServiceTest {
    @Test
    fun loaderMapsProviderPageAndReusesItWithoutUiState() = runTest {
        val loadCount = AtomicInteger(0)
        val service = DefaultWebCatalogPageService(
            sourceFactory = { accountId, _ -> FakePaginatedSource(accountId, loadCount) },
        )
        val request = WebCatalogPageRequest(
            url = "https://wtr-lab.com/en/novel-list",
            accountId = "wtr-test",
            pageNumber = 3,
        )

        val first = service.load(request)
        val cached = service.load(request)
        val refreshed = service.load(request.copy(forceRefresh = true))

        assertEquals(2, loadCount.get())
        assertEquals(3, first.paging.currentPage)
        assertEquals(8, first.paging.totalPages)
        assertEquals(1, first.paging.pageItemCount)
        assertFalse(first.fromMemoryCache)
        assertTrue(cached.fromMemoryCache)
        assertFalse(refreshed.fromMemoryCache)
        assertEquals("Book on remote page 3", first.catalog.items.single().title)
    }

    @Test
    fun liveWtrPageLoadsAndThenUsesTheUiFreeMemoryCache() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") == "1")
        val service = DefaultWebCatalogPageService()
        val request = WebCatalogPageRequest(
            url = "https://wtr-lab.com/en/novel-list",
            accountId = "live_wtr_lab",
            pageNumber = 1,
        )

        val networkStartedAt = System.nanoTime()
        val network = service.load(request)
        val networkDurationMs = (System.nanoTime() - networkStartedAt) / 1_000_000L
        val cacheStartedAt = System.nanoTime()
        val cached = service.load(request)
        val cacheDurationMs = (System.nanoTime() - cacheStartedAt) / 1_000_000L

        assertTrue(network.catalog.items.isNotEmpty())
        assertTrue((network.paging.totalPages ?: 0) > 1)
        assertFalse(network.fromMemoryCache)
        assertTrue(cached.fromMemoryCache)
        assertEquals(
            network.catalog.items.map { it.identity.remoteId },
            cached.catalog.items.map { it.identity.remoteId },
        )
        println(
            "LIVE_WEB_CATALOG provider=${network.providerId} " +
                "page=${network.paging.currentPage}/${network.paging.totalPages} " +
                "items=${network.catalog.items.size} " +
                "networkMs=$networkDurationMs cacheMs=$cacheDurationMs",
        )
    }
}

private class FakePaginatedSource(
    override val accountId: String,
    private val loadCount: AtomicInteger,
) : RemoteBookSource, PaginatedRemoteBookSource {
    override val sourceType: RemoteSourceType = RemoteSourceType.WebNovel

    override suspend fun connect(): RemoteSourceConnection =
        RemoteSourceConnection(sourceType, accountId, "Fake", 1)

    override suspend fun list(): List<RemoteBookItem> = emptyList()

    override suspend fun search(query: String): List<RemoteBookItem> = emptyList()

    override suspend fun download(item: RemoteBookItem): ByteArray = ByteArray(0)

    override suspend fun refresh(): List<RemoteBookItem> = emptyList()

    override suspend fun loadCatalogPage(
        page: Int,
        onStep: (RemoteCatalogLoadStep) -> Unit,
    ): RemoteCatalogPage {
        loadCount.incrementAndGet()
        return RemoteCatalogPage(
            title = "Fake catalog",
            url = "https://wtr-lab.com/en/novel-list?page=$page",
            items = listOf(
                RemoteBookItem(
                    identity = RemoteBookIdentity(sourceType, accountId, "book-$page"),
                    title = "Book on remote page $page",
                    format = DocumentFormat.TEXT,
                    downloadUrl = "https://wtr-lab.com/en/novel/book-$page",
                ),
            ),
            currentPage = page,
            totalPages = 8,
            totalItems = 80,
            hasPreviousPage = page > 1,
            hasNextPage = page < 8,
        )
    }
}
