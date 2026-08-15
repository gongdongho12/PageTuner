package com.dongholab.pagetuner.source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in integration test: every catalog/detail/index/body response comes from NovelBuddy. */
class NovelBuddyFullFlowLiveTest {
    @Test
    fun liveSearchDetailChapterIndexAndBodyWorkWithoutWebView() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") == "1")

        val searchSource = WebNovelRemoteBookSource(
            accountId = "default_novelbuddy",
            endpointUrl = "https://novelbuddy.me/search?q=shadow%20slave&genres=fantasy",
            renderedChapterLoader = null,
        )
        val page = searchSource.loadCatalogPage(1) {}
        val book = requireNotNull(page.items.firstOrNull { it.title == "Shadow Slave" })
        assertTrue((page.totalPages ?: 0) > 1)
        assertTrue((page.totalItems ?: 0) > 20)
        assertEquals("en", book.language)
        assertTrue(book.tags.any { it.equals("Fantasy", ignoreCase = true) })

        val bookSource = WebNovelRemoteBookSource(
            accountId = "default_novelbuddy",
            endpointUrl = book.downloadUrl,
            renderedChapterLoader = null,
        )
        val detail = bookSource.loadNovelDetail()
        val chapters = bookSource.list()
        assertEquals("Guiltythree", detail.author)
        assertTrue(detail.totalChapters > 3_000)
        assertEquals(detail.totalChapters, chapters.size)
        assertEquals(1, chapters.first().chapterNumber)
        assertEquals(detail.totalChapters, chapters.last().chapterNumber)
        assertEquals(book.seriesId, chapters.first().seriesId)

        val original = bookSource.download(chapters.first()).toString(Charsets.UTF_8)
        assertTrue(original.startsWith("# Chapter 1"))
        assertTrue(original.length > 5_000)
        assertTrue(original.contains("Sunny"))
    }
}
