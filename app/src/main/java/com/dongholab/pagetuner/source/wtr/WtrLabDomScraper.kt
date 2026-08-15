package com.dongholab.pagetuner.source.wtr

import com.dongholab.pagetuner.source.WebNovelTextExtractor
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import com.dongholab.pagetuner.source.webnovel.NextJsPageData
import com.dongholab.pagetuner.source.webnovel.WebNovelAuthenticationRequiredException

/** Parses WTR-LAB's server-rendered Next.js state into stable app data models. */
object WtrLabDomScraper {
    private const val DefaultBaseUrl = "https://wtr-lab.com/en"

    fun parseHomeResponse(html: String, baseUrl: String = DefaultBaseUrl): HomeResponse {
        val pageProps = extractPageProps(html)
        val sections = buildList {
            addSection(pageProps, "series", "NEW_NOVELS", baseUrl)
            addSection(pageProps, "daily", "TRENDING", baseUrl)
            addSection(pageProps, "recently", "RECENT_UPDATES", baseUrl)
        }.filter { it.items.isNotEmpty() }

        if (sections.isNotEmpty()) {
            return HomeResponse(
                quickResume = null,
                sections = sections,
            )
        }

        val fallbackItems = WebNovelTextExtractor.parseNovelLinksFromHtml(html, baseUrl)
            .mapNotNull { (title, url, cover) ->
                val id = rawNovelIdFromUrl(url) ?: return@mapNotNull null
                NovelSummaryItem(
                    novelId = id,
                    slug = novelSlugFromUrl(url),
                    title = title,
                    coverUrl = cover,
                )
            }
            .distinctBy { it.novelId }

        return HomeResponse(
            sections = listOf(HomeSection(sectionType = "NEW_NOVELS", items = fallbackItems)),
        )
    }

