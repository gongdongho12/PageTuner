package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.source.webnovel.WebNovelProviderPlugins
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry
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

        val plugin = WebNovelProviderPlugins.novelBuddy
        val registry = WebNovelSiteAdapterRegistry.fromPlugins(
            listOf(plugin, WebNovelProviderPlugins.genericHtml),
        )

        val searchSource = WebNovelRemoteBookSource(
            accountId = plugin.manifest.accountId,
            endpointUrl = "https://novelbuddy.me/search?q=shadow%20slave&genres=fantasy",
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val page = searchSource.loadCatalogPage(1) {}
        val book = requireNotNull(page.items.firstOrNull { it.title == "Shadow Slave" })
        assertTrue((page.totalPages ?: 0) > 1)
        assertTrue((page.totalItems ?: 0) > 20)
        assertEquals("en", book.language)
        assertTrue(book.tags.any { it.equals("Fantasy", ignoreCase = true) })

        val bookSource = WebNovelRemoteBookSource(
            accountId = plugin.manifest.accountId,
            endpointUrl = book.downloadUrl,
            renderedChapterLoader = null,
            adapterRegistry = registry,
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

        println(
            buildString {
                appendLine("LIVE_WEB_NOVEL_EVIDENCE")
                appendLine("provider=${plugin.manifest.id}")
                appendLine("searchUrl=https://novelbuddy.me/search?q=shadow%20slave&genres=fantasy")
                appendLine("catalogItems=${page.totalItems}")
                appendLine("catalogPages=${page.totalPages}")
                appendLine("bookTitle=${book.title}")
                appendLine("bookUrl=${book.downloadUrl}")
                appendLine("author=${detail.author}")
                appendLine("status=${detail.status}")
                appendLine("chapterCount=${detail.totalChapters}")
                appendLine("firstChapterTitle=${chapters.first().title}")
                appendLine("firstChapterUrl=${chapters.first().downloadUrl}")
                appendLine("originalCharacters=${original.length}")
                append("webView=false")
            },
        )
    }
}
