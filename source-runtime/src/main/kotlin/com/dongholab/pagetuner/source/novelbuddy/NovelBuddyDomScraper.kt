package com.dongholab.pagetuner.source.novelbuddy

import com.dongholab.pagetuner.source.WebNovelTextExtractor
import com.dongholab.pagetuner.source.webnovel.NextJsPageData
import java.io.IOException
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object NovelBuddyDomScraper {
    fun parseCatalogResponse(html: String, baseUrl: String): NovelBuddyCatalogResponse {
        val pageProps = NextJsPageData.pageProps(html)
            ?: throw IOException("NovelBuddy page did not contain server-rendered catalog data.")
        val items = pageProps.optJSONArray("ssrItems").toBookSummaries(baseUrl)
        val pagination = pageProps.optJSONObject("ssrPagination")
        val currentPage = pagination?.positiveInt("page") ?: currentPage(baseUrl)
        val totalPages = pagination?.positiveInt("total_pages")
        val totalItems = pagination?.nonNegativeInt("total")
        return NovelBuddyCatalogResponse(
            items = items,
            currentPage = currentPage,
            totalPages = totalPages,
            totalItems = totalItems,
            hasPreviousPage = pagination?.optBoolean("has_previous", currentPage > 1) ?: (currentPage > 1),
            hasNextPage = pagination?.optBoolean("has_next", totalPages?.let { currentPage < it } ?: false)
                ?: false,
        )
    }

    fun parseDetail(html: String, baseUrl: String): NovelBuddyBookSummary {
        val manga = NextJsPageData.pageProps(html)?.optJSONObject("initialManga")
            ?: throw IOException("NovelBuddy page did not contain server-rendered book details.")
        return manga.toBookSummary(baseUrl)
    }

    fun parseEmbeddedChapters(html: String, baseUrl: String): List<NovelBuddyChapterSummary> {
        val manga = NextJsPageData.pageProps(html)?.optJSONObject("initialManga") ?: return emptyList()
        return manga.optJSONArray("chapters").toChapters(baseUrl)
    }

    fun chapterIndexUrl(html: String, pageUrl: String): String? {
        val pageProps = NextJsPageData.pageProps(html) ?: return null
        val manga = pageProps.optJSONObject("initialManga") ?: return null
        val id = manga.optString("id").takeIf(String::isNotBlank) ?: return null
        val version = manga.optLong("cv", 0L).takeIf { it > 0L }
        val apiBase = pageProps.optJSONObject("siteConfig")
            ?.optString("apiUrl")
            ?.takeIf(String::isNotBlank)
            ?: runCatching {
                val uri = URI(pageUrl)
                "${uri.scheme}://api.${uri.host.orEmpty().removePrefix("www.")}"
            }.getOrDefault("https://api.novelbuddy.me")
        return buildString {
            append(apiBase.trimEnd('/')).append("/titles/").append(id).append("/chapters")
            version?.let { append("?cv=").append(it) }
        }
    }

    fun parseChapterIndex(rawJson: String, baseUrl: String): List<NovelBuddyChapterSummary> {
        val root = runCatching { JSONObject(rawJson) }
            .getOrElse { throw IOException("NovelBuddy chapter index was not valid JSON.", it) }
        val chapters = root.optJSONObject("data")?.optJSONArray("chapters")
            ?: root.optJSONArray("chapters")
            ?: throw IOException("NovelBuddy chapter index did not contain chapters.")
        return chapters.toChapters(baseUrl)
    }

    fun firstChapterUrl(html: String, baseUrl: String): String? {
        val manga = NextJsPageData.pageProps(html)?.optJSONObject("initialManga") ?: return null
        val first = manga.optJSONObject("firstChapter")
            ?: manga.optJSONArray("chapters")?.let { chapters ->
                (0 until chapters.length())
                    .mapNotNull(chapters::optJSONObject)
                    .minByOrNull { it.optInt("number", Int.MAX_VALUE) }
            }
        return first?.optString("url")
            ?.takeIf(String::isNotBlank)
            ?.let { resolveUrl(baseUrl, it) }
    }

    fun parseChapterContent(html: String): NovelBuddyChapterContent {
        val chapter = NextJsPageData.pageProps(html)?.optJSONObject("initialChapter")
            ?: throw IOException("NovelBuddy page did not contain server-rendered chapter data.")
        val text = WebNovelTextExtractor.extractNovelText(html)
        val paragraphs = text
            .split(Regex("\\n{2,}"))
            .map(String::trim)
            .filter(String::isNotBlank)
        if (paragraphs.isEmpty()) throw IOException("NovelBuddy chapter body was empty.")
        return NovelBuddyChapterContent(
            number = chapter.optInt("number", chapterNumber(chapter.optString("url")) ?: 1),
            title = chapter.optString("name").ifBlank { "Chapter ${chapter.optInt("number", 1)}" },
            paragraphs = paragraphs,
        )
    }

    private fun JSONArray?.toBookSummaries(baseUrl: String): List<NovelBuddyBookSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { add(it.toBookSummary(baseUrl)) }
            }
        }.distinctBy { it.url }
    }

    private fun JSONObject.toBookSummary(baseUrl: String): NovelBuddyBookSummary {
        val rawUrl = optString("url").ifBlank { "/${optString("slug")}" }
        val genres = optJSONArray("genres").names()
        val tags = (optJSONArray("tags").names() + genres + buildList {
            if (optBoolean("isMtl")) add("MTL")
            if (optBoolean("isAdult")) add("18+")
        }).distinct()
        val stats = optJSONObject("stats")
        val chapterCount = stats?.positiveInt("chaptersCount")
            ?: stats?.positiveInt("chapters_count")
            ?: Regex("[\\d,]+").find(optString("displayChapters"))
                ?.value?.replace(",", "")?.toIntOrNull()
            ?: 0
        return NovelBuddyBookSummary(
            id = optString("id").ifBlank { optString("slug").ifBlank { rawUrl } },
            name = optString("name").ifBlank { optString("title").ifBlank { "Untitled" } },
            url = resolveUrl(baseUrl, rawUrl),
            authors = optJSONArray("authors").names(),
            coverUrl = optString("cover").takeIf(String::isNotBlank)?.let { resolveUrl(baseUrl, it) },
            summary = optString("summary").takeIf(String::isNotBlank),
            status = normalizeStatus(optString("status")),
            chapterCount = chapterCount,
            genres = genres,
            tags = tags,
            views = optString("displayViews").takeIf(String::isNotBlank)
                ?: stats?.optLong("views", 0L)?.toString().orEmpty(),
            rating = optDouble("rating", 0.0).toFloat(),
            contentVersion = optLong("cv", 0L).takeIf { it > 0L },
        )
    }

    private fun JSONArray?.toChapters(baseUrl: String): List<NovelBuddyChapterSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val chapter = optJSONObject(index) ?: continue
                val rawUrl = chapter.optString("url")
                val number = chapter.optInt("number", 0).takeIf { it > 0 }
                    ?: chapterNumber(rawUrl)
                    ?: chapterNumber(chapter.optString("name"))
                    ?: continue
                add(
                    NovelBuddyChapterSummary(
                        id = chapter.optString("id").ifBlank { "chapter-$number" },
                        number = number,
                        name = chapter.optString("name").ifBlank { "Chapter $number" },
                        url = resolveUrl(baseUrl, rawUrl),
                    ),
                )
            }
        }.distinctBy { it.url }.sortedBy { it.number }
    }

    private fun JSONArray?.names(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                when (val value = opt(index)) {
                    is JSONObject -> value.optString("name").takeIf(String::isNotBlank)?.let(::add)
                    is String -> value.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }

    private fun JSONObject.positiveInt(key: String): Int? = optInt(key, 0).takeIf { it > 0 }

    private fun JSONObject.nonNegativeInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key, 0).coerceAtLeast(0) else null

    private fun currentPage(url: String): Int =
        Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun chapterNumber(value: String): Int? =
        Regex("(?:chapter|ch)[- _.:/]?(\\d+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.toIntOrNull()

    private fun normalizeStatus(value: String): String = when {
        value.contains("complete", ignoreCase = true) -> "completed"
        value.contains("hiatus", ignoreCase = true) -> "hiatus"
        value.contains("cancel", ignoreCase = true) -> "cancelled"
        else -> "ongoing"
    }

    private fun resolveUrl(baseUrl: String, value: String): String =
        runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
}
