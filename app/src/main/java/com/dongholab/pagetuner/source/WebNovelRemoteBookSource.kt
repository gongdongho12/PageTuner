package com.dongholab.pagetuner.source

import android.util.Log
import com.dongholab.pagetuner.common.DiagnosticLogger
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.source.webnovel.WebNovelPageKind
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapter
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteBook
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteChapter
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteDetail
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * RemoteBookSource orchestration for web novels. Site-specific URL, DOM, and rendering behavior
 * lives behind [WebNovelSiteAdapter]; this class only handles caching and common model mapping.
 */
class WebNovelRemoteBookSource(
    override val accountId: String,
    private val endpointUrl: String,
    private val fetchHtml: suspend (String) -> String = WebNovelHttpClient::fetchText,
    private val renderedChapterLoader: RenderedChapterLoader? = WebNovelPageRuntime.renderedChapterLoader,
    adapterRegistry: WebNovelSiteAdapterRegistry = WebNovelSiteAdapterRegistry.default,
) : RemoteBookSource, PaginatedRemoteBookSource {
    override val sourceType: RemoteSourceType = RemoteSourceType.WebNovel

    private val siteAdapter: WebNovelSiteAdapter = adapterRegistry.resolve(endpointUrl)
    private val resolvedEndpointUrl: String = when (siteAdapter.classify(endpointUrl)) {
        WebNovelPageKind.Catalog -> siteAdapter.canonicalCatalogUrl(endpointUrl)
        else -> endpointUrl
    }
    private val pageMutex = Mutex()
    @Volatile
    private var cachedEndpointHtml: String? = null
    @Volatile
    private var cachedItems: List<RemoteBookItem>? = null

    override suspend fun connect(): RemoteSourceConnection {
        val html = endpointHtml()
        val items = itemsFromHtml(html)
        val title = siteAdapter.siteTitle(html, resolvedEndpointUrl)
        logD("Connected through ${siteAdapter.id} to $resolvedEndpointUrl (${items.size} items)")
        return RemoteSourceConnection(
            sourceType = sourceType,
            accountId = accountId,
            title = title,
            itemCount = items.size,
        )
    }

    override suspend fun list(): List<RemoteBookItem> = itemsFromHtml(endpointHtml())

    override suspend fun loadCatalogPage(
        page: Int,
        onStep: (RemoteCatalogLoadStep) -> Unit,
    ): RemoteCatalogPage {
        check(siteAdapter.classify(resolvedEndpointUrl) == WebNovelPageKind.Catalog) {
            "The requested web-novel URL is not a catalog: $resolvedEndpointUrl"
        }
        val pageUrl = siteAdapter.catalogPageUrl(resolvedEndpointUrl, page)
        onStep(RemoteCatalogLoadStep.FetchingPage)
        val html = if (pageUrl == resolvedEndpointUrl) endpointHtml() else loadHtml(pageUrl)
        onStep(RemoteCatalogLoadStep.ParsingDom)
        val parsed = siteAdapter.parseCatalogPage(html, pageUrl)
        return RemoteCatalogPage(
            title = siteAdapter.siteTitle(html, pageUrl),
            url = parsed.url,
            items = parsed.items.map(::bookItem),
            currentPage = parsed.currentPage,
            totalPages = parsed.totalPages,
            totalItems = parsed.totalItems,
            hasPreviousPage = parsed.hasPreviousPage,
            hasNextPage = parsed.hasNextPage,
        )
    }

    override suspend fun search(query: String): List<RemoteBookItem> {
        val items = list()
        if (query.isBlank()) return items
        return items.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.downloadUrl.contains(query, ignoreCase = true) ||
                it.authors.any { author -> author.contains(query, ignoreCase = true) } ||
                it.description.orEmpty().contains(query, ignoreCase = true) ||
                it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
        }
    }

    suspend fun loadNovelDetail(): WebNovelSiteDetail {
        return siteAdapter.parseDetail(endpointHtml(), resolvedEndpointUrl)
    }

    override suspend fun download(item: RemoteBookItem): ByteArray {
        val targetUrl = siteAdapter.resolveChapterUrl(item.downloadUrl, ::loadHtml)
        DiagnosticLogger.log("[WEB FETCH]", "${siteAdapter.id} loading $targetUrl")
        val content = siteAdapter.loadChapter(
            url = targetUrl,
            fallbackTitle = item.title,
            fetchHtml = fetchHtml,
            renderedChapterLoader = renderedChapterLoader,
        )
        val paragraphs = content.paragraphs.map(String::trim).filter(String::isNotBlank)
        val extractedText = paragraphs.joinToString("\n\n")
        if (extractedText.length < MIN_CHAPTER_CHARS) {
            throw IOException(
                "The chapter page loaded through ${siteAdapter.displayName}, but its rendered body was not available.",
            )
        }
        DiagnosticLogger.log(
            "[WEB PARSE SUCCESS]",
            "${siteAdapter.id} extracted ${paragraphs.size} paragraphs (${extractedText.length} chars)",
        )
        return buildString {
            append("# ").append(content.title.ifBlank { item.title }).append("\n\n")
            append(extractedText)
        }.toByteArray(Charsets.UTF_8)
    }

    override suspend fun refresh(): List<RemoteBookItem> {
        pageMutex.withLock {
            cachedEndpointHtml = null
            cachedItems = null
        }
        return list()
    }

    private suspend fun endpointHtml(): String {
        cachedEndpointHtml?.let { return it }
        return pageMutex.withLock {
            cachedEndpointHtml?.let { return@withLock it }
            loadHtml(resolvedEndpointUrl).also { cachedEndpointHtml = it }
        }
    }

    private suspend fun loadHtml(url: String): String {
        val html = fetchHtml(url)
        if (html.isBlank()) throw IOException("Web novel page returned no HTML: $url")
        return html
    }

    private suspend fun itemsFromHtml(html: String): List<RemoteBookItem> {
        cachedItems?.let { return it }
        val items = when (siteAdapter.classify(resolvedEndpointUrl)) {
            WebNovelPageKind.Catalog -> siteAdapter.parseCatalog(html, resolvedEndpointUrl).map(::bookItem)
            WebNovelPageKind.NovelDetail -> {
                val detail = siteAdapter.parseDetail(html, resolvedEndpointUrl)
                siteAdapter.loadChapters(html, resolvedEndpointUrl, ::loadHtml)
                    .map { chapter -> chapterItem(chapter, detail) }
            }
            WebNovelPageKind.Chapter -> {
                val directChapterNumber = com.dongholab.pagetuner.source.webnovel.WebNovelChapterNumbers
                    .fromUrl(resolvedEndpointUrl)
                    ?: 1
                listOf(chapterItem(
                    WebNovelSiteChapter(
                        id = com.dongholab.pagetuner.source.webnovel.WebNovelChapterKeys.fromUrl(
                            resolvedEndpointUrl,
                            directChapterNumber,
                        ),
                        number = directChapterNumber,
                        title = siteAdapter.siteTitle(html, resolvedEndpointUrl),
                        url = resolvedEndpointUrl,
                        language = languageFromUrl(resolvedEndpointUrl),
                    ),
                ))
            }
        }
        return items.also { cachedItems = it }
    }

    private fun bookItem(book: WebNovelSiteBook): RemoteBookItem = RemoteBookItem(
        identity = RemoteBookIdentity(sourceType, accountId, book.id),
        title = book.title,
        authors = book.authors,
        format = DocumentFormat.TEXT,
        language = book.language,
        contentType = "text/plain",
        downloadUrl = book.url,
        coverUrl = book.coverUrl,
        description = book.description,
        chapterCount = book.chapterCount,
        tags = book.tags,
        seriesId = com.dongholab.pagetuner.source.webnovel.WebNovelSeriesKeys.fromUrl(book.url),
        seriesTitle = book.title,
    )

    private fun chapterItem(
        chapter: WebNovelSiteChapter,
        detail: WebNovelSiteDetail? = null,
    ): RemoteBookItem {
        val seriesId = com.dongholab.pagetuner.source.webnovel.WebNovelSeriesKeys.fromUrl(chapter.url)
        val chapterId = com.dongholab.pagetuner.source.webnovel.WebNovelChapterKeys.fromUrl(
            chapter.url,
            chapter.number,
        )
        return RemoteBookItem(
            identity = RemoteBookIdentity(sourceType, accountId, chapterId),
            title = chapter.title,
            format = DocumentFormat.TEXT,
            language = chapter.language,
            contentType = "text/plain",
            downloadUrl = chapter.url,
            seriesId = seriesId,
            seriesTitle = detail?.title,
            chapterNumber = chapter.number,
        )
    }

    private fun languageFromUrl(url: String): String = runCatching {
        java.net.URI(url).path.orEmpty()
            .split('/')
            .firstOrNull { segment -> segment.matches(Regex("[A-Za-z]{2,3}")) }
            ?.lowercase()
    }.getOrNull() ?: "auto"

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }.onFailure { println("[$TAG] $message") }
    }

    private companion object {
        const val TAG = "WebNovelRemoteSource"
        const val MIN_CHAPTER_CHARS = 100
    }
}
