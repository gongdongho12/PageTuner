package com.dongholab.pagetuner.source.wtr

/**
 * WTR-LAB API & Scraper Data Models matching specified endpoints.
 */

data class QuickResumeItem(
    val novelId: Long,
    val novelTitle: String,
    val lastChapterNumber: Int,
    val progressPercentage: Float = 0f,
    val coverUrl: String? = null,
)

data class NovelSummaryItem(
    val novelId: Long,
    val slug: String,
    val title: String,
    val coverUrl: String? = null,
    val chapterCount: Int = 0,
    val status: String = "ongoing",
    val views: String = "0",
    val rating: Float = 0f,
)

data class HomeSection(
    val sectionType: String, // "NEW_NOVELS", "TRENDING", "RECENT_UPDATES"
    val items: List<NovelSummaryItem>,
)

data class HomeResponse(
    val quickResume: QuickResumeItem? = null,
    val sections: List<HomeSection> = emptyList(),
)

data class NovelListResponse(
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val novels: List<NovelSummaryItem> = emptyList(),
)

data class NovelDetailResponse(
    val novelId: Long,
    val slug: String,
    val title: String,
    val titleOriginal: String? = null,
    val author: String = "WTR-Lab Author",
    val status: String = "ongoing",
    val totalChapters: Int = 0,
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val coverUrl: String? = null,
    val views: String = "0",
    val rating: Float = 0f,
)

data class ChapterItemDto(
    val chapterNumber: Int,
    val title: String,
    val titleKo: String? = null,
    val releaseDate: String? = null,
    val urlPath: String,
    val readStatus: String = "UNREAD", // "UNREAD", "READING", "COMPLETED"
    val readProgress: Float = 0f,
)

data class ChapterListResponse(
    val novelId: Long,
    val totalChapters: Int = 0,
    val page: Int = 1,
    val totalPages: Int = 1,
    val chapters: List<ChapterItemDto> = emptyList(),
)

data class ChapterNavigationDto(
    val prevChapter: Int? = null,
    val nextChapter: Int? = null,
    val totalChapters: Int = 0,
)

data class ChapterContentResponse(
    val novelId: Long,
    val chapterNumber: Int,
    val titleOriginal: String,
    val titleTranslated: String? = null,
    val paragraphs: List<String> = emptyList(),
    val navigation: ChapterNavigationDto = ChapterNavigationDto(),
)
