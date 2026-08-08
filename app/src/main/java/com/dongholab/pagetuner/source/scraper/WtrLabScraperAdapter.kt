package com.dongholab.pagetuner.source.scraper

import com.dongholab.pagetuner.source.wtr.ChapterContentResponse
import com.dongholab.pagetuner.source.wtr.NovelDetailResponse
import com.dongholab.pagetuner.source.wtr.NovelListResponse
import com.dongholab.pagetuner.source.wtr.WtrLabDomScraper

/**
 * Dedicated Scraper Adapter for WTR-LAB (wtr-lab.com).
 * Employs Next.js __NEXT_DATA__ React Hydration state & specialized DOM selectors.
 */
class WtrLabScraperAdapter : WebNovelScraperEngine {
    override val name: String = "WTR-LAB Adapter"

    override fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("wtr-lab.com") || lower.contains("wtr-lab")
    }

    override fun parseCatalog(html: String, baseUrl: String): NovelListResponse {
        val home = WtrLabDomScraper.parseHomeResponse(html)
        val allItems = home.sections.flatMap { it.items }.distinctBy { it.novelId }
        return NovelListResponse(
            currentPage = 1,
            hasNextPage = allItems.size >= 10,
            novels = allItems,
        )
    }

    override fun parseNovelDetail(novelId: Long, html: String, url: String): NovelDetailResponse {
        return WtrLabDomScraper.parseNovelDetailResponse(novelId, html, url)
    }

    override fun parseChapterContent(novelId: Long, chapterNumber: Int, html: String): ChapterContentResponse {
        return WtrLabDomScraper.parseChapterContentResponse(novelId, chapterNumber, html)
    }
}
