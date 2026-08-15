package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.source.webnovel.FactoryWebNovelProviderPlugin
import com.dongholab.pagetuner.source.webnovel.WebNovelPageKind
import com.dongholab.pagetuner.source.webnovel.WebNovelProviderManifest
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

/** Contract example for attaching the next website without changing common source code. */
class WebNovelProviderPluginFullFlowTest {
    @Test
    fun installedProviderRunsCatalogBookChapterAndOriginalDownloadFlow() = runTest {
        val plugin = FactoryWebNovelProviderPlugin(
            manifest = WebNovelProviderManifest(
                id = "sample-novels",
                displayName = "Sample Novels",
                accountId = "sample_novels",
                defaultCatalogUrl = CATALOG_URL,
            ),
            adapterFactory = ::SampleSiteAdapter,
        )
        val registry = WebNovelSiteAdapterRegistry.fromPlugins(listOf(plugin))
        val responses = mapOf(
            CATALOG_PAGE_URL to "catalog",
            BOOK_URL to "detail",
            CHAPTER_URL to "chapter",
        )
        val requests = mutableListOf<String>()
        val fetch: suspend (String) -> String = { url ->
            requests += url
            requireNotNull(responses[url]) { "Unexpected URL: $url" }
        }

        val catalogSource = WebNovelRemoteBookSource(
            accountId = plugin.manifest.accountId,
            endpointUrl = requireNotNull(plugin.manifest.defaultCatalogUrl),
            fetchHtml = fetch,
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val book = catalogSource.loadCatalogPage(1) {}.items.single()
        assertEquals("A Portable Story", book.title)
        assertEquals(BOOK_URL, book.downloadUrl)

        val bookSource = WebNovelRemoteBookSource(
            accountId = plugin.manifest.accountId,
            endpointUrl = book.downloadUrl,
            fetchHtml = fetch,
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val detail = bookSource.loadNovelDetail()
        val chapter = bookSource.list().single()
        val original = bookSource.download(chapter).toString(Charsets.UTF_8)

        assertEquals("Sample Author", detail.author)
        assertEquals(BOOK_URL, chapter.seriesId)
        assertEquals(1, chapter.chapterNumber)
        assertTrue(original.startsWith("# Chapter 1: Boarding"))
        assertTrue(original.contains("This paragraph is deliberately long enough"))
        assertEquals(listOf(CATALOG_PAGE_URL, BOOK_URL, CHAPTER_URL), requests)
    }

    private class SampleSiteAdapter : WebNovelSiteAdapter {
        override val id = "sample-novels"
        override val displayName = "Sample Novels"

        override fun supports(url: String) = url.startsWith("https://sample-novels.example/")

        override fun classify(url: String) = when {
            "/chapter-" in url -> WebNovelPageKind.Chapter
            "/book/" in url -> WebNovelPageKind.NovelDetail
            else -> WebNovelPageKind.Catalog
        }

        override fun siteTitle(html: String, url: String) = "Sample Novels"

        override fun parseCatalog(html: String, url: String) = listOf(
            WebNovelSiteBook(
                id = "portable-story",
                title = "A Portable Story",
                url = BOOK_URL,
                authors = listOf("Sample Author"),
                language = "en",
            ),
        )

        override fun parseDetail(html: String, url: String) = WebNovelSiteDetail(
            id = "portable-story",
            title = "A Portable Story",
            author = "Sample Author",
            language = "en",
            totalChapters = 1,
        )

        override fun parseChapters(html: String, url: String) = listOf(
            WebNovelSiteChapter(
                id = "chapter-1",
                number = 1,
                title = "Chapter 1: Boarding",
                url = CHAPTER_URL,
                language = "en",
            ),
        )

        override suspend fun resolveChapterUrl(
            url: String,
            loadHtml: suspend (String) -> String,
        ) = url

        override suspend fun loadChapter(
            url: String,
            fallbackTitle: String,
            fetchHtml: suspend (String) -> String,
            renderedChapterLoader: RenderedChapterLoader?,
        ): WebNovelSiteChapterContent {
            fetchHtml(url)
            return WebNovelSiteChapterContent(
                number = 1,
                title = "Chapter 1: Boarding",
                paragraphs = listOf(
                    "This paragraph is deliberately long enough to pass the common readable-content " +
                        "validation and prove that a newly installed provider reaches original download.",
                ),
            )
        }
    }

    private companion object {
        const val CATALOG_URL = "https://sample-novels.example/catalog"
        const val CATALOG_PAGE_URL = "$CATALOG_URL?page=1"
        const val BOOK_URL = "https://sample-novels.example/book/portable-story"
        const val CHAPTER_URL = "$BOOK_URL/chapter-1"
    }
}