    fun parseNovelListResponse(
        html: String,
        baseUrl: String,
        currentPage: Int = 1,
    ): NovelListResponse {
        val home = parseHomeResponse(html, baseUrl)
        val items = home.sections.flatMap { it.items }.distinctBy { it.novelId }
        // WTR-LAB's finder keeps the unfiltered catalog count in pageProps. Its pagination
        // links are filtered correctly, so do not surface that stale count as search results.
        val totalItems = extractPageProps(html)?.optString("count")?.toIntOrNull()
            ?.takeUnless { URI(baseUrl).path.orEmpty().endsWith("/novel-finder") }
        val pageSize = items.size.coerceAtLeast(1)
        val explicitLastPage = Regex("(?:[?&]|&amp;)page=(\\d+)", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
        val totalPages = explicitLastPage ?: totalItems?.let { count ->
            ((count + pageSize - 1) / pageSize).coerceAtLeast(1)
        }
        val hasExplicitNextPage = Regex(
            "(?is)<a[^>]+href=[\"'][^\"']*(?:[?&]|&amp;)page=${currentPage + 1}(?:[&#\"'])",
        ).containsMatchIn(html)
        return NovelListResponse(
            currentPage = currentPage,
            hasNextPage = hasExplicitNextPage || (totalPages != null && currentPage < totalPages),
            totalPages = totalPages,
            totalItems = totalItems,
            novels = items,
        )
    }

    fun parseNovelDetailResponse(novelId: Long, html: String, url: String): NovelDetailResponse {
        val pageProps = extractPageProps(html)
        val serie = pageProps?.optJSONObject("serie")
        val serieData = serie?.optJSONObject("serie_data")
            ?: serie?.optJSONObject("data")
            ?: pageProps?.optJSONObject("novel")
        val data = serieData?.optJSONObject("data")

        if (serieData != null) {
            val resolvedId = positiveLong(serieData, "raw_id")
                ?: rawNovelIdFromUrl(url)
                ?: novelId
            val title = firstNonBlank(
                data?.optString("title"),
                serieData.optString("title"),
                WebNovelTextExtractor.extractNovelTitle(html, "WTR-LAB Novel $resolvedId"),
            )
            val raw = data?.optJSONObject("raw")
            val tagNames = resolveTagNames(pageProps, serieData.optJSONArray("tags"))

            return NovelDetailResponse(
                novelId = resolvedId,
                slug = firstNonBlank(serieData.optString("slug"), novelSlugFromUrl(url)),
                title = title,
                titleOriginal = firstNonBlank(raw?.optString("title"), serieData.optString("original_title")).takeIf { it.isNotBlank() },
                author = firstNonBlank(data?.optString("author"), serieData.optString("author"), raw?.optString("author"), "WTR-LAB Author"),
                status = statusName(serieData.opt("status")),
                totalChapters = positiveInt(serieData, "chapter_count")
                    ?: positiveInt(serieData, "raw_chapter_count")
                    ?: 0,
                summary = firstNonBlank(data?.optString("description"), serieData.optString("description")),
                tags = tagNames,
                coverUrl = firstNonBlank(data?.optString("image"), serieData.optString("cover")).takeIf { it.isNotBlank() },
                views = totalViews(serieData).toString(),
                rating = serieData.optDouble("rating", 0.0).takeUnless { it.isNaN() }?.toFloat() ?: 0f,
            )
        }

        return NovelDetailResponse(
            novelId = novelId,
            slug = novelSlugFromUrl(url),
            title = WebNovelTextExtractor.extractNovelTitle(html, "WTR-LAB Novel $novelId"),
            summary = WebNovelTextExtractor.extractNovelSynopsis(html),
        )
    }

    fun parseChapterListResponse(novelId: Long, html: String, url: String): ChapterListResponse {
        val pageProps = extractPageProps(html)
        val serie = pageProps?.optJSONObject("serie")
        val serieData = serie?.optJSONObject("serie_data")
        val totalChapters = positiveInt(serieData, "chapter_count")
            ?: positiveInt(serieData, "raw_chapter_count")
            ?: 0
        val resolvedNovelId = positiveLong(serieData, "raw_id")
            ?: rawNovelIdFromUrl(url)
            ?: novelId
        val slug = firstNonBlank(serieData?.optString("slug"), novelSlugFromUrl(url))
        val languagePrefix = languagePrefix(url)
        val knownChapters = knownChaptersByNumber(serie)

        val chapters = if (totalChapters > 0 && slug.isNotBlank()) {
            (1..totalChapters).map { number ->
                val known = knownChapters[number]
                ChapterItemDto(
                    chapterNumber = number,
                    title = known?.optString("title")?.takeIf { it.isNotBlank() } ?: "Chapter $number",
                    releaseDate = known?.optString("updated_at")?.substringBefore(' ')?.takeIf { it.isNotBlank() },
                    urlPath = "$languagePrefix/novel/$resolvedNovelId/$slug/chapter-$number",
                )
            }
        } else {
            WebNovelTextExtractor.parseNovelLinksFromHtml(html, url)
                .mapNotNull { (title, chapterUrl, _) ->
                    val number = chapterNumberFromUrl(chapterUrl) ?: return@mapNotNull null
                    ChapterItemDto(
                        chapterNumber = number,
                        title = title,
                        urlPath = URI(url).relativize(URI(chapterUrl)).toString().takeIf { it.isNotBlank() } ?: chapterUrl,
                    )
                }
                .distinctBy { it.chapterNumber }
                .sortedBy { it.chapterNumber }
        }

        return ChapterListResponse(
            novelId = resolvedNovelId,
            totalChapters = totalChapters.takeIf { it > 0 } ?: chapters.size,
            page = 1,
            totalPages = 1,
            chapters = chapters,
        )
    }

    fun parseChapterContentResponse(novelId: Long, chapterNumber: Int, html: String): ChapterContentResponse {
        val pageProps = extractPageProps(html)
        val serie = pageProps?.optJSONObject("serie")
        val chapter = serie?.optJSONObject("chapter")
            ?: pageProps?.optJSONObject("chapter")
        val totalChapters = positiveInt(serie?.optJSONObject("serie_data"), "chapter_count") ?: 0
        val title = firstNonBlank(
            chapter?.optString("title"),
            WebNovelTextExtractor.extractNovelTitle(html, "Chapter $chapterNumber"),
        )

        return ChapterContentResponse(
            novelId = novelId,
            chapterNumber = chapterNumber,
            titleOriginal = title,
            paragraphs = WebNovelTextExtractor.extractParagraphs(html),
            navigation = ChapterNavigationDto(
                prevChapter = if (chapterNumber > 1) chapterNumber - 1 else null,
                nextChapter = if (totalChapters == 0 || chapterNumber < totalChapters) chapterNumber + 1 else null,
                totalChapters = totalChapters,
            ),
        )
    }

    /** Parses the JSON returned by WTR-LAB's lightweight reader request. */
    fun parseReaderChapterResponse(
        novelId: Long,
        chapterNumber: Int,
        rawJson: String,
        language: String = "en",
    ): ChapterContentResponse {
        val root = JSONObject(rawJson)
        if (!root.optBoolean("success")) {
            if (root.optString("code") == "1401") {
                throw WebNovelAuthenticationRequiredException("WTR-LAB")
            }
            val reason = when {
                root.optBoolean("requireTurnstile") -> "browser verification is required"
                root.optString("code").isNotBlank() -> root.optString("code")
                root.optString("error").isNotBlank() -> root.optString("error")
                root.optString("message").isNotBlank() -> root.optString("message")
                else -> "unknown reader response"
            }
            throw IllegalArgumentException("WTR-LAB reader request failed: $reason")
        }

        val chapter = root.optJSONObject("chapter")
        val responseData = root.optJSONObject("data")
        val contentData = responseData?.optJSONObject("data") ?: responseData
        val glossary = readerGlossary(contentData)
        val patches = readerPatches(contentData, language)
        val paragraphs = readerBody(contentData?.opt("body"))
            .map { applyReaderTerms(it, glossary, patches) }
            .map(String::trim)
            .filter(String::isNotBlank)

        val resolvedNovelId = chapter?.optLong("raw_id", novelId)
            ?.takeIf { it > 0L }
            ?: novelId
        val resolvedChapterNumber = chapter?.optInt("order", chapterNumber)
            ?.takeIf { it > 0 }
            ?: chapterNumber
        val title = firstNonBlank(
            contentData?.optString("title"),
            chapter?.optString("title"),
            chapter?.optString("name"),
            "Chapter $resolvedChapterNumber",
        )

        return ChapterContentResponse(
            novelId = resolvedNovelId,
            chapterNumber = resolvedChapterNumber,
            titleOriginal = title,
            titleTranslated = contentData?.optString("title")?.takeIf(String::isNotBlank),
            paragraphs = paragraphs,
        )
    }

    private fun readerBody(rawBody: Any?): List<String> = when (rawBody) {
        is JSONArray -> buildList {
            for (index in 0 until rawBody.length()) {
                readerLine(rawBody.opt(index))?.let(::add)
            }
        }
        is String -> rawBody.split(Regex("\\n\\s*\\n+"))
        else -> emptyList()
    }

    private fun readerLine(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> firstNonBlank(
            value.optString("text"),
            value.optString("content"),
            value.optString("value"),
        ).takeIf(String::isNotBlank)
        else -> null
    }

    private fun readerGlossary(contentData: JSONObject?): Map<Int, String> {
        val terms = contentData
            ?.optJSONObject("glossary_data")
            ?.optJSONArray("terms")
            ?: return emptyMap()
        return buildMap {
            for (index in 0 until terms.length()) {
                val term = terms.optJSONArray(index)?.optString(0).orEmpty().trim()
                if (term.isNotBlank()) put(index, term)
            }
        }
    }

    private fun readerPatches(contentData: JSONObject?, language: String): List<Pair<String, String>> {
        val rawPatches = contentData?.optJSONArray("patch") ?: return emptyList()
        val targetKey = language.substringBefore('-').lowercase()
        return buildList {
            for (index in 0 until rawPatches.length()) {
                val patch = rawPatches.optJSONObject(index) ?: continue
                val replacement = firstNonBlank(
                    patch.optString(targetKey),
                    patch.optString(language),
                    patch.optString("en"),
                )
                if (replacement.isBlank()) continue
                val keys = patch.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val source = patch.optString(key)
                    if (key != targetKey && source.isNotBlank() && source != replacement) {
                        add(source to replacement)
                    }
                }
            }
        }
    }

