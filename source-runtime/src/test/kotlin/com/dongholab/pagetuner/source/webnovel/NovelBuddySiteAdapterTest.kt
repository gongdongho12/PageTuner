package com.dongholab.pagetuner.source.webnovel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelBuddySiteAdapterTest {
    private val adapter = NovelBuddySiteAdapter()

    @Test
    fun classifiesNovelBuddyRoutesAndCanonicalizesHome() {
        assertEquals(WebNovelPageKind.Catalog, adapter.classify("https://novelbuddy.me/home"))
        assertEquals(WebNovelPageKind.Catalog, adapter.classify("https://novelbuddy.me/genres/fantasy"))
        assertEquals(WebNovelPageKind.NovelDetail, adapter.classify("https://novelbuddy.me/shadow-slave"))
        assertEquals(
            WebNovelPageKind.Chapter,
            adapter.classify("https://novelbuddy.me/shadow-slave/chapter-1-nightmare-begins"),
        )
        assertEquals("https://novelbuddy.me/search", adapter.canonicalCatalogUrl("https://novelbuddy.me/home"))
    }

    @Test
    fun buildsProviderOwnedSearchAndPreservesFiltersAcrossPages() {
        val input = "https://novelbuddy.me/search?q=old&genres=fantasy&status=ongoing&page=4"
        val request = adapter.catalogRequest(input)
        val search = adapter.catalogSearchUrl(input, request.copy(query = "shadow slave", page = 1))

        assertEquals("fantasy", request.filters["genres"])
        assertEquals(
            "https://novelbuddy.me/search?q=shadow%20slave&genres=fantasy&status=ongoing&page=1",
            search,
        )
        assertEquals(
            "https://novelbuddy.me/search?q=old&genres=fantasy&status=ongoing&page=7",
            adapter.catalogPageUrl(input, 7),
        )
        assertTrue(adapter.catalogCapabilities.remoteSearch)
        assertEquals("genres", adapter.catalogCapabilities.genreFilterKey)
    }

    @Test
    fun loadsDedicatedChapterIndexInsteadOfOnlyEmbeddedPage() = runTest {
        val html = nextHtml(
            """{"initialManga":{"id":"book-id","url":"/sample","name":"Sample","cv":10,
              "stats":{"chaptersCount":2},"chapters":[{"id":"c2","number":2,"name":"Chapter 2","url":"/sample/chapter-2-end"}]}}""",
        )
        var fetchedUrl: String? = null

        val chapters = adapter.loadChapters(html, "https://novelbuddy.me/sample") { url ->
            fetchedUrl = url
            """{"data":{"chapters":[
              {"id":"c2","number":2,"name":"Chapter 2","url":"/sample/chapter-2-end"},
              {"id":"c1","number":1,"name":"Chapter 1","url":"/sample/chapter-1-start"}
            ]}}"""
        }

        assertEquals("https://api.novelbuddy.me/titles/book-id/chapters?cv=10", fetchedUrl)
        assertEquals(listOf(1, 2), chapters.map { it.number })
    }

    private fun nextHtml(pageProps: String): String =
        """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":$pageProps}}</script>"""
}
