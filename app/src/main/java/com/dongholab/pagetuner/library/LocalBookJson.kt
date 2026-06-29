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
                    .put("fileSizeBytes", book.fileSizeBytes),
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
                    ),
                )
            }
        }.filter { it.id.isNotBlank() && it.relativePath.isNotBlank() }
    }

    private fun JSONObject.optDocumentFormat(): DocumentFormat {
        val stored = optString("format", DocumentFormat.TEXT.name)
        return runCatching { DocumentFormat.valueOf(stored) }.getOrDefault(DocumentFormat.TEXT)
    }
}
