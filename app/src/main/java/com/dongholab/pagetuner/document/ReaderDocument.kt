package com.dongholab.pagetuner.document

import java.security.MessageDigest

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

data class TextSegment(
    val id: String,
    val pageIndex: Int,
    val indexInPage: Int,
    val text: String,
) {
    val wordCount: Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

enum class DocumentFormat {
    TEXT,
    MARKDOWN,
    PDF,
    EPUB,
}

object DocumentIds {
    fun stableId(title: String, body: String): String = sha256("$title\n$body")

    fun segmentId(documentId: String, pageIndex: Int, indexInPage: Int, text: String): String {
        return sha256("$documentId:$pageIndex:$indexInPage:$text").take(24)
    }

    fun sha256(value: String): String {
        return sha256(value.toByteArray(Charsets.UTF_8))
    }

    fun sha256(value: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
