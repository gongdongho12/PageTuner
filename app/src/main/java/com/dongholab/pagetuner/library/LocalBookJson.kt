package com.dongholab.pagetuner.library

import com.dongholab.pagetuner.document.DocumentFormat
import org.json.JSONArray
import org.json.JSONObject

object LocalBookJson {
    fun encode(books: List<LocalBook>): String {
        val array = JSONArray()
        books.forEach { book ->
            array.put(
                JSONObject()
                    .put("id", book.id)
                    .put("title", book.title)
                    .put("format", book.format.name)
                    .put("relativePath", book.relativePath)
                    .put("contentHash", book.contentHash)
                    .put("pageCount", book.pageCount)
                    .put("currentPageIndex", book.currentPageIndex)
                    .put("importedAtMillis", book.importedAtMillis)
                    .put("lastOpenedAtMillis", book.lastOpenedAtMillis)
                    .put("fileSizeBytes", book.fileSizeBytes)
                    .put("bookmarks", book.bookmarksToJsonArray())
                    .put("annotations", book.annotationsToJsonArray()),
            )
        }
        return array.toString(2)
    }

    fun decode(rawJson: String): List<LocalBook> {
        if (rawJson.isBlank()) return emptyList()

        val array = JSONArray(rawJson)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    LocalBook(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        format = item.optDocumentFormat(),
                        relativePath = item.optString("relativePath"),
                        contentHash = item.optString("contentHash"),
                        pageCount = item.optInt("pageCount", 1).coerceAtLeast(1),
                        currentPageIndex = item.optInt("currentPageIndex", 0).coerceAtLeast(0),
                        importedAtMillis = item.optLong("importedAtMillis", 0L),
                        lastOpenedAtMillis = item.optLong("lastOpenedAtMillis", 0L),
                        fileSizeBytes = item.optLong("fileSizeBytes", 0L),
                        bookmarks = item.optBookmarks(),
                        annotations = item.optAnnotations(),
                    ),
                )
            }
        }.filter { it.id.isNotBlank() && it.relativePath.isNotBlank() }
    }

    private fun JSONObject.optDocumentFormat(): DocumentFormat {
        val stored = optString("format", DocumentFormat.TEXT.name)
        return runCatching { DocumentFormat.valueOf(stored) }.getOrDefault(DocumentFormat.TEXT)
    }

    private fun LocalBook.bookmarksToJsonArray(): JSONArray {
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            array.put(
                JSONObject()
                    .put("id", bookmark.id)
                    .put("pageIndex", bookmark.pageIndex)
                    .put("label", bookmark.label)
                    .put("createdAtMillis", bookmark.createdAtMillis),
            )
        }
        return array
    }

    private fun JSONObject.optBookmarks(): List<LocalBookBookmark> {
        val array = optJSONArray("bookmarks") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    LocalBookBookmark(
                        id = id,
                        pageIndex = item.optInt("pageIndex", 0).coerceAtLeast(0),
                        label = item.optString("label").takeIf { it.isNotBlank() },
                        createdAtMillis = item.optLong("createdAtMillis", 0L),
                    ),
                )
            }
        }.sortedBy { it.pageIndex }
    }

    private fun LocalBook.annotationsToJsonArray(): JSONArray {
        val array = JSONArray()
        annotations.forEach { annotation ->
            array.put(
                JSONObject()
                    .put("id", annotation.id)
                    .put("type", annotation.type.name)
                    .put("pageIndex", annotation.pageIndex)
                    .put("text", annotation.text)
                    .put("createdAtMillis", annotation.createdAtMillis),
            )
        }
        return array
    }

    private fun JSONObject.optAnnotations(): List<LocalBookAnnotation> {
        val array = optJSONArray("annotations") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val text = item.optString("text")
                if (id.isBlank() || text.isBlank()) continue
                add(
                    LocalBookAnnotation(
                        id = id,
                        type = item.optAnnotationType(),
                        pageIndex = item.optInt("pageIndex", 0).coerceAtLeast(0),
                        text = text,
                        createdAtMillis = item.optLong("createdAtMillis", 0L),
                    ),
                )
            }
        }.sortedWith(compareBy<LocalBookAnnotation> { it.pageIndex }.thenBy { it.createdAtMillis })
    }

    private fun JSONObject.optAnnotationType(): LocalBookAnnotationType {
        val stored = optString("type", LocalBookAnnotationType.Note.name)
        return runCatching {
            LocalBookAnnotationType.valueOf(stored)
        }.getOrDefault(LocalBookAnnotationType.Note)
    }
}
