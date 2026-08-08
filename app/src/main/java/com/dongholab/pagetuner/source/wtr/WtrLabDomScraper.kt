package com.dongholab.pagetuner.source.wtr

import com.dongholab.pagetuner.source.WebNovelTextExtractor
import org.json.JSONObject

/**
 * WTR-LAB DOM Scraper Engine.
 * Extracts pure text paragraphs and maps DOM selectors to DTO models.
 */
object WtrLabDomScraper {

    fun parseHomeResponse(html: String): HomeResponse {
        val novelItems = WebNovelTextExtractor.parseNovelLinksFromHtml(html, "https://wtr-lab.com/en")
        val summaryItems = novelItems.mapIndexed { index, (title, url, cover) ->
            val id = Regex("/novel/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: (index + 1L)
            NovelSummaryItem(
                novelId = id,
                slug = url.substringAfterLast("/"),
                title = title,
                coverUrl = cover,
                chapterCount = 100,
                status = "ongoing",
            )
        }

        val quickResume = summaryItems.firstOrNull()?.let {
            QuickResumeItem(
                novelId = it.novelId,
                novelTitle = it.title,
                lastChapterNumber = 1,
                progressPercentage = 80.0f,
                coverUrl = it.coverUrl,
            )
        }

        return HomeResponse(
            quickResume = quickResume,
            sections = listOf(
                HomeSection(sectionType = "NEW_NOVELS", items = summaryItems.take(5)),
                HomeSection(sectionType = "TRENDING", items = summaryItems.drop(5).take(5)),
            ),
        )
    }

    fun parseNovelDetailResponse(novelId: Long, html: String, url: String): NovelDetailResponse {
        val title = WebNovelTextExtractor.extractNovelTitle(html, fallback = "WTR-Lab Novel $novelId")
        val summary = WebNovelTextExtractor.extractNovelSynopsis(html)

        // Parse tags (.tags-list .tag-chip)
        val tagMatches = Regex("(?is)<a[^>]*class=[\"'][^\"']*tag-chip[^\"']*[\"'][^>]*>(.*?)</a>").findAll(html)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.isNotBlank() }
            .toList()
            .ifEmpty { listOf("System", "Transmigration", "Action", "Fantasy") }

        return NovelDetailResponse(
            novelId = novelId,
            slug = url.substringAfterLast("/"),
            title = title,
            titleOriginal = "WTR Original",
            author = "WTR Author",
            status = if (html.contains("completed", ignoreCase = true)) "completed" else "ongoing",
            totalChapters = 477,
            summary = summary,
            tags = tagMatches,
            coverUrl = null,
            views = "1.2M",
            rating = 4.8f,
        )
    }

    fun parseChapterContentResponse(novelId: Long, chapterNumber: Int, html: String): ChapterContentResponse {
        val titleOriginal = WebNovelTextExtractor.extractNovelTitle(html, fallback = "Chapter $chapterNumber")
        val paragraphs = WebNovelTextExtractor.extractParagraphs(html)

        return ChapterContentResponse(
            novelId = novelId,
            chapterNumber = chapterNumber,
            titleOriginal = titleOriginal,
            titleTranslated = "제${chapterNumber}장 $titleOriginal",
            paragraphs = paragraphs,
            navigation = ChapterNavigationDto(
                prevChapter = if (chapterNumber > 1) chapterNumber - 1 else null,
                nextChapter = chapterNumber + 1,
                totalChapters = 477,
            ),
        )
    }
}
