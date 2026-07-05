package com.dongholab.pagetuner.library

import com.dongholab.pagetuner.document.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalBookJsonTest {
    @Test
    fun roundTripPreservesLocalBookMetadata() {
        val book = LocalBook(
            id = "book-id",
            title = "Example",
            format = DocumentFormat.EPUB,
            relativePath = "books/example.epub",
            contentHash = "hash",
            pageCount = 10,
            currentPageIndex = 4,
            importedAtMillis = 100L,
            lastOpenedAtMillis = 200L,
            fileSizeBytes = 300L,
            bookmarks = listOf(
                LocalBookBookmark(
                    id = "bookmark-1",
                    pageIndex = 4,
                    label = "Important",
                    createdAtMillis = 150L,
                ),
            ),
            annotations = listOf(
                LocalBookAnnotation(
                    id = "annotation-1",
                    type = LocalBookAnnotationType.Note,
                    pageIndex = 4,
                    text = "Remember this page.",
                    createdAtMillis = 160L,
                ),
            ),
        )

        val decoded = LocalBookJson.decode(LocalBookJson.encode(listOf(book)))

        assertEquals(listOf(book), decoded)
        assertEquals(50, decoded.single().readingProgressPercent)
    }

    @Test
    fun decodeIgnoresIncompleteRows() {
        val decoded = LocalBookJson.decode(
            """
            [
              {"id": "", "relativePath": "books/missing.txt"},
              {"id": "ok", "relativePath": "books/ok.txt", "format": "TEXT"}
            ]
            """.trimIndent(),
        )

        assertEquals(1, decoded.size)
        assertEquals("ok", decoded.single().id)
        assertEquals(DocumentFormat.TEXT, decoded.single().format)
    }
}
