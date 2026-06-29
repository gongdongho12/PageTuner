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
