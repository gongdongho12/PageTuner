package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.source.webnovel.WebNovelPageKind
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapter
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteBook
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteChapter
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteChapterContent
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteDetail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNovelRemoteBookSourceAdapterTest {
    @Test
    fun sourceDelegatesSiteBehaviorToInjectedAdapter() = runTest {
        val adapter = FixtureAdapter()
        val source = WebNovelRemoteBookSource(
            accountId = "fixture-account",
            endpointUrl = "https://fixture.example/catalog",
            fetchHtml = { "<html>fixture</html>" },
            adapterRegistry = WebNovelSiteAdapterRegistry(listOf(adapter)),
        )

        val connection = source.connect()
        val item = source.list().single()
        val detail = source.loadNovelDetail()
        val content = source.download(item).toString(Charsets.UTF_8)

        assertEquals("Fixture site", connection.title)
        assertEquals("book-1", item.identity.remoteId)
        assertEquals("Fixture detail", detail.title)
        assertTrue(content.startsWith("# Fixture chapter"))
        assertTrue(adapter.chapterLoads == 1)
    }

    private class FixtureAdapter : WebNovelSiteAdapter {
        override val id: String = "fixture"
        override val displayName: String = "Fixture"
        var chapterLoads = 0

        override fun supports(url: String) = url.contains("fixture.example")
        override fun classify(url: String) = WebNovelPageKind.Catalog
        override fun siteTitle(html: String, url: String) = "Fixture site"
        override fun parseCatalog(html: String, url: String) = listOf(
            WebNovelSiteBook("book-1", "Fixture book", "https://fixture.example/chapter/1"),
        )
        override fun parseDetail(html: String, url: String) = WebNovelSiteDetail("book-1", "Fixture detail")
        override fun parseChapters(html: String, url: String) = emptyList<WebNovelSiteChapter>()
        override suspend fun resolveChapterUrl(url: String, loadHtml: suspend (String) -> String) = url
        override suspend fun loadChapter(
            url: String,
            fallbackTitle: String,
            fetchHtml: suspend (String) -> String,
            renderedChapterLoader: RenderedChapterLoader?,
        ): WebNovelSiteChapterContent {
            chapterLoads += 1
            return WebNovelSiteChapterContent(
                number = 1,
                title = "Fixture chapter",
                paragraphs = listOf("Readable fixture paragraph. ".repeat(8)),
            )
        }
    }
}
