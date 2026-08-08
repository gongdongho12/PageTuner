package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.source.RenderedChapterLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNovelSiteAdapterRegistryTest {
    @Test
    fun resolvesDedicatedAdapterBeforeGenericFallback() {
        val registry = WebNovelSiteAdapterRegistry(
            listOf(WtrLabSiteAdapter(), GenericWebNovelSiteAdapter()),
        )

        assertEquals("wtr-lab", registry.resolve("https://wtr-lab.com/en").id)
        assertEquals("generic-semantic-html", registry.resolve("https://novels.example/books").id)
    }

    @Test
    fun registeredSiteCanOverrideFallbackWithoutChangingSourceCode() {
        val registry = WebNovelSiteAdapterRegistry(listOf(GenericWebNovelSiteAdapter()))
        registry.register(FakeSiteAdapter("custom", "custom.example"))

        assertEquals("custom", registry.resolve("https://custom.example/library").id)
        assertTrue(registry.all().first().id == "custom")
    }

    @Test
    fun wtrAdapterClassifiesCatalogDetailAndChapterUrls() {
        val adapter = WtrLabSiteAdapter()

        assertEquals(WebNovelPageKind.Catalog, adapter.classify("https://wtr-lab.com/en"))
        assertEquals(
            WebNovelPageKind.NovelDetail,
            adapter.classify("https://wtr-lab.com/en/novel/42/example"),
        )
        assertEquals(
            WebNovelPageKind.Chapter,
            adapter.classify("https://wtr-lab.com/en/novel/42/example/chapter-7"),
        )
    }

    @Test
    fun genericAdapterPreservesDiscoveredUrlsAndChapterNumbers() {
        val adapter = GenericWebNovelSiteAdapter()
        val catalog = adapter.parseCatalog(
            html = """
                <a href="/book/alpha"><img src="/alpha.jpg">Alpha Novel</a>
            """.trimIndent(),
            url = "https://novels.example/library",
        )
        val chapters = adapter.parseChapters(
            html = """
                <a href="/book/alpha/chapter-2">Chapter 2: Return</a>
                <a href="/book/alpha/chapter-1">Chapter 1: Start</a>
            """.trimIndent(),
            url = "https://novels.example/book/alpha",
        )

        assertEquals("https://novels.example/book/alpha", catalog.single().url)
        assertEquals(listOf(1, 2), chapters.map { it.number })
        assertEquals("https://novels.example/book/alpha/chapter-1", chapters.first().url)
    }

    private class FakeSiteAdapter(
        override val id: String,
        private val host: String,
    ) : WebNovelSiteAdapter {
        override val displayName: String = id
        override fun supports(url: String) = url.contains(host)
        override fun classify(url: String) = WebNovelPageKind.Catalog
        override fun siteTitle(html: String, url: String) = id
        override fun parseCatalog(html: String, url: String) = emptyList<WebNovelSiteBook>()
        override fun parseDetail(html: String, url: String) = WebNovelSiteDetail(id, id)
        override fun parseChapters(html: String, url: String) = emptyList<WebNovelSiteChapter>()
        override suspend fun resolveChapterUrl(url: String, loadHtml: suspend (String) -> String) = url
        override suspend fun loadChapter(
            url: String,
            fallbackTitle: String,
            fetchHtml: suspend (String) -> String,
            renderedChapterLoader: RenderedChapterLoader?,
        ) = WebNovelSiteChapterContent(1, fallbackTitle, listOf("content"))
    }
}