    private fun applyReaderTerms(
        value: String,
        glossary: Map<Int, String>,
        patches: List<Pair<String, String>>,
    ): String {
        var result = ReaderTermMarker.replace(value) { match ->
            glossary[match.groupValues[1].toIntOrNull()] ?: match.value
        }
        patches.forEach { (source, replacement) ->
            result = result.replace(source, replacement)
        }
        return result
    }

    private fun MutableList<HomeSection>.addSection(
        pageProps: JSONObject?,
        jsonKey: String,
        sectionType: String,
        baseUrl: String,
    ) {
        val array = pageProps?.optJSONArray(jsonKey) ?: return
        val items = buildList {
            for (index in 0 until array.length()) {
                parseSummaryItem(array.optJSONObject(index), baseUrl)?.let(::add)
            }
        }.distinctBy { it.novelId }
        if (items.isNotEmpty()) add(HomeSection(sectionType = sectionType, items = items))
    }

    private fun parseSummaryItem(rawItem: JSONObject?, baseUrl: String): NovelSummaryItem? {
        val item = unwrapSeries(rawItem) ?: return null
        val data = item.optJSONObject("data")
        val rawId = positiveLong(item, "raw_id") ?: positiveLong(item, "id") ?: return null
        val title = firstNonBlank(data?.optString("title"), item.optString("title"), item.optString("name"))
        if (title.isBlank()) return null
        val slug = firstNonBlank(item.optString("slug"), slugify(title))
        val cover = firstNonBlank(data?.optString("image"), item.optString("cover"), item.optString("image"))
            .takeIf { it.isNotBlank() }
            ?.let { resolveUrl(baseUrl, it) }

        return NovelSummaryItem(
            novelId = rawId,
            slug = slug,
            title = title,
            coverUrl = cover,
            chapterCount = positiveInt(item, "chapter_count") ?: positiveInt(item, "raw_chapter_count") ?: 0,
            status = statusName(item.opt("status")),
            views = totalViews(item).toString(),
            rating = item.optDouble("rating", 0.0).takeUnless { it.isNaN() }?.toFloat() ?: 0f,
            author = firstNonBlank(data?.optString("author"), item.optString("author"))
                .takeIf { it.isNotBlank() },
            description = firstNonBlank(data?.optString("description"), item.optString("description"))
                .takeIf { it.isNotBlank() },
        )
    }

