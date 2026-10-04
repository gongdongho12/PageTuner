package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.source.RenderedChapterLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNovelProviderPluginTest {
    @Test
    fun builtInPluginsHaveUniqueManifestsAndMatchingAdapters() {
        val plugins = WebNovelProviderPlugins.builtIn

        assertEquals(plugins.size, plugins.map { it.manifest.id }.distinct().size)
        plugins.forEach { plugin ->
            val adapter = plugin.createAdapter()
            assertEquals(plugin.manifest.id, adapter.id)
            assertEquals(plugin.manifest.displayName, adapter.displayName)
            plugin.manifest.defaultCatalogUrl?.let { url ->
                assertTrue(adapter.supports(url))
                assertEquals(WebNovelPageKind.Catalog, adapter.classify(url))
            }
        }
        assertFalse(WebNovelProviderPlugins.discoverable.contains(WebNovelProviderPlugins.genericHtml))
    }

    @Test
    fun registryCanBeComposedOnlyFromPlugins() {
        val registry = WebNovelSiteAdapterRegistry.fromPlugins(
            listOf(WebNovelProviderPlugins.novelBuddy, WebNovelProviderPlugins.genericHtml),
        )

        assertEquals("novelbuddy", registry.resolve("https://novelbuddy.me/search").id)
        assertEquals("generic-semantic-html", registry.resolve("https://another.example/books").id)
    }

    @Test
    fun pluginFactoryRejectsManifestAndAdapterIdMismatch() {
        val plugin = FactoryWebNovelProviderPlugin(
            manifest = WebNovelProviderManifest(
                id = "declared-id",
                displayName = "Broken provider",
                accountId = "broken",
            ),
        ) { MinimalAdapter("actual-id", "broken.example") }

        assertThrows(IllegalArgumentException::class.java) { plugin.createAdapter() }
    }

    private class MinimalAdapter(
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
