package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.source.RenderedChapterLoader
import com.dongholab.pagetuner.source.WebNovelTextExtractor
import com.dongholab.pagetuner.source.wtr.WtrLabDomScraper
import java.io.IOException
import java.net.URI

class WtrLabSiteAdapter : WebNovelSiteAdapter {
    override val id: String = "wtr-lab"
    override val displayName: String = "WTR-LAB"

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
                language = language(url),
                coverUrl = novel.coverUrl,
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
            WebNovelSiteChapter(
                id = "chapter_${chapter.chapterNumber}",
                number = chapter.chapterNumber,
                title = chapter.title,
                url = resolveUrl(url, chapter.urlPath),
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
        val loader = renderedChapterLoader ?: throw IOException(
            "WTR-LAB requires the app rendered-page loader.",
        )
        val rendered = loader.loadChapter(url, number)
        return WebNovelSiteChapterContent(
            number = number,
            title = rendered.title.ifBlank { fallbackTitle },
            paragraphs = rendered.paragraphs,
        )
    }

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
}
