package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.core.paging.PageResult
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

/** One server-side catalog page. This is intentionally separate from E-Ink viewport paging. */
data class WebNovelCatalogPage(
    val url: String,
    override val currentPage: Int = 1,
    override val totalPages: Int? = null,
    override val totalItems: Int? = null,
    override val items: List<WebNovelSiteBook> = emptyList(),
    override val hasPreviousPage: Boolean = currentPage > 1,
    override val hasNextPage: Boolean = totalPages?.let { currentPage < it } ?: false,
) : PageResult<WebNovelSiteBook>

/** URL helper shared by every paginated web-novel adapter. */
object WebNovelCatalogPageUrls {
    fun withPage(url: String, page: Int): String {
        val safePage = page.coerceAtLeast(1)
        val fragment = url.substringAfter('#', missingDelimiterValue = "")
        val withoutFragment = url.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val queryParts = withoutFragment.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter(String::isNotBlank)
            .filterNot { it.substringBefore('=').equals("page", ignoreCase = true) }
            .toMutableList()
            .apply { add("page=$safePage") }
        return buildString {
            append(base)
            append('?')
            append(queryParts.joinToString("&"))
            if (fragment.isNotBlank()) append('#').append(fragment)
        }
    }

    fun currentPage(url: String): Int =
        Regex("[?&]page=(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1
}

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

data class WebNovelCatalogOption(
    val key: String?,
    val label: String,
)

data class WebNovelCatalogCapabilities(
    val remoteSearch: Boolean = false,
    val genreFilterKey: String? = null,
    val genreOptions: List<WebNovelCatalogOption> = emptyList(),
    val providerAdvancedControls: Boolean = false,
)

/**
 * Site-level extension point. Implementations own URL classification, URL construction,
 * rendered/static loading policy, and mapping from site DOM models to common web-novel models.
 */
interface WebNovelSiteAdapter {
    val id: String
    val displayName: String
    val chapterLoadStrategy: WebNovelChapterLoadStrategy
        get() = WebNovelChapterLoadStrategy.HttpOnly
    val catalogCapabilities: WebNovelCatalogCapabilities
        get() = WebNovelCatalogCapabilities()

    fun supports(url: String): Boolean

    fun classify(url: String): WebNovelPageKind

    fun siteTitle(html: String, url: String): String

    /** Converts a site landing URL into its complete, pageable catalog URL when needed. */
    fun canonicalCatalogUrl(url: String): String = url

    fun catalogPageUrl(url: String, page: Int): String =
        WebNovelCatalogPageUrls.withPage(canonicalCatalogUrl(url), page)

    /** Reads provider-specific URL parameters into state that the common catalog UI can retain. */
    fun catalogRequest(url: String): WebNovelCatalogRequest = WebNovelCatalogRequest(
        page = WebNovelCatalogPageUrls.currentPage(url),
    )

    /** Returns null when a provider has no remote keyword-search endpoint. */
    fun catalogSearchUrl(url: String, request: WebNovelCatalogRequest): String? = null

    fun parseCatalog(html: String, url: String): List<WebNovelSiteBook>

    fun parseCatalogPage(html: String, url: String): WebNovelCatalogPage = WebNovelCatalogPage(
        url = url,
        currentPage = WebNovelCatalogPageUrls.currentPage(url),
        items = parseCatalog(html, url),
    )

    fun parseDetail(html: String, url: String): WebNovelSiteDetail

    fun parseChapters(html: String, url: String): List<WebNovelSiteChapter>

    /** Providers may fetch a dedicated chapter index while keeping common source orchestration. */
    suspend fun loadChapters(
        html: String,
        url: String,
        fetchText: suspend (String) -> String,
    ): List<WebNovelSiteChapter> = parseChapters(html, url)

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

    fun register(plugin: WebNovelProviderPlugin) {
        register(plugin.createAdapter())
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

        fun fromPlugins(plugins: List<WebNovelProviderPlugin>): WebNovelSiteAdapterRegistry =
            WebNovelSiteAdapterRegistry(plugins.map(WebNovelProviderPlugin::createAdapter))

        private fun defaultAdapters(): List<WebNovelSiteAdapter> =
            WebNovelProviderPlugins.builtIn.map(WebNovelProviderPlugin::createAdapter)
    }
}
