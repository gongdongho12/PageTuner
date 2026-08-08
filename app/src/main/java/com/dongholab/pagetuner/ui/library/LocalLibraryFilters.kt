package com.dongholab.pagetuner.ui.library

import com.dongholab.pagetuner.library.LocalBook

fun localLibraryFolders(books: List<LocalBook>): List<String> {
    return books
        .map { book -> book.folder.trim() }
        .filter { folder -> folder.isNotBlank() }
        .distinctBy { folder -> folder.lowercase() }
        .sortedBy { folder -> folder.lowercase() }
}

fun filterLocalLibraryBooks(
    books: List<LocalBook>,
    query: String,
    folder: String,
): List<LocalBook> {
    val normalizedQuery = query.trim()
    val normalizedFolder = folder.trim()
    return books.filter { book ->
        val folderMatches = normalizedFolder.isBlank() ||
            book.folder.trim().equals(normalizedFolder, ignoreCase = true)
        val queryMatches = normalizedQuery.isBlank() ||
            book.title.contains(normalizedQuery, ignoreCase = true) ||
            book.format.name.contains(normalizedQuery, ignoreCase = true) ||
            book.folder.contains(normalizedQuery, ignoreCase = true) ||
            book.tags.any { tag -> tag.contains(normalizedQuery, ignoreCase = true) }

        folderMatches && queryMatches
    }
}

fun groupedLocalLibraryBooks(books: List<LocalBook>): List<Pair<String, List<LocalBook>>> {
    return books
        .groupBy { book -> book.folder.trim() }
        .toList()
        .sortedWith(
            compareBy<Pair<String, List<LocalBook>>> { (folder, _) ->
                if (folder.isBlank()) "zzzzzz" else folder.lowercase()
            }.thenBy { (_, folderBooks) -> folderBooks.firstOrNull()?.title.orEmpty().lowercase() },
        )
}
