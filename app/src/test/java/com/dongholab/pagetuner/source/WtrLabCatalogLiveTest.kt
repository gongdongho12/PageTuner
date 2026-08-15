package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.source.webnovel.WebNovelChapterLoadStrategy
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry
import com.dongholab.pagetuner.source.webnovel.WtrLabSiteAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class WtrLabCatalogLiveTest {
    @Test
    fun loadsCurrentCatalogWithoutOpeningWebNovelUi() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") == "1")
        val source = WebNovelRemoteBookSource(
            accountId = "default_wtr_lab",
            endpointUrl = "https://wtr-lab.com/en",
        )

        val connection = source.connect()
        val firstPage = source.loadCatalogPage(1) {}
        val secondPage = source.loadCatalogPage(2) {}
        val items = source.list()

        assertTrue(connection.itemCount > 0)
        assertEquals(connection.itemCount, items.size)
        assertTrue(items.all { it.identity.sourceType == RemoteSourceType.WebNovel })
        assertTrue(items.all { !it.seriesId.isNullOrBlank() })
        assertTrue((firstPage.totalItems ?: 0) > firstPage.items.size)
        assertTrue((firstPage.totalPages ?: 0) > 1)
        assertEquals(2, secondPage.currentPage)
        assertTrue(firstPage.items.map { it.identity.remoteId } != secondPage.items.map { it.identity.remoteId })
    }

    @Test
    fun searchesKeywordAndGenreAcrossTheLiveRemoteCatalog() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") == "1")
        val searchUrl = WtrLabCatalogQueryParams(
            genreId = 9,
            query = "alchemy",
        ).buildUrl()
        val source = WebNovelRemoteBookSource(
            accountId = "default_wtr_lab",
            endpointUrl = searchUrl,
        )

        val firstPage = source.loadCatalogPage(1) {}
        val secondPage = source.loadCatalogPage(2) {}

        assertTrue(firstPage.items.isNotEmpty())
        assertTrue((firstPage.totalPages ?: 0) > 1)
        assertEquals(null, firstPage.totalItems)
        assertEquals(2, secondPage.currentPage)
        assertTrue(firstPage.items.map { it.identity.remoteId } != secondPage.items.map { it.identity.remoteId })
        assertTrue(firstPage.items.all { it.title.contains("alchemy", ignoreCase = true) })
    }

    @Test
    fun loadsLiveChapterThroughHttpWithoutWebView() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") == "1")
        val chapterUrl =
            "https://wtr-lab.com/en/novel/51593/sea-survival-the-female-leads-combat-power-is-off-the-charts/chapter-1"
        val item = RemoteBookItem(
            identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "default_wtr_lab", "chapter_1"),
            title = "Chapter 1",
            format = DocumentFormat.TEXT,
            downloadUrl = chapterUrl,
        )
        val source = WebNovelRemoteBookSource(
            accountId = "default_wtr_lab",
            endpointUrl = chapterUrl,
            renderedChapterLoader = null,
            adapterRegistry = WebNovelSiteAdapterRegistry(
                listOf(WtrLabSiteAdapter(WebNovelChapterLoadStrategy.HttpOnly)),
            ),
        )

        val text = source.download(item).toString(Charsets.UTF_8)

        assertTrue(text.length > 1_000)
        assertTrue(text.contains("Ji Ting"))
        assertTrue(!text.contains("※12⛬"))
    }
}
