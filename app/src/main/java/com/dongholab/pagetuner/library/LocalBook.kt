package com.dongholab.pagetuner.library

import com.dongholab.pagetuner.document.DocumentFormat

data class LocalBook(
    val id: String,
    val title: String,
    val format: DocumentFormat,
    val relativePath: String,
    val contentHash: String,
    val pageCount: Int,
    val currentPageIndex: Int,
    val importedAtMillis: Long,
    val lastOpenedAtMillis: Long,
    val fileSizeBytes: Long,
    val folder: String = "",
    val tags: List<String> = emptyList(),
    val bookmarks: List<LocalBookBookmark> = emptyList(),
    val annotations: List<LocalBookAnnotation> = emptyList(),
) {
    val safeCurrentPageIndex: Int
        get() = currentPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    val readingProgressPercent: Int
        get() = if (pageCount <= 0) {
            0
        } else {
            (((safeCurrentPageIndex + 1).toFloat() / pageCount.toFloat()) * 100f).toInt()
                .coerceIn(0, 100)
        }
}

data class LocalBookBookmark(
    val id: String,
    val pageIndex: Int,
    val label: String?,
    val createdAtMillis: Long,
)

data class LocalBookAnnotation(
    val id: String,
    val type: LocalBookAnnotationType,
    val pageIndex: Int,
    val text: String,
    val createdAtMillis: Long,
)

enum class LocalBookAnnotationType {
    Highlight,
    Note,
}

fun normalizeLocalBookFolder(folder: String): String {
    return folder.trim().replace(Regex("\\s+"), " ").take(80)
}

fun parseLocalBookTags(rawTags: String): List<String> {
    return rawTags
        .split(',', '#')
        .map { tag -> tag.trim().replace(Regex("\\s+"), " ").take(40) }
        .filter { tag -> tag.isNotBlank() }
        .distinctBy { tag -> tag.lowercase() }
        .take(12)
}
