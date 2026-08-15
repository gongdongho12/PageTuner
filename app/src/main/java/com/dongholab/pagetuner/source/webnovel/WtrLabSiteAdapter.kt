package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.source.RenderedChapterLoader
import com.dongholab.pagetuner.source.WebNovelHttpClient
import com.dongholab.pagetuner.source.WebNovelTextExtractor
import com.dongholab.pagetuner.source.wtr.WtrLabDomScraper
import java.io.IOException
import java.net.URI
import org.json.JSONObject
import com.dongholab.pagetuner.source.WtrLabCatalogQueryParams

class WtrLabSiteAdapter(
    override val chapterLoadStrategy: WebNovelChapterLoadStrategy =
        WebNovelChapterLoadStrategy.HttpThenWebView,
    private val postReaderJson: suspend (String, String, String) -> String =
        WebNovelHttpClient::postJson,
) : WebNovelSiteAdapter {
    override val id: String = "wtr-lab"
    override val displayName: String = "WTR-LAB"
    override val catalogCapabilities: WebNovelCatalogCapabilities = WebNovelCatalogCapabilities(
        remoteSearch = true,
        genreFilterKey = "genreId",
        genreOptions = WtrLabCatalogQueryParams.GENRE_OPTIONS.map { genre ->
            WebNovelCatalogOption(genre.id?.toString(), genre.label)
        },
        providerAdvancedControls = true,
    )

    override fun supports(url: String): Boolean = runCatching {
        URI(url).host.orEmpty().equals("wtr-lab.com", ignoreCase = true)
    }.getOrDefault(false)

    override fun classify(url: String): WebNovelPageKind = when {
        chapterNumber(url) != null -> WebNovelPageKind.Chapter
        Regex("/novel/\\d+/[^/?#]+", RegexOption.IGNORE_CASE).containsMatchIn(url) ->
            WebNovelPageKind.NovelDetail
        else -> WebNovelPageKind.Catalog
    }

    override fun siteTitle(html: String, url: String): String {
        return WebNovelTextExtractor.extractNovelTitle(html, "WTR-LAB")
    }

    override fun canonicalCatalogUrl(url: String): String = runCatching {
        val uri = URI(url)
        val language = language(url)
        val path = uri.path.orEmpty().trimEnd('/')
        val catalogPath = when {
            path.endsWith("/novel-list", ignoreCase = true) -> path
            path.isBlank() || path == "/" || path == "/$language" -> "/$language/novel-list"
            else -> path
        }
        buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority).append(catalogPath)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }.getOrDefault(url)

    override fun catalogRequest(url: String): WebNovelCatalogRequest {
        val request = WtrLabCatalogQueryParams.fromUrl(url)
        return WebNovelCatalogRequest(
            query = request.query,
            page = request.page,
            filters = buildMap {
                put("orderBy", request.orderBy)
                put("order", request.order)
                put("status", request.status)
                request.genreId?.let { put("genreId", it.toString()) }
            },
        )
    }

    override fun catalogSearchUrl(url: String, request: WebNovelCatalogRequest): String {
        val current = WtrLabCatalogQueryParams.fromUrl(url)
        return current.copy(
            query = request.query.trim(),
            genreId = request.filters["genreId"]?.toIntOrNull(),
            page = request.page.coerceAtLeast(1),
        ).buildUrl(url)
    }

    override fun parseCatalog(html: String, url: String): List<WebNovelSiteBook> {
        return parseCatalogPage(html, url).items
    }

    override fun parseCatalogPage(html: String, url: String): WebNovelCatalogPage {
        val response = WtrLabDomScraper.parseNovelListResponse(html, url, currentPage(url))
        val items = response.novels.map { novel ->
            WebNovelSiteBook(
                id = "novel_${novel.novelId}",
                title = novel.title,
                url = novelUrl(url, novel.novelId, novel.slug),
                authors = listOfNotNull(novel.author),
                language = language(url),
                coverUrl = novel.coverUrl,
                description = novel.description,
                chapterCount = novel.chapterCount,
            )
        }
        return WebNovelCatalogPage(
            url = url,
            currentPage = response.currentPage,
            totalPages = response.totalPages,
            totalItems = response.totalItems,
            items = items,
            hasPreviousPage = response.currentPage > 1,
            hasNextPage = response.hasNextPage,
        )
    }

    override fun parseDetail(html: String, url: String): WebNovelSiteDetail {
        val response = WtrLabDomScraper.parseNovelDetailResponse(novelId(url), html, url)
        return WebNovelSiteDetail(
            id = response.novelId.toString(),
            title = response.title,
            author = response.author,
            language = language(url),
            status = response.status,
            totalChapters = response.totalChapters,
            summary = response.summary,
            tags = response.tags,
            coverUrl = response.coverUrl,
            views = response.views,
            rating = response.rating,
        )
    }

    override fun parseChapters(html: String, url: String): List<WebNovelSiteChapter> {
        return WtrLabDomScraper.parseChapterListResponse(novelId(url), html, url).chapters.map { chapter ->
            val chapterUrl = resolveUrl(url, chapter.urlPath)
            WebNovelSiteChapter(
                id = WebNovelChapterKeys.fromUrl(chapterUrl, chapter.chapterNumber),
                number = chapter.chapterNumber,
                title = chapter.title,
                url = chapterUrl,
                language = language(url),
            )
        }
    }

    override suspend fun resolveChapterUrl(
        url: String,
        loadHtml: suspend (String) -> String,
    ): String {
        if (classify(url) == WebNovelPageKind.Chapter) return url
        if (classify(url) == WebNovelPageKind.NovelDetail) {
            return "${url.substringBefore('?').trimEnd('/')}/chapter-1"
        }
        val html = loadHtml(url)
        return WebNovelTextExtractor.parseNovelLinksFromHtml(html, url)
            .firstOrNull { (_, candidate, _) -> chapterNumber(candidate) != null }
            ?.second
            ?: throw IOException("No readable WTR-LAB chapter link was found at $url")
    }

    override suspend fun loadChapter(
        url: String,
        fallbackTitle: String,
        fetchHtml: suspend (String) -> String,
        renderedChapterLoader: RenderedChapterLoader?,
    ): WebNovelSiteChapterContent {
        val number = chapterNumber(url) ?: 1
        return WebNovelChapterLoadPolicy.load(
            strategy = chapterLoadStrategy,
            http = {
                val rawNovelId = novelId(url).takeIf { it > 0L }
                    ?: throw IOException("WTR-LAB chapter URL does not contain a novel ID: $url")
                val requestBody = JSONObject()
                    .put("translate", "ai")
                    .put("language", language(url))
                    .put("raw_id", rawNovelId)
                    .put("chapter_no", number)
                    .toString()
                val response = postReaderJson(readerApiUrl(url), requestBody, url)
                val parsed = WtrLabDomScraper.parseReaderChapterResponse(
                    novelId = rawNovelId,
                    chapterNumber = number,
                    rawJson = response,
                    language = language(url),
                )
                if (parsed.paragraphs.joinToString(" ").length < MIN_CONTENT_CHARS) {
                    throw IOException("WTR-LAB reader API returned no readable chapter body.")
                }
                WebNovelSiteChapterContent(
                    number = number,
                    title = parsed.titleTranslated
                        ?.takeIf(String::isNotBlank)
                        ?: parsed.titleOriginal.ifBlank { fallbackTitle },
                    paragraphs = parsed.paragraphs,
                )
            },
            webView = {
                val loader = renderedChapterLoader ?: throw IOException(
                    "The rendered WebView chapter loader is unavailable.",
                )
                val rendered = loader.loadChapter(url, number)
                WebNovelSiteChapterContent(
                    number = number,
                    title = rendered.title.ifBlank { fallbackTitle },
                    paragraphs = rendered.paragraphs,
                )
            },
        )
    }

    private fun readerApiUrl(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.rawAuthority}/api/reader/get"
    }.getOrElse { throw IOException("Invalid WTR-LAB chapter URL: $url", it) }

    private fun novelId(url: String): Long =
        Regex("/novel/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun chapterNumber(url: String): Int? =
        Regex("/chapter-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun currentPage(url: String): Int =
        Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun language(url: String): String =
        Regex("https?://[^/]+/([^/?#]+)").find(url)?.groupValues?.get(1)?.takeIf { it.length <= 3 } ?: "en"

    private fun novelUrl(baseUrl: String, novelId: Long, slug: String): String {
        val uri = URI(baseUrl)
        return "${uri.scheme}://${uri.authority}/${language(baseUrl)}/novel/$novelId/$slug"
    }

    private fun resolveUrl(baseUrl: String, value: String): String =
        runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)

    private companion object {
        const val MIN_CONTENT_CHARS = 100
    }
}
