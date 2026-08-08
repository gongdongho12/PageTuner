package com.dongholab.pagetuner.source

import android.util.Log
import com.dongholab.pagetuner.common.DiagnosticLogger
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.source.scraper.WebNovelScraperRegistry
import com.dongholab.pagetuner.source.wtr.NovelDetailResponse
import com.dongholab.pagetuner.source.wtr.WtrLabDomScraper
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WebNovelRemoteBookSource(
    override val accountId: String,
    private val endpointUrl: String,
    private val fetchHtml: suspend (String) -> String = WebNovelHttpClient::fetchText,
    private val renderedChapterLoader: RenderedChapterLoader? = WebNovelPageRuntime.renderedChapterLoader,
) : RemoteBookSource {
    override val sourceType: RemoteSourceType = RemoteSourceType.WebNovel

    private val pageMutex = Mutex()
    @Volatile
    private var cachedEndpointHtml: String? = null
    @Volatile
    private var cachedItems: List<RemoteBookItem>? = null

    override suspend fun connect(): RemoteSourceConnection {
        val html = endpointHtml()
        val title = WebNovelTextExtractor.extractNovelTitle(html, fallback = "Web Novel Source")
        val itemCount = runCatching { itemsFromHtml(html).size }.getOrDefault(0)
        logD("Connected to $endpointUrl ($itemCount items, ${html.length} HTML chars)")
        return RemoteSourceConnection(
            sourceType = sourceType,
            accountId = accountId,
            title = title,
            itemCount = itemCount,
        )
    }

    override suspend fun list(): List<RemoteBookItem> = itemsFromHtml(endpointHtml())

    override suspend fun search(query: String): List<RemoteBookItem> {
        val items = list()
        if (query.isBlank()) return items
        return items.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.downloadUrl.contains(query, ignoreCase = true) ||
                it.authors.any { author -> author.contains(query, ignoreCase = true) }
        }
    }

    suspend fun loadNovelDetail(): NovelDetailResponse {
        val html = endpointHtml()
        val id = novelIdFromUrl(endpointUrl) ?: 0L
        return WebNovelScraperRegistry.findScraper(endpointUrl)
            .parseNovelDetail(id, html, endpointUrl)
    }

    override suspend fun download(item: RemoteBookItem): ByteArray {
        val targetUrl = resolveChapterUrl(item.downloadUrl)
        val chapterNumber = chapterNumberFromUrl(targetUrl) ?: 1
        val novelId = novelIdFromUrl(targetUrl) ?: novelIdFromUrl(item.downloadUrl) ?: 0L
        DiagnosticLogger.log("[WEB FETCH]", "Loading chapter $chapterNumber from $targetUrl")

        val response = if (isWtrLabUrl(targetUrl)) {
            val loader = renderedChapterLoader
            if (loader != null) {
                val rendered = loader.loadChapter(targetUrl, chapterNumber)
                com.dongholab.pagetuner.source.wtr.ChapterContentResponse(
                    novelId = novelId,
                    chapterNumber = chapterNumber,
                    titleOriginal = rendered.title.ifBlank { item.title },
                    paragraphs = rendered.paragraphs,
                )
            } else {
                throw IOException(
                    "This WTR-LAB chapter requires the app's rendered-page loader. " +
                        "Open it from PageTurner and try again.",
                )
            }
        } else {
            val html = fetchHtml(targetUrl)
            WebNovelScraperRegistry.findScraper(targetUrl)
                .parseChapterContent(novelId, chapterNumber, html)
        }

        val paragraphs = response.paragraphs
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val extractedText = paragraphs.joinToString("\n\n")
        if (extractedText.length < MIN_CHAPTER_CHARS) {
            throw IOException(
                "The chapter page loaded, but its rendered body was not available. " +
                    "Please retry after checking the network connection.",
            )
        }

        val finalTitle = response.titleOriginal.ifBlank { item.title }
        DiagnosticLogger.log(
            "[WEB PARSE SUCCESS]",
            "Extracted ${paragraphs.size} paragraphs (${extractedText.length} chars) from $targetUrl",
        )
        return buildString {
            append("# ").append(finalTitle).append("\n\n")
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
            val html = fetchHtml(endpointUrl)
            if (html.isBlank()) throw IOException("Web novel page returned no HTML: $endpointUrl")
            cachedEndpointHtml = html
            html
        }
    }

    private fun itemsFromHtml(html: String): List<RemoteBookItem> {
        cachedItems?.let { return it }
        return parseItems(html).also { cachedItems = it }
    }

    private fun parseItems(html: String): List<RemoteBookItem> {
        if (isWtrLabUrl(endpointUrl) && isNovelDetailUrl(endpointUrl)) {
            val novelId = novelIdFromUrl(endpointUrl) ?: 0L
            val response = WtrLabDomScraper.parseChapterListResponse(novelId, html, endpointUrl)
            return response.chapters.map { chapter ->
                createWebNovelItem(
                    id = "chapter_${chapter.chapterNumber}",
                    title = chapter.title,
                    downloadUrl = resolveUrl(endpointUrl, chapter.urlPath),
                    authors = emptyList(),
                    language = languageFromUrl(endpointUrl),
                )
            }
        }

        val catalog = WebNovelScraperRegistry.findScraper(endpointUrl).parseCatalog(html, endpointUrl)
        return catalog.novels.map { novel ->
            createWebNovelItem(
                id = "novel_${novel.novelId}",
                title = novel.title,
                downloadUrl = novelUrl(novel.novelId, novel.slug),
                coverUrl = novel.coverUrl,
                authors = emptyList(),
                language = languageFromUrl(endpointUrl),
                chapterCount = novel.chapterCount,
            )
        }
    }

    private suspend fun resolveChapterUrl(rawUrl: String): String {
        if (chapterNumberFromUrl(rawUrl) != null) return rawUrl
        if (isWtrLabUrl(rawUrl) && isNovelDetailUrl(rawUrl)) {
            return "${rawUrl.substringBefore('?').trimEnd('/')}/chapter-1"
        }

        val html = if (rawUrl == endpointUrl) endpointHtml() else fetchHtml(rawUrl)
        return WebNovelTextExtractor.parseNovelLinksFromHtml(html, rawUrl)
            .firstOrNull { (_, url, _) -> chapterNumberFromUrl(url) != null }
            ?.second
            ?: throw IOException("No readable chapter link was found on $rawUrl")
    }

    private fun createWebNovelItem(
        id: String,
        title: String,
        downloadUrl: String,
        coverUrl: String? = null,
        authors: List<String>,
        language: String,
        description: String? = null,
        chapterCount: Int? = null,
        tags: List<String> = emptyList(),
    ): RemoteBookItem = RemoteBookItem(
        identity = RemoteBookIdentity(
            sourceType = sourceType,
            accountId = accountId,
            remoteId = id,
        ),
        title = title,
        authors = authors,
        format = DocumentFormat.TEXT,
        language = language,
        contentType = "text/plain",
        downloadUrl = downloadUrl,
        coverUrl = coverUrl,
        description = description,
        chapterCount = chapterCount,
        tags = tags,
    )

    private fun novelUrl(novelId: Long, slug: String): String {
        val uri = URI(endpointUrl)
        val language = languageFromUrl(endpointUrl)
        return "${uri.scheme}://${uri.authority}/$language/novel/$novelId/$slug"
    }

    private fun resolveUrl(baseUrl: String, value: String): String =
        runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)

    private fun languageFromUrl(url: String): String =
        Regex("https?://[^/]+/([^/?#]+)").find(url)?.groupValues?.get(1)?.takeIf { it.length <= 3 } ?: "en"

    private fun isNovelDetailUrl(url: String): Boolean =
        Regex("/novel/\\d+/[^/?#]+").containsMatchIn(url) && chapterNumberFromUrl(url) == null

    private fun novelIdFromUrl(url: String): Long? =
        Regex("/novel/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    private fun chapterNumberFromUrl(url: String): Int? =
        Regex("/chapter-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun isWtrLabUrl(url: String): Boolean =
        runCatching { URI(url).host.orEmpty().equals("wtr-lab.com", ignoreCase = true) }.getOrDefault(false)

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }.onFailure { println("[$TAG] $message") }
    }

    private companion object {
        const val TAG = "WebNovelRemoteSource"
        const val MIN_CHAPTER_CHARS = 100
    }
}
