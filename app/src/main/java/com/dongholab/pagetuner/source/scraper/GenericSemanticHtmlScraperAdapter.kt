package com.dongholab.pagetuner.source.scraper

import com.dongholab.pagetuner.source.WebNovelTextExtractor
import com.dongholab.pagetuner.source.wtr.ChapterContentResponse
import com.dongholab.pagetuner.source.wtr.ChapterNavigationDto
import com.dongholab.pagetuner.source.wtr.NovelDetailResponse
import com.dongholab.pagetuner.source.wtr.NovelListResponse
import com.dongholab.pagetuner.source.wtr.NovelSummaryItem

/**
 * Universal Generic Web Novel Scraper Adapter.
 * Employs HTML5 semantic tags (OpenGraph, LD+JSON, article, main) for any custom web novel website.
 */
class GenericSemanticHtmlScraperAdapter : WebNovelScraperEngine {
    override val name: String = "Generic Web Novel Adapter"

    override fun canHandle(url: String): Boolean {
        // Fallback adapter capable of attempting parsing for any valid web URL
        return url.startsWith("http://") || url.startsWith("https://")
    }

    override fun parseCatalog(html: String, baseUrl: String): NovelListResponse {
        val rawLinks = WebNovelTextExtractor.parseNovelLinksFromHtml(html, baseUrl)
        val items = rawLinks.mapIndexed { index, (title, url, cover) ->
            NovelSummaryItem(
                novelId = index + 1L,
                slug = url.substringAfterLast("/"),
                title = title,
                coverUrl = cover,
                chapterCount = 50,
                status = "ongoing",
            )
        }
        return NovelListResponse(
            currentPage = 1,
            hasNextPage = false,
            novels = items,
        )
    }

    override fun parseNovelDetail(novelId: Long, html: String, url: String): NovelDetailResponse {
        val title = WebNovelTextExtractor.extractNovelTitle(html, fallback = "Custom Web Novel $novelId")
        val summary = WebNovelTextExtractor.extractNovelSynopsis(html)

        // Parse meta tags (og:title, og:description)
        val ogDesc = Regex("(?i)<meta\\s+property=[\"']og:description[\"']\\s+content=[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)

        return NovelDetailResponse(
            novelId = novelId,
            slug = url.substringAfterLast("/"),
            title = title,
            titleOriginal = title,
            author = "Custom Author",
            status = "ongoing",
            totalChapters = 100,
            summary = ogDesc ?: summary,
            tags = listOf("Custom Novel", "Web Edition"),
            coverUrl = null,
            views = "10k",
            rating = 4.5f,
        )
    }

    override fun parseChapterContent(novelId: Long, chapterNumber: Int, html: String): ChapterContentResponse {
        val title = WebNovelTextExtractor.extractNovelTitle(html, fallback = "Chapter $chapterNumber")
        val paragraphs = WebNovelTextExtractor.extractParagraphs(html)

        return ChapterContentResponse(
            novelId = novelId,
            chapterNumber = chapterNumber,
            titleOriginal = title,
            titleTranslated = title,
            paragraphs = paragraphs,
            navigation = ChapterNavigationDto(
                prevChapter = if (chapterNumber > 1) chapterNumber - 1 else null,
                nextChapter = chapterNumber + 1,
                totalChapters = 100,
            ),
        )
    }
}
