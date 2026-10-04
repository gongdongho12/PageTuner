package com.dongholab.pagetuner.source

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * WTR-LAB URL Query Parameter Mapping Model.
 * Supports:
 * - orderBy: addition_date, name, views, readers, chapters
 * - order: asc, desc
 * - status: all, ongoing, completed
 * - query: server-side title/raw-title keyword search
 * - genreId: WTR-LAB's real 1-based genre identifier
 * - page: Int (1-indexed page)
 */
data class WtrLabCatalogQueryParams(
    val orderBy: String = "addition_date",
    val order: String = "desc",
    val status: String = "all",
    val genreId: Int? = null,
    val query: String = "",
    val page: Int = 1,
) {
    fun buildUrl(baseUrl: String = "https://wtr-lab.com/en/novel-list"): String {
        val uri = URI(baseUrl)
        val language = uri.path.orEmpty().split('/').firstOrNull(String::isNotBlank) ?: "en"
        val useFinder = query.isNotBlank() || genreId != null
        val cleanBase = "${uri.scheme}://${uri.rawAuthority}/$language/${if (useFinder) "novel-finder" else "novel-list"}"
        val params = mutableListOf<String>()
        if (orderBy != "addition_date") params.add("orderBy=${orderBy.toWtrOrderBy()}")
        if (order != "desc") params.add("order=$order")
        if (status != "all") params.add("status=$status")
        if (genreId != null) params.add("gi=$genreId")
        if (query.isNotBlank()) params.add("text=${query.urlEncode()}")
        params.add("page=$page")
        return "$cleanBase?${params.joinToString("&")}"
    }

    private fun String.toWtrOrderBy(): String = when (this) {
        "views" -> "view"
        "readers" -> "reader"
        "chapters" -> "chapter"
        else -> this
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

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

        val GENRE_OPTIONS = listOf(
            WtrLabGenre(null, "all", "All Genres"),
            WtrLabGenre(1, "action", "Action"),
            WtrLabGenre(2, "adult", "Adult"),
            WtrLabGenre(3, "adventure", "Adventure"),
            WtrLabGenre(4, "comedy", "Comedy"),
            WtrLabGenre(5, "drama", "Drama"),
            WtrLabGenre(6, "ecchi", "Ecchi"),
            WtrLabGenre(7, "erciyuan", "Erciyuan"),
            WtrLabGenre(8, "fan-fiction", "Fan Fiction"),
            WtrLabGenre(9, "fantasy", "Fantasy"),
            WtrLabGenre(10, "game", "Game"),
            WtrLabGenre(11, "gender-bender", "Gender Bender"),
            WtrLabGenre(12, "harem", "Harem"),
            WtrLabGenre(13, "historical", "Historical"),
            WtrLabGenre(14, "horror", "Horror"),
            WtrLabGenre(15, "josei", "Josei"),
            WtrLabGenre(16, "martial-arts", "Martial Arts"),
            WtrLabGenre(17, "mature", "Mature"),
            WtrLabGenre(18, "mecha", "Mecha"),
            WtrLabGenre(19, "military", "Military"),
            WtrLabGenre(20, "mystery", "Mystery"),
            WtrLabGenre(21, "psychological", "Psychological"),
            WtrLabGenre(22, "romance", "Romance"),
            WtrLabGenre(23, "school-life", "School Life"),
            WtrLabGenre(24, "sci-fi", "Sci-Fi"),
            WtrLabGenre(25, "seinen", "Seinen"),
            WtrLabGenre(26, "shoujo", "Shoujo"),
            WtrLabGenre(27, "shoujo-ai", "Shoujo Ai"),
            WtrLabGenre(28, "shounen", "Shounen"),
            WtrLabGenre(29, "shounen-ai", "Shounen Ai"),
            WtrLabGenre(30, "slice-of-life", "Slice of Life"),
            WtrLabGenre(31, "smut", "Smut"),
            WtrLabGenre(32, "sports", "Sports"),
            WtrLabGenre(33, "supernatural", "Supernatural"),
            WtrLabGenre(34, "tragedy", "Tragedy"),
            WtrLabGenre(35, "urban-life", "Urban Life"),
            WtrLabGenre(36, "wuxia", "Wuxia"),
            WtrLabGenre(37, "xianxia", "Xianxia"),
            WtrLabGenre(38, "xuanhuan", "Xuanhuan"),
            WtrLabGenre(39, "yaoi", "Yaoi"),
            WtrLabGenre(40, "yuri", "Yuri"),
        )

        fun fromUrl(url: String): WtrLabCatalogQueryParams {
            val params = URI(url).rawQuery.orEmpty()
                .split('&')
                .filter(String::isNotBlank)
                .associate { part ->
                    val key = part.substringBefore('=')
                    val value = part.substringAfter('=', missingDelimiterValue = "")
                    key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                }
            return WtrLabCatalogQueryParams(
                orderBy = when (params["orderBy"]) {
                    "view" -> "views"
                    "reader" -> "readers"
                    "chapter" -> "chapters"
                    null -> "addition_date"
                    else -> params.getValue("orderBy")
                },
                order = params["order"] ?: "desc",
                status = params["status"] ?: "all",
                genreId = (params["gi"] ?: params["genre"])?.toIntOrNull(),
                query = params["text"].orEmpty(),
                page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            )
        }
    }
}

data class WtrLabGenre(
    val id: Int?,
    val slug: String,
    val label: String,
)

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
