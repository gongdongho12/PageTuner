package com.dongholab.pagetuner.source.service

import com.dongholab.pagetuner.source.scraper.GenericSemanticHtmlScraperAdapter
import com.dongholab.pagetuner.source.webnovel.NovelBuddySiteAdapter
import com.dongholab.pagetuner.source.webnovel.WebNovelChapterKeys
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NovelSourceServiceTest {
    @Test fun catalogFilterCapabilitiesUseProviderKeysAndRejectUnsupportedValues() {
        var requested = ""
        val service = NovelSourceService(object : NovelHttpTransport {
            override suspend fun fetchText(url: String): String { requested = url; throw IOException("Captured request") }
            override suspend fun postJson(url: String, body: String, referer: String): String = error("Unexpected POST")
        })
        assertThrows(IOException::class.java) { runBlocking {
            service.catalog("wtr-lab", null, "reader", 3, "9", "views", "asc", "completed")
        } }
        assertTrue(requested.contains("gi=9")); assertTrue(requested.contains("orderBy=view"))
        assertTrue(requested.contains("order=asc")); assertTrue(requested.contains("status=completed")); assertTrue(requested.contains("page=3"))
        assertThrows(IOException::class.java) { runBlocking { service.catalog("novelbuddy", null, "reader", 2, "action") } }
        assertTrue(requested.contains("genres=action")); assertTrue(requested.contains("page=2"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.catalog("novelbuddy", null, null, 1, orderBy = "views") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.catalog("wtr-lab", null, null, 1, genre = "99999") } }
    }
    @Test
    fun wtrCatalogDetailAndChapterUseOneInjectedTransportAndStableIdentities() = runBlocking {
        val calls = mutableListOf<String>()
        val bookUrl = "https://wtr-lab.com/en/novel/42/sample"
        val content = "An original paragraph long enough to be a readable chapter body. ".repeat(3)
        val transport = object : NovelHttpTransport {
            override suspend fun fetchText(url: String): String {
                calls += "GET $url"
                return if (url.contains("novel-finder")) next("""{"series":[{"raw_id":42,"slug":"sample","chapter_count":2,"data":{"title":"Sample"}}]}""")
                else next("""{"serie":{"serie_data":{"raw_id":42,"slug":"sample","chapter_count":2,"data":{"title":"Sample"}}}}""")
            }
            override suspend fun postJson(url: String, body: String, referer: String): String {
                calls += "POST $url"
                assertEquals("$bookUrl/chapter-1", referer)
                assertTrue(body.contains("\"raw_id\":42"))
                return """{"success":true,"chapter":{"raw_id":42,"order":1},"data":{"body":["$content"],"title":"First"}}"""
            }
        }
        val service = NovelSourceService(transport)
        assertEquals(listOf("wtr-lab", "novelbuddy", "generic-semantic-html"), service.sources().items.map { it.id })
        val catalog = service.catalog("wtr-lab", null, "sample", 1)
        assertEquals(bookUrl, catalog.items.single().bookId)
        val detail = service.detail(bookUrl, 1, 1)
        assertEquals(2, detail.totalItems)
        assertEquals(2, detail.chapters.single().number)
        assertFalse(detail.hasNext)
        val draft = service.fetchChapter("$bookUrl/chapter-1", bookUrl)
        assertEquals(bookUrl, draft.bookId)
        assertEquals(WebNovelChapterKeys.fromUrl(draft.chapterUrl, 1), draft.chapterId)
        assertEquals(listOf(content.trim()), draft.paragraphs)
        assertEquals(1, calls.count { it.startsWith("POST") })
        assertEquals("POST https://wtr-lab.com/api/reader/get", calls.last())
    }

    @Test
    fun invalidPagingAndMismatchedBookFailBeforeFetching() {
        val service = NovelSourceService(noNetwork)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.catalog("wtr-lab", null, null, 0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.detail("https://novelbuddy.me/sample", Int.MAX_VALUE, 100) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            service.fetchChapter("https://novelbuddy.me/sample/chapter-1", "https://novelbuddy.me/another")
        } }
    }

    @Test
    fun completeIndexFailuresAndCancellationAreNotConvertedToEmbeddedChapters() {
        val html = next("""{"initialManga":{"id":"sample","chapters":[{"number":1,"url":"/sample/chapter-1"}]}}""")
        val adapter = NovelBuddySiteAdapter()
        assertThrows(IOException::class.java) { runBlocking {
            adapter.loadChapters(html, "https://novelbuddy.me/sample") { throw IOException("Unavailable") }
        } }
        assertThrows(CancellationException::class.java) { runBlocking {
            adapter.loadChapters(html, "https://novelbuddy.me/sample") { throw CancellationException("Canceled") }
        } }
    }

    @Test
    fun successfulButPartialIndexIsReportedAsIncomplete() {
        val service = NovelSourceService(object : NovelHttpTransport {
            override suspend fun fetchText(url: String) = if (url.contains("/titles/"))
                """{"chapters":[{"number":1,"name":"First","url":"/sample/chapter-1"}]}"""
            else next("""{"initialManga":{"id":"sample","name":"Sample","stats":{"chaptersCount":2}}}""")
            override suspend fun postJson(url: String, body: String, referer: String): String = error("No POST")
        })
        assertThrows(IOException::class.java) { runBlocking { service.detail("https://novelbuddy.me/sample") } }
    }

    @Test
    fun genericDetailsDoNotInventAuthorRatingOrChapterCounts() {
        val detail = GenericSemanticHtmlScraperAdapter().parseNovelDetail(0, "<title>Example</title>", "https://example.org/book/sample")
        assertEquals(0, detail.totalChapters)
        assertEquals("Unknown author", detail.author)
        assertEquals(0f, detail.rating, 0f)
        assertEquals("0", detail.views)
        assertTrue(detail.tags.isEmpty())
    }

    private fun next(props: String) = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":$props}}</script>"""
    private val noNetwork = object : NovelHttpTransport {
        override suspend fun fetchText(url: String): String = error("Unexpected fetch")
        override suspend fun postJson(url: String, body: String, referer: String): String = error("Unexpected POST")
    }
}
