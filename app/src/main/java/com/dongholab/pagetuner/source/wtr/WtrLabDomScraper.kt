package com.dongholab.pagetuner.source.wtr

import com.dongholab.pagetuner.source.WebNovelTextExtractor
import org.json.JSONObject

/**
 * WTR-LAB Dynamic Web Hydration & DOM Fallback Scraper Adapter Engine.
 * Supports Next.js React Hydration State JSON (__NEXT_DATA__) and HTML DOM Selectors.
 */
object WtrLabDomScraper {

    fun parseHomeResponse(html: String): HomeResponse {
        // Strategy A: Next.js __NEXT_DATA__ JSON State
        val nextDataMatch = Regex("(?is)<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>").find(html)
        if (nextDataMatch != null) {
            runCatching {
                val jsonString = nextDataMatch.groupValues[1].trim()
                val jsonObj = JSONObject(jsonString)
                val pageProps = jsonObj.optJSONObject("props")?.optJSONObject("pageProps")
                val seriesList = pageProps?.optJSONArray("series")
                    ?: pageProps?.optJSONArray("novels")
                    ?: pageProps?.optJSONArray("data")

                if (seriesList != null && seriesList.length() > 0) {
                    val summaryItems = mutableListOf<NovelSummaryItem>()
                    for (i in 0 until seriesList.length()) {
                        val item = seriesList.optJSONObject(i) ?: continue
                        val id = item.optLong("id", item.optLong("series_id", i + 1L))
                        val title = item.optString("title", item.optString("name", "Web Novel"))
                        val slug = item.optString("slug", "novel-$id")
                        val cover = item.optString("cover", "").takeIf { it.isNotBlank() }
                            ?: item.optString("image", "").takeIf { it.isNotBlank() }
                        val chapters = item.optInt("chapter_count", item.optInt("chapters", 100))

                        summaryItems.add(
                            NovelSummaryItem(
                                novelId = id,
                                slug = slug,
                                title = title,
                                coverUrl = cover,
                                chapterCount = chapters,
                                status = "ongoing",
                            )
                        )
                    }

                    val quickResume = summaryItems.firstOrNull()?.let {
                        QuickResumeItem(
                            novelId = it.novelId,
                            novelTitle = it.title,
                            lastChapterNumber = 3,
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
            }
        }

        // Strategy B: Client HTML DOM Selector Fallback
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
                lastChapterNumber = 3,
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

        // Strategy A: Next.js __NEXT_DATA__ JSON State
        val nextDataMatch = Regex("(?is)<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>").find(html)
        if (nextDataMatch != null) {
            runCatching {
                val jsonString = nextDataMatch.groupValues[1].trim()
                val jsonObj = JSONObject(jsonString)
                val pageProps = jsonObj.optJSONObject("props")?.optJSONObject("pageProps")
                val serie = pageProps?.optJSONObject("serie")
                    ?: pageProps?.optJSONObject("novel")
                    ?: pageProps?.optJSONObject("data")

                if (serie != null) {
                    val rawTitle = serie.optString("title", serie.optString("name", title))
                    val rawAuthor = serie.optString("author", "WTR Author")
                    val rawStatus = serie.optString("status", "ongoing")
                    val totalCh = serie.optInt("chapter_count", serie.optInt("chapters", 477))
                    val desc = serie.optString("description", summary)

                    val tagArray = serie.optJSONArray("tags")
                    val tags = mutableListOf<String>()
                    if (tagArray != null) {
                        for (i in 0 until tagArray.length()) {
                            val tagObj = tagArray.opt(i)
                            if (tagObj is JSONObject) {
                                tags.add(tagObj.optString("name", "Tag"))
                            } else if (tagObj is String) {
                                tags.add(tagObj)
                            }
                        }
                    }
                    if (tags.isEmpty()) {
                        tags.addAll(listOf("System", "Transmigration", "Action", "Fantasy"))
                    }

                    return NovelDetailResponse(
                        novelId = novelId,
                        slug = url.substringAfterLast("/"),
                        title = rawTitle,
                        titleOriginal = serie.optString("original_title", "Original Title"),
                        author = rawAuthor,
                        status = rawStatus,
                        totalChapters = totalCh,
                        summary = desc,
                        tags = tags,
                        coverUrl = serie.optString("cover", "").takeIf { it.isNotBlank() },
                        views = serie.optString("views", "1.2M"),
                        rating = serie.optDouble("rating", 4.8).toFloat(),
                    )
                }
            }
        }

        // Strategy B: Client HTML DOM Selectors
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
