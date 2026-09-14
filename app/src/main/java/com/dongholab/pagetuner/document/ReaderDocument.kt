package com.dongholab.pagetuner.document


data class ReaderDocument(
    val id: String,
    val title: String,
    val format: DocumentFormat,
    val pages: List<ReaderPage>,
    val tableOfContents: List<DocumentOutlineItem> = emptyList(),
) {
    val pageCount: Int = pages.size
}

data class ReaderPage(
    val index: Int,
    val segments: List<TextSegment>,
    val chapterTitle: String? = null,
    val imageCount: Int = 0,
    val images: List<ReaderPageImage> = emptyList(),
) {
    val plainText: String = segments.joinToString(separator = "\n\n") { it.text }
    val hasText: Boolean = segments.any { it.text.isNotBlank() }
}

data class ReaderPageImage(
    val id: String,
    val altText: String?,
    val mimeType: String,
    val bytes: ByteArray,
)

data class DocumentOutlineItem(
    val title: String,
    val pageIndex: Int,
    val level: Int = 1,
)

enum class DocumentFormat {
    TEXT,
    MARKDOWN,
    PDF,
    EPUB,
}
