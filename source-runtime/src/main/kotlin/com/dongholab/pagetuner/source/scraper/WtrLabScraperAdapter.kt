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
        val currentPage = Regex("[?&]page=(\\d+)")
            .find(baseUrl)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: 1
        return WtrLabDomScraper.parseNovelListResponse(
            html = html,
            baseUrl = baseUrl,
            currentPage = currentPage,
        )
    }

    override fun parseNovelDetail(novelId: Long, html: String, url: String): NovelDetailResponse {
        return WtrLabDomScraper.parseNovelDetailResponse(novelId, html, url)
    }

    override fun parseChapterContent(novelId: Long, chapterNumber: Int, html: String): ChapterContentResponse {
        return WtrLabDomScraper.parseChapterContentResponse(novelId, chapterNumber, html)
    }
}
