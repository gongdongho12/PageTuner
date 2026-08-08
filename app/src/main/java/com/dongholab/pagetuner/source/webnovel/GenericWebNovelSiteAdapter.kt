package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.source.RenderedChapterLoader
import com.dongholab.pagetuner.source.WebNovelTextExtractor
import com.dongholab.pagetuner.source.scraper.WebNovelScraperRegistry
import java.io.IOException
import java.net.URI

class GenericWebNovelSiteAdapter : WebNovelSiteAdapter {
    override val id: String = "generic-semantic-html"
    override val displayName: String = "Generic semantic HTML"

    override fun supports(url: String): Boolean = runCatching {
        URI(url).scheme in setOf("http", "https") && !URI(url).host.isNullOrBlank()
    }.getOrDefault(false)

    override fun classify(url: String): WebNovelPageKind = when {
        chapterNumber(url) != null || url.contains("/chapter/", ignoreCase = true) -> WebNovelPageKind.Chapter
        url.contains("/novel/", ignoreCase = true) || url.contains("/book/", ignoreCase = true) ->
            WebNovelPageKind.NovelDetail
        else -> WebNovelPageKind.Catalog
    }

    override fun siteTitle(html: String, url: String): String {
        return WebNovelTextExtractor.extractNovelTitle(html, URI(url).host ?: "Web novel source")
    }

    override fun parseCatalog(html: String, url: String): List<WebNovelSiteBook> {
        return WebNovelTextExtractor.parseNovelLinksFromHtml(html, url)
            .mapIndexed { index, (title, itemUrl, cover) ->
                WebNovelSiteBook(
                    id = stableRemoteId(itemUrl, index),
                    title = title,
                    url = itemUrl,
                    language = language(itemUrl),
                    coverUrl = cover,
                )
            }
            .distinctBy { it.url }
    }

    override fun parseDetail(html: String, url: String): WebNovelSiteDetail {
        val parsed = WebNovelScraperRegistry.findScraper(url)
            .parseNovelDetail(numericId(url), html, url)
        return WebNovelSiteDetail(
            id = parsed.novelId.toString(),
            title = parsed.title,
            author = parsed.author,
            language = language(url),
            status = parsed.status,
            totalChapters = parsed.totalChapters,
            summary = parsed.summary,
            tags = parsed.tags,
            coverUrl = parsed.coverUrl,
            views = parsed.views,
            rating = parsed.rating,
        )
    }

    override fun parseChapters(html: String, url: String): List<WebNovelSiteChapter> {
        return WebNovelTextExtractor.parseNovelLinksFromHtml(html, url)
            .mapIndexedNotNull { index, (title, chapterUrl, _) ->
                val number = chapterNumber(chapterUrl) ?: chapterNumber(title) ?: return@mapIndexedNotNull null
                WebNovelSiteChapter(
                    id = stableRemoteId(chapterUrl, index),
                    number = number,
                    title = title,
                    url = chapterUrl,
                    language = language(chapterUrl),
                )
            }
            .distinctBy { it.url }
            .sortedBy { it.number }
    }

    override suspend fun resolveChapterUrl(
        url: String,
        loadHtml: suspend (String) -> String,
    ): String {
        if (classify(url) == WebNovelPageKind.Chapter) return url
        val chapters = parseChapters(loadHtml(url), url)
        return chapters.firstOrNull()?.url
            ?: throw IOException("No readable chapter link was found at $url")
    }

    override suspend fun loadChapter(
        url: String,
        fallbackTitle: String,
        fetchHtml: suspend (String) -> String,
        renderedChapterLoader: RenderedChapterLoader?,
    ): WebNovelSiteChapterContent {
        val number = chapterNumber(url) ?: 1
        val response = WebNovelScraperRegistry.findScraper(url)
            .parseChapterContent(numericId(url), number, fetchHtml(url))
        return WebNovelSiteChapterContent(
            number = number,
            title = response.titleOriginal.ifBlank { fallbackTitle },
            paragraphs = response.paragraphs,
        )
    }

    private fun numericId(url: String): Long =
        Regex("/(?:novel|book)/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun chapterNumber(value: String): Int? =
        Regex("(?:chapter|ch)[-_ /]?(\\d+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.toIntOrNull()

    private fun language(url: String): String =
        Regex("https?://[^/]+/([^/?#]+)").find(url)?.groupValues?.get(1)?.takeIf { it.length in 2..3 } ?: "auto"

    private fun stableRemoteId(url: String, fallbackIndex: Int): String {
        return url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: "item_${fallbackIndex + 1}"
    }
}
