package com.dongholab.pagetuner.source

/**
 * WTR-LAB URL Query Parameter Mapping Model.
 * Supports:
 * - orderBy: addition_date, name, views, readers, chapters
 * - order: asc, desc
 * - status: all, ongoing, completed
 * - genreId: Int? (Genre ID Enum mapping)
 * - page: Int (1-indexed page)
 */
data class WtrLabCatalogQueryParams(
    val orderBy: String = "addition_date",
    val order: String = "desc",
    val status: String = "all",
    val genreId: Int? = null,
    val page: Int = 1,
) {
    fun buildUrl(baseUrl: String = "https://wtr-lab.com/en/novel-list"): String {
        val cleanBase = baseUrl.substringBefore("?")
        val params = mutableListOf<String>()
        params.add("orderBy=$orderBy")
        params.add("order=$order")
        params.add("status=$status")
        if (genreId != null) {
            params.add("genre=$genreId")
        }
        params.add("page=$page")
        return "$cleanBase?${params.joinToString("&")}"
    }

    companion object {
        val ORDER_BY_OPTIONS = listOf(
            "addition_date" to "Addition Date 📅",
            "name" to "Title Name 🔤",
            "views" to "Views 🔥",
            "readers" to "Readers 👥",
            "chapters" to "Chapters 📚",
        )

        val STATUS_OPTIONS = listOf(
            "all" to "All Status",
            "ongoing" to "Ongoing ⚡",
            "completed" to "Completed ✅",
        )

        val GENRE_MAP = listOf(
            null to "All Genres",
            1 to "Action ⚔️",
            2 to "Fantasy 🪄",
            3 to "Romance 💖",
            4 to "System 💻",
            5 to "Sci-Fi 🚀",
            6 to "Xianxia ☯️",
        )
    }
}

enum class ChapterReadStatus {
    UNREAD,
    READING,
    COMPLETED,
}

data class WtrLabChapterMeta(
    val chapterNumber: Int,
    val titleOriginal: String,
    val titleTranslated: String? = null,
    val releaseDate: String? = null,
    val readStatus: ChapterReadStatus = ChapterReadStatus.UNREAD,
    val readProgress: Float = 0f,
)