    private fun unwrapSeries(item: JSONObject?): JSONObject? {
        if (item == null) return null
        return item.optJSONObject("serie_data")
            ?: item.optJSONObject("serie")?.optJSONObject("serie_data")
            ?: item.optJSONObject("series")
            ?: item
    }

    private fun knownChaptersByNumber(serie: JSONObject?): Map<Int, JSONObject> {
        val result = mutableMapOf<Int, JSONObject>()
        val arrays = listOf("last_chapters", "chapters")
        arrays.forEach { key ->
            val values = serie?.optJSONArray(key) ?: return@forEach
            for (index in 0 until values.length()) {
                val chapter = values.optJSONObject(index) ?: continue
                val number = positiveInt(chapter, "order") ?: continue
                result[number] = chapter
            }
        }
        serie?.optJSONObject("chapter")?.let { chapter ->
            positiveInt(chapter, "order")?.let { result[it] = chapter }
        }
        return result
    }

    private fun resolveTagNames(pageProps: JSONObject?, tagIds: JSONArray?): List<String> {
        if (tagIds == null) return emptyList()
        val lookup = mutableMapOf<Int, String>()
        val availableTags = pageProps?.optJSONArray("tags")
        if (availableTags != null) {
            for (index in 0 until availableTags.length()) {
                val tag = availableTags.optJSONObject(index) ?: continue
                val id = tag.optInt("id", -1)
                val name = firstNonBlank(tag.optString("name"), tag.optString("title"))
                if (id >= 0 && name.isNotBlank()) lookup[id] = name
            }
        }
        return buildList {
            for (index in 0 until tagIds.length()) {
                when (val tag = tagIds.opt(index)) {
                    is JSONObject -> firstNonBlank(tag.optString("name"), tag.optString("title")).takeIf { it.isNotBlank() }?.let(::add)
                    is Number -> lookup[tag.toInt()]?.let(::add)
                    is String -> tag.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.distinct()
    }

    private fun extractPageProps(html: String): JSONObject? {
        return NextJsPageData.pageProps(html)
    }

    private fun positiveLong(json: JSONObject?, key: String): Long? =
        json?.optLong(key, 0L)?.takeIf { it > 0L }

    private fun positiveInt(json: JSONObject?, key: String): Int? =
        json?.optInt(key, 0)?.takeIf { it > 0 }

    private fun totalViews(item: JSONObject): Long =
        item.optLong("view", 0L).coerceAtLeast(0L) + item.optLong("view_temp", 0L).coerceAtLeast(0L)

    private fun statusName(rawStatus: Any?): String = when (rawStatus) {
        is Number -> if (rawStatus.toInt() == 1) "completed" else "ongoing"
        is String -> when (rawStatus.lowercase()) {
            "1", "completed", "complete", "finished" -> "completed"
            else -> "ongoing"
        }
        else -> "ongoing"
    }

    private fun rawNovelIdFromUrl(url: String): Long? =
        Regex("/novel/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    private fun chapterNumberFromUrl(url: String): Int? =
        Regex("/chapter-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun novelSlugFromUrl(url: String): String =
        Regex("/novel/\\d+/([^/?#]+)").find(url)?.groupValues?.get(1).orEmpty()

    private fun languagePrefix(url: String): String =
        Regex("https?://[^/]+(/[^/]+)").find(url)?.groupValues?.get(1)?.takeIf { it.length <= 4 } ?: "/en"

    private fun resolveUrl(baseUrl: String, value: String): String =
        runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)

    private fun slugify(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private val ReaderTermMarker = Regex("※(\\d+)⛬")
}
