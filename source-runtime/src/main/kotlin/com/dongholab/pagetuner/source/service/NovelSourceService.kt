package com.dongholab.pagetuner.source.service

import com.dongholab.pagetuner.source.RenderedChapterLoader
import com.dongholab.pagetuner.source.webnovel.*
import java.io.IOException

/** Every provider request, including WTR reader POSTs and dedicated indexes, uses this boundary. */
interface NovelHttpTransport {
    suspend fun fetchText(url: String): String
    suspend fun postJson(url: String, body: String, referer: String): String
}

data class NovelSourceInfo(
    val id: String,
    val displayName: String,
    val defaultCatalogUrl: String?,
    val remoteSearch: Boolean,
    val requiresUrl: Boolean,
)
data class NovelSourcesResponse(val items: List<NovelSourceInfo>)
data class NovelBookSummary(
    val bookId: String,
    val title: String,
    val url: String,
    val authors: List<String>,
    val sourceLanguage: String,
    val coverUrl: String?,
    val description: String?,
    val chapterCount: Int?,
    val tags: List<String>,
)
data class NovelCatalogResponse(
    val sourceId: String,
    val url: String,
    val currentPage: Int,
    val totalPages: Int?,
    val totalItems: Int?,
    val hasPreviousPage: Boolean,
    val hasNextPage: Boolean,
    val items: List<NovelBookSummary>,
)
data class NovelChapterSummary(
    val chapterId: String,
    val number: Int,
    val title: String,
    val url: String,
    val sourceLanguage: String,
)
data class NovelDetailResponse(
    val sourceId: String,
    val bookId: String,
    val title: String,
    val url: String,
    val author: String,
    val sourceLanguage: String,
    val status: String,
    val totalChapters: Int,
    val summary: String,
    val tags: List<String>,
    val coverUrl: String?,
    val chapters: List<NovelChapterSummary>,
    val page: Int,
    val size: Int,
    val totalItems: Int,
    val totalPages: Int,
    val hasNext: Boolean,
)
data class SourceChapterDraft(
    val providerId: String,
    val bookId: String,
    val bookTitle: String,
    val bookUrl: String,
    val chapterId: String,
    val chapterTitle: String,
    val chapterUrl: String,
    val sourceLanguage: String,
    val paragraphs: List<String>,
)

