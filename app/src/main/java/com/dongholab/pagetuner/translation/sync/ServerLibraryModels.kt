package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.core.translation.StoredTranslation
import java.util.UUID
import org.json.JSONObject

enum class ServerLibraryKind(val label: String) { Translations("번역"), Originals("원문") }
data class ServerLibraryEntry(
    val recordId: String, val title: String, val language: String, val paragraphCount: Int,
    val sourceRevision: String, val kind: ServerLibraryKind,
)
data class ServerLibraryPage(
    val items: List<ServerLibraryEntry>, val page: Int, val size: Int, val totalItems: Long,
    val totalPages: Int, val hasNext: Boolean,
)
data class ServerLibraryDocument(
    val entry: ServerLibraryEntry,
    val paragraphs: List<String>,
    val storedTranslation: StoredTranslation? = null,
    val sourceContent: ChapterContent? = null,
) {
    val text: String get() = paragraphs.joinToString("\n\n")
}

internal object ServerLibraryJson {
    fun page(json: JSONObject, kind: ServerLibraryKind, page: Int, size: Int): ServerLibraryPage {
        require(json.integer("page") == page && json.integer("size") == size)
        val rawCount = json.get("totalItems")
        require(rawCount is Int || rawCount is Long)
        val count = (rawCount as Number).toLong().also { require(it >= 0) }
        val pages = json.integer("totalPages")
        val next = json.get("hasNext") as? Boolean ?: error("Missing next page flag")
        require(pages.toLong() == count / size + (if (count % size > 0) 1 else 0) && next == (page.toLong() + 1 < pages))
        val values = json.getJSONArray("items")
        val expectedItems = (count - page.toLong() * size).coerceIn(0, size.toLong()).toInt()
        require(values.length() == expectedItems)
        val entries = List(values.length()) { index ->
            val item = values.getJSONObject(index)
            val id = item.string("recordId").also { require(UUID.fromString(it).toString() == it) }
            val title = listOfNotNull(item.optionalTitle("bookTitle"), item.optionalTitle("chapterTitle"))
                .distinct().joinToString(" · ").ifBlank { if (kind == ServerLibraryKind.Translations) "제목 없는 번역" else "제목 없는 원문" }
            val paragraphs = item.integer("paragraphCount").also { require(it in 1..10_000) }
            ServerLibraryEntry(id, title, item.string(if (kind == ServerLibraryKind.Translations) "targetLanguage" else "sourceLanguage"),
                paragraphs, item.string("sourceRevision"), kind)
        }
        require(entries.map { it.recordId }.distinct().size == entries.size)
        return ServerLibraryPage(entries, page, size, count, pages, next)
    }

    fun chapter(json: JSONObject, entry: ServerLibraryEntry): ServerLibraryDocument {
        require(json.string("recordId") == entry.recordId)
        val values = json.getJSONArray("paragraphs")
        require(values.length() == entry.paragraphCount)
        val paragraphs = List(values.length()) { index ->
            val paragraph = values.getJSONObject(index)
            require(paragraph.integer("ordinal") == index)
            ContentParagraph(paragraph.string("paragraphId"), index, paragraph.string("text"))
        }
        val chapter = ChapterContent(ChapterIdentity(BookIdentity(json.string("providerId"), json.string("bookId")),
            json.string("chapterId")), json.string("chapterTitle"), json.string("sourceLanguage"), paragraphs)
        require(chapter.sourceLanguage == entry.language)
        require(chapter.sourceRevision == json.string("sourceRevision") && chapter.sourceRevision == entry.sourceRevision)
        return ServerLibraryDocument(entry, paragraphs.map { it.text }, sourceContent = chapter)
    }

    private fun JSONObject.string(key: String) = (get(key) as? String)?.takeIf(String::isNotBlank) ?: error("Invalid string")
    private fun JSONObject.integer(key: String): Int {
        val value = get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in 0..Int.MAX_VALUE) }.toInt()
    }
    private fun JSONObject.optionalTitle(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return string(key).also { require(it.length <= 4_000) }
    }
}
