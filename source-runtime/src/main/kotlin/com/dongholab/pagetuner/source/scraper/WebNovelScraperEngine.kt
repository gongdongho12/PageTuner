package com.dongholab.pagetuner.source.scraper

import com.dongholab.pagetuner.source.wtr.ChapterContentResponse
import com.dongholab.pagetuner.source.wtr.NovelDetailResponse
import com.dongholab.pagetuner.source.wtr.NovelListResponse

/**
 * Common Standard Interface for all Web Novel Scraper Adapters.
 * Allows custom web novel sites to be dynamically added and parsed.
 */
interface WebNovelScraperEngine {
    val name: String

    /**
     * Determines whether this scraper adapter can handle the given URL.
     */
    fun canHandle(url: String): Boolean

    /**
     * Parses novel catalog listing.
     */
    fun parseCatalog(html: String, baseUrl: String): NovelListResponse

    /**
     * Parses novel detail overview (cover, title, author, synopsis, tags).
     */
    fun parseNovelDetail(novelId: Long, html: String, url: String): NovelDetailResponse

    /**
     * Parses chapter content into pure text paragraph list (paragraphs: List<String>).
     */
    fun parseChapterContent(novelId: Long, chapterNumber: Int, html: String): ChapterContentResponse
}
