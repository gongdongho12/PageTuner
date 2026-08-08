package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.source.RenderedChapterLoader

enum class WebNovelPageKind {
    Catalog,
    NovelDetail,
    Chapter,
}

data class WebNovelSiteBook(
    val id: String,
    val title: String,
    val url: String,
    val authors: List<String> = emptyList(),
    val language: String = "auto",
    val coverUrl: String? = null,
    val description: String? = null,
    val chapterCount: Int? = null,
    val tags: List<String> = emptyList(),
)

data class WebNovelSiteDetail(
    val id: String,
    val title: String,
    val author: String = "Unknown author",
    val language: String = "auto",
    val status: String = "ongoing",
    val totalChapters: Int = 0,
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val coverUrl: String? = null,
    val views: String = "0",
    val rating: Float = 0f,
)

data class WebNovelSiteChapter(
    val id: String,
    val number: Int,
    val title: String,
    val url: String,
    val language: String = "auto",
)

data class WebNovelSiteChapterContent(
    val number: Int,
    val title: String,
    val paragraphs: List<String>,
)

/**
 * Site-level extension point. Implementations own URL classification, URL construction,
 * rendered/static loading policy, and mapping from site DOM models to common web-novel models.
 */
interface WebNovelSiteAdapter {
    val id: String
    val displayName: String

    fun supports(url: String): Boolean

    fun classify(url: String): WebNovelPageKind

    fun siteTitle(html: String, url: String): String

    fun parseCatalog(html: String, url: String): List<WebNovelSiteBook>

    fun parseDetail(html: String, url: String): WebNovelSiteDetail

    fun parseChapters(html: String, url: String): List<WebNovelSiteChapter>

    suspend fun resolveChapterUrl(
        url: String,
        loadHtml: suspend (String) -> String,
    ): String

    suspend fun loadChapter(
        url: String,
        fallbackTitle: String,
        fetchHtml: suspend (String) -> String,
        renderedChapterLoader: RenderedChapterLoader?,
    ): WebNovelSiteChapterContent
}

class WebNovelSiteAdapterRegistry(
    adapters: List<WebNovelSiteAdapter> = defaultAdapters(),
) {
    private val registered = adapters.toMutableList()

    @Synchronized
    fun register(adapter: WebNovelSiteAdapter) {
        registered.removeAll { it.id == adapter.id }
        registered.add(0, adapter)
    }

    @Synchronized
    fun resolve(url: String): WebNovelSiteAdapter {
        return registered.firstOrNull { it.supports(url) }
            ?: throw IllegalArgumentException("No web novel site adapter supports: $url")
    }

    @Synchronized
    fun all(): List<WebNovelSiteAdapter> = registered.toList()

    companion object {
        val default: WebNovelSiteAdapterRegistry by lazy { WebNovelSiteAdapterRegistry() }

        private fun defaultAdapters(): List<WebNovelSiteAdapter> = listOf(
            WtrLabSiteAdapter(),
            GenericWebNovelSiteAdapter(),
        )
    }
}
