package com.dongholab.pagetuner.source.scraper

import com.dongholab.pagetuner.source.WebNovelTextExtractor
import com.dongholab.pagetuner.source.wtr.ChapterContentResponse
import com.dongholab.pagetuner.source.wtr.ChapterNavigationDto
import com.dongholab.pagetuner.source.wtr.NovelDetailResponse
import com.dongholab.pagetuner.source.wtr.NovelListResponse
import com.dongholab.pagetuner.source.wtr.NovelSummaryItem

/**
 * Dynamic Rule Scraper Adapter executing user-defined CustomWebSourceRules.
 */
class CustomRuleScraperAdapter(
    val rule: CustomWebSourceRule,
) : WebNovelScraperEngine {

    override val name: String = "Custom Adapter: ${rule.name}"

    override fun canHandle(url: String): Boolean {
        val cleanDomain = rule.domainUrl.lowercase().replace("http://", "").replace("https://", "").substringBefore("/")
        val cleanUrl = url.lowercase().replace("http://", "").replace("https://", "")
        return cleanDomain.isNotBlank() && cleanUrl.contains(cleanDomain)
    }

    override fun parseCatalog(html: String, baseUrl: String): NovelListResponse {
        val rawLinks = WebNovelTextExtractor.parseNovelLinksFromHtml(html, baseUrl)
        val items = rawLinks.mapIndexed { index, (title, url, cover) ->
            NovelSummaryItem(
                novelId = index + 1L,
                slug = url.substringAfterLast("/"),
                title = title,
                coverUrl = cover,
                chapterCount = 100,
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
        val title = WebNovelTextExtractor.extractNovelTitle(html, fallback = rule.name)
        val summary = WebNovelTextExtractor.extractNovelSynopsis(html)

        return NovelDetailResponse(
            novelId = novelId,
            slug = url.substringAfterLast("/"),
            title = title,
            titleOriginal = title,
            author = rule.name,
            status = "ongoing",
            totalChapters = 100,
            summary = summary,
            tags = listOf(rule.name, "Custom Rule Edition"),
            coverUrl = null,
            views = "Custom",
            rating = 5.0f,
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
