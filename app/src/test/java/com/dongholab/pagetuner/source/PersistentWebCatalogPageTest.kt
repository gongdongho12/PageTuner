package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.paging.PageMetadata
import com.dongholab.pagetuner.document.DocumentFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentWebCatalogPageTest {
    @get:Rule val temp = TemporaryFolder()
    private val request = WebCatalogPageRequest("https://wtr-lab.com/en/novel-list", "account", 1)
    private fun service(
        store: WebCatalogPageStore? = null, clock: () -> Long = { 100 },
        load: suspend (String, Int) -> RemoteCatalogPage,
    ) = DefaultWebCatalogPageService(
        pageStore = store, nowMillis = clock, ttlMillis = 1000,
        sourceFactory = { account, _ -> object : PaginatedRemoteBookSource {
            override suspend fun loadCatalogPage(page: Int, onStep: (RemoteCatalogLoadStep) -> Unit): RemoteCatalogPage = load(account, page)
        } },
    )

    @Test
    fun restartRestoresEachPageAndAccountWithoutNetwork() = runTest {
        val store = FileWebCatalogPageStore(temp.newFolder())
        val first = service(store) { account, page -> remotePage(account, page) }
        first.load(request)
        first.load(request.copy(pageNumber = 2))
        first.load(request.copy(accountId = "other"))
        val restarted = service(store) { _, _ -> error("Unexpected network") }
        val restored = restarted.load(request.copy(pageNumber = 2))
        assertTrue(restored.fromDiskCache)
        assertEquals(2, restored.paging.currentPage)
        assertEquals(80, restored.paging.totalItems)
        assertEquals("account-2", restored.catalog.items.single().identity.remoteId)
        assertEquals("other-1", restarted.load(request.copy(accountId = "other")).catalog.items.single().identity.remoteId)
        assertEquals("account-1", restarted.load(request).catalog.items.single().identity.remoteId)
    }

    @Test
    fun queryVariantsHaveSeparateDiskKeys() = runTest {
        val store = FileWebCatalogPageStore(temp.newFolder())
        var calls = 0
        val loader = service(store) { account, page -> calls++; remotePage(account, page) }
        loader.load(request.copy(url = "${request.url}?orderBy=views"))
        loader.load(request.copy(url = "${request.url}?orderBy=chapters"))
        assertEquals(2, calls)
        val restarted = service(store) { _, _ -> error("Unexpected network") }
        assertTrue(restarted.load(request.copy(url = "${request.url}?orderBy=views")).fromDiskCache)
    }

    @Test
    fun ttlRefreshesButOfflineCanUseExpiredSnapshot() = runTest {
        var now = 100L
        var offline = false
        var calls = 0
        val loader = service(FileWebCatalogPageStore(temp.newFolder()), { now }) { account, page ->
            calls++
            if (offline) throw IOException("offline")
            remotePage(account, page)
        }
        loader.load(request)
        assertTrue(loader.load(request).fromMemoryCache)
        assertEquals(1, calls)
        now += 1000
        assertFalse(loader.load(request).fromMemoryCache)
        assertEquals(2, calls)
        now += 1000
        offline = true
        assertTrue(loader.load(request).isStale)
        try { loader.load(request.copy(forceRefresh = true)); fail("Force refresh must expose failure") } catch (_: IOException) { }
    }

    @Test
    fun forceRefreshReplacesDiskAndMemory() = runTest {
        val store = FileWebCatalogPageStore(temp.newFolder())
        var version = "old"
        val loader = service(store) { account, page -> remotePage(account, page).copy(title = version) }
        loader.load(request)
        version = "new"
        assertEquals("new", loader.load(request.copy(forceRefresh = true)).catalog.title)
        assertEquals("new", service(store) { _, _ -> error("Unexpected network") }.load(request).catalog.title)
    }

    @Test
    fun concurrentSamePageLoadsAreDeduplicated() = runTest {
        val calls = AtomicInteger()
        val loader = service { account, page -> calls.incrementAndGet(); delay(10); remotePage(account, page) }
        val results = List(8) { async { loader.load(request) } }.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(7, results.count { it.fromMemoryCache })
    }

    @Test
    fun cancellationNeverReturnsStaleData() = runTest {
        val store = FileWebCatalogPageStore(temp.newFolder())
        service(store) { a, p -> remotePage(a, p) }.load(request)
        val loader = service(store, { 10_000 }) { _, _ -> throw CancellationException("cancelled") }
        try { loader.load(request); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
    }

    @Test
    fun corruptCacheIsRefetchedAndUnknownCountsRoundTrip() = runTest {
        val directory = temp.newFolder()
        val store = FileWebCatalogPageStore(directory)
        service(store) { a, p -> remotePage(a, p).copy(totalItems = null, totalPages = null) }.load(request)
        assertNull(service(store) { _, _ -> error("Unexpected network") }.load(request).paging.totalItems)
        directory.listFiles()!!.single().writeText("broken")
        var calls = 0
        val restored = service(store) { a, p -> calls++; remotePage(a, p) }.load(request)
        assertEquals(1, calls)
        assertFalse(restored.fromDiskCache)
    }

    @Test
    fun unavailableDiskDoesNotHideSuccessfulNetworkResult() = runTest {
        val broken = object : WebCatalogPageStore {
            override suspend fun read(key: String): StoredCatalogPage? = throw IOException("read unavailable")
            override suspend fun write(key: String, page: StoredCatalogPage) { throw IOException("disk full") }
        }
        assertEquals(1, service(broken) { a, p -> remotePage(a, p) }.load(request).catalog.items.size)
    }

    @Test
    fun storeBoundsOwnedFilesAndPreservesUnrelatedFiles() = runTest {
        val directory = temp.newFolder()
        val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        val store = FileWebCatalogPageStore(directory, maxPages = 1)
        val data = WebCatalogPageData(
            PageTurnerCatalog("1", "account", "Book", items = remotePage("account", 1).items),
            PageMetadata(pageItemCount = 1), "wtr-lab", false,
        )
        store.write("one", StoredCatalogPage(1, data))
        directory.listFiles()!!.filter { it.extension == "json" }.forEach { it.setLastModified(1) }
        store.write("two", StoredCatalogPage(2, data))
        assertNull(store.read("one"))
        assertNotNull(store.read("two"))
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun oversizedEntryIsNotWrittenAndTranslatedVariantSurvivesRoundTrip() = runTest {
        val directory = temp.newFolder()
        val item = remotePage("account", 1).items.single().copy(contentVariant = RemoteBookContentVariant.Translated)
        val data = WebCatalogPageData(
            PageTurnerCatalog("1", "account", "Book", items = listOf(item)),
            PageMetadata(pageItemCount = 1), "wtr-lab", false,
        )
        val tiny = FileWebCatalogPageStore(directory, maxTotalBytes = 1)
        tiny.write("one", StoredCatalogPage(1, data))
        assertNull(tiny.read("one"))
        assertTrue(directory.listFiles()!!.isEmpty())
        val store = FileWebCatalogPageStore(directory)
        store.write("one", StoredCatalogPage(1, data))
        assertEquals(data.catalog, store.read("one")!!.data.catalog)
    }

    private fun remotePage(account: String, page: Int) = RemoteCatalogPage(
        title = "Page $page", url = "${request.url}?page=$page", currentPage = page,
        totalPages = 8, totalItems = 80,
        items = listOf(RemoteBookItem(
            RemoteBookIdentity(RemoteSourceType.WebNovel, account, "$account-$page"),
            title = "Book $page", format = DocumentFormat.TEXT, downloadUrl = "https://example.test/$account/$page",
        )),
    )

    @Test
    fun liveWtrPageSurvivesServiceRestartWithoutAnotherNetworkCall() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") == "1")
        val store = FileWebCatalogPageStore(temp.newFolder())
        val started = System.nanoTime()
        val remote = DefaultWebCatalogPageService(pageStore = store).load(request)
        val networkMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(remote.catalog.items.isNotEmpty())
        assertTrue((remote.paging.totalPages ?: 0) > 1)
        assertFalse(remote.fromMemoryCache || remote.fromDiskCache)
        val restoreStarted = System.nanoTime()
        val restored = DefaultWebCatalogPageService(
            pageStore = store,
            sourceFactory = { _, _ -> error("Restoring persisted live data must not call network") },
        ).load(request)
        val diskMs = (System.nanoTime() - restoreStarted) / 1_000_000
        assertTrue(restored.fromDiskCache)
        assertEquals(remote.catalog, restored.catalog)
        assertEquals(remote.paging, restored.paging)
        println("LIVE_PAGE_DISK provider=${remote.providerId} items=${remote.catalog.items.size} pages=${remote.paging.totalPages} networkMs=$networkMs diskMs=$diskMs")
    }
}
