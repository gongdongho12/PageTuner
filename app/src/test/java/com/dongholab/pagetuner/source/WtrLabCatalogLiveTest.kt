package com.dongholab.pagetuner.source

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
}
