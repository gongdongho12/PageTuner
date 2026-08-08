package com.dongholab.pagetuner.ui.library

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.library.LocalBook
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLibraryFiltersTest {
    @Test
    fun filterLocalLibraryBooksMatchesTitleFolderFormatAndTags() {
        val books = listOf(
            book(id = "one", title = "Solar Notes", folder = "Science", tags = listOf("Physics")),
            book(id = "two", title = "Korean Reader", folder = "Language", tags = listOf("KO", "Study")),
            book(id = "three", title = "Offline Memo", format = DocumentFormat.MARKDOWN, tags = listOf("Travel")),
        )

        assertEquals(listOf("one"), filterLocalLibraryBooks(books, query = "solar", folder = "").map { it.id })
        assertEquals(listOf("one"), filterLocalLibraryBooks(books, query = "physics", folder = "").map { it.id })
        assertEquals(listOf("two"), filterLocalLibraryBooks(books, query = "reader", folder = "language").map { it.id })
        assertEquals(listOf("three"), filterLocalLibraryBooks(books, query = "markdown", folder = "").map { it.id })
    }

    @Test
    fun localLibraryFoldersReturnsDistinctSortedNonBlankFolders() {
        val books = listOf(
            book(id = "one", folder = "Language"),
            book(id = "two", folder = "science"),
            book(id = "three", folder = " Science "),
            book(id = "four", folder = ""),
        )

        assertEquals(listOf("Language", "science"), localLibraryFolders(books))
    }

    @Test
    fun groupedLocalLibraryBooksKeepsUncategorizedLast() {
        val books = listOf(
            book(id = "uncategorized", title = "Loose Notes", folder = ""),
            book(id = "science", title = "Solar Notes", folder = "Science"),
            book(id = "language", title = "Korean Reader", folder = "Language"),
        )

        assertEquals(
            listOf("Language", "Science", ""),
            groupedLocalLibraryBooks(books).map { (folder, _) -> folder },
        )
    }

    private fun book(
        id: String,
        title: String = id,
        format: DocumentFormat = DocumentFormat.TEXT,
        folder: String = "",
        tags: List<String> = emptyList(),
    ): LocalBook {
        return LocalBook(
            id = id,
            title = title,
            format = format,
            relativePath = "books/$id.txt",
            contentHash = id,
            pageCount = 1,
            currentPageIndex = 0,
            importedAtMillis = 0L,
            lastOpenedAtMillis = 0L,
            fileSizeBytes = 1L,
            folder = folder,
            tags = tags,
        )
    }
}
