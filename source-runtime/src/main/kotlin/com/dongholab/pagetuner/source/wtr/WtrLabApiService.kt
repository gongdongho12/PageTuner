package com.dongholab.pagetuner.source.wtr

/**
 * WTR-LAB Clean API Service Interface.
 */
interface WtrLabApiService {

    // 1. Home Screen Data (Quick Resume + New/Trending)
    suspend fun getHomeData(): HomeResponse

    // 2. Novel List, Search, and Filtering
    suspend fun getNovels(
        orderBy: String? = "addition_date",
        order: String? = "desc",
        status: String? = "all",
        genreId: Int? = null,
        page: Int = 1,
        search: String? = null,
    ): NovelListResponse

    // 3. Novel Detail Info
    suspend fun getNovelDetail(
        novelId: Long,
    ): NovelDetailResponse

    // 4. Chapter Table of Contents
    suspend fun getChapterList(
        novelId: Long,
        page: Int = 1,
    ): ChapterListResponse

    // 5. Chapter Content & Pure Text Paragraph List
    suspend fun getChapterContent(
        novelId: Long,
        chapterNumber: Int,
        lang: String = "ko",
        translator: String = "google",
    ): ChapterContentResponse
}