class NovelSourceService(
    private val transport: NovelHttpTransport,
    private val renderedChapterLoader: RenderedChapterLoader? = null,
) {
    private val registry = WebNovelSiteAdapterRegistry(listOf(
        WtrLabSiteAdapter(
            chapterLoadStrategy = if (renderedChapterLoader == null) WebNovelChapterLoadStrategy.HttpOnly
                else WebNovelChapterLoadStrategy.HttpThenWebView,
            postReaderJson = transport::postJson,
        ),
        NovelBuddySiteAdapter(),
        GenericWebNovelSiteAdapter(),
    ))

    fun sources(): NovelSourcesResponse = NovelSourcesResponse(WebNovelProviderPlugins.builtIn.map { plugin ->
        val manifest = plugin.manifest
        val adapter = registry.all().first { it.id == manifest.id }
        NovelSourceInfo(manifest.id, manifest.displayName, manifest.defaultCatalogUrl,
            adapter.catalogCapabilities.remoteSearch, manifest.defaultCatalogUrl == null)
    })

    suspend fun catalog(sourceId: String, url: String?, query: String?, page: Int): NovelCatalogResponse {
        require(page in 1..100_000) { "page must be between 1 and 100000." }
        require(query == null || query.length <= 200) { "query must be at most 200 characters." }
        val source = sources().items.firstOrNull { it.id == sourceId }
            ?: throw IllegalArgumentException("Unknown novel source.")
        val adapter = registry.all().first { it.id == sourceId }
        val initialUrl = url?.takeIf(String::isNotBlank) ?: source.defaultCatalogUrl
            ?: throw IllegalArgumentException("A catalog URL is required for this source.")
        require(adapter.supports(initialUrl)) { "The URL does not belong to the selected source." }
        require(adapter.classify(initialUrl) == WebNovelPageKind.Catalog) { "A catalog URL is required." }
        val canonical = adapter.canonicalCatalogUrl(initialUrl)
        val search = query?.trim()?.takeIf(String::isNotBlank)
        val pageUrl = if (search != null) {
            adapter.catalogSearchUrl(canonical, adapter.catalogRequest(canonical).copy(query = search, page = page))
                ?: throw IllegalArgumentException("This source does not provide remote keyword search.")
        } else adapter.catalogPageUrl(canonical, page)
        val parsed = adapter.parseCatalogPage(transport.fetchText(pageUrl), pageUrl)
        return NovelCatalogResponse(adapter.id, parsed.url, parsed.currentPage, parsed.totalPages,
            parsed.totalItems, parsed.hasPreviousPage, parsed.hasNextPage, parsed.items.map { book ->
                NovelBookSummary(WebNovelSeriesKeys.fromUrl(book.url), book.title, book.url, book.authors,
                    book.language, book.coverUrl, book.description, book.chapterCount?.takeIf { it > 0 }, book.tags)
            })
    }

    suspend fun detail(url: String, page: Int = 0, size: Int = 20): NovelDetailResponse {
        require(page >= 0 && size in 1..100 && page.toLong() * size <= Int.MAX_VALUE) {
            "page must be nonnegative, size must be between 1 and 100, and offset must fit an integer."
        }
        val adapter = registry.resolve(url)
        require(adapter.classify(url) == WebNovelPageKind.NovelDetail) { "A book detail URL is required." }
        val html = transport.fetchText(url)
        val detail = adapter.parseDetail(html, url)
        val chapters = adapter.loadChapters(html, url, transport::fetchText)
        if (chapters.size > MAX_CHAPTERS) throw IOException("The provider chapter index exceeds the supported limit.")
        if (detail.totalChapters > 0 && chapters.size < detail.totalChapters) {
            throw IOException("The provider returned an incomplete chapter index; retry the book details.")
        }
        val offset = page.toLong() * size
        val totalPages = (chapters.size + size - 1) / size
        return NovelDetailResponse(adapter.id, WebNovelSeriesKeys.fromUrl(url), detail.title, url,
            detail.author, detail.language, detail.status, detail.totalChapters.takeIf { it > 0 } ?: chapters.size,
            detail.summary, detail.tags, detail.coverUrl,
            chapters.drop(offset.coerceAtMost(chapters.size.toLong()).toInt()).take(size).map { chapter ->
                NovelChapterSummary(WebNovelChapterKeys.fromUrl(chapter.url, chapter.number), chapter.number,
                    chapter.title, chapter.url, chapter.language)
            }, page, size, chapters.size, totalPages, offset + size < chapters.size)
    }

    suspend fun fetchChapter(url: String, bookUrl: String?): SourceChapterDraft {
        val adapter = registry.resolve(url)
        require(adapter.classify(url) == WebNovelPageKind.Chapter) { "A chapter URL is required." }
        val canonicalBookUrl = WebNovelSeriesKeys.fromUrl(url)
        val requestedBookUrl = bookUrl?.takeIf(String::isNotBlank) ?: canonicalBookUrl
        require(registry.resolve(requestedBookUrl).id == adapter.id &&
            WebNovelSeriesKeys.fromUrl(requestedBookUrl) == canonicalBookUrl) {
            "The chapter does not belong to the supplied book URL."
        }
        val detail = adapter.parseDetail(transport.fetchText(requestedBookUrl), requestedBookUrl)
        val content = adapter.loadChapter(url, "Chapter", transport::fetchText, renderedChapterLoader)
        val expectedNumber = WebNovelChapterNumbers.fromUrl(url)
        if (expectedNumber != null && content.number != expectedNumber) {
            throw IOException("The provider returned a different chapter than requested.")
        }
        val paragraphs = content.paragraphs.map(String::trim).filter(String::isNotBlank)
        val characters = paragraphs.sumOf { it.length.toLong() }
        if (characters < 100 || characters > 2_000_000 || paragraphs.size > 50_000) {
            throw IOException("The provider did not return a supported readable chapter body.")
        }
        return SourceChapterDraft(adapter.id, canonicalBookUrl, detail.title, requestedBookUrl,
            WebNovelChapterKeys.fromUrl(url, content.number), content.title, url, detail.language, paragraphs)
    }

    companion object { const val MAX_CHAPTERS = 100_000 }
}
