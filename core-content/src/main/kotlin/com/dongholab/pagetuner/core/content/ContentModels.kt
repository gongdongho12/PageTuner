package com.dongholab.pagetuner.core.content

import java.security.MessageDigest

data class BookIdentity(
    val providerId: String,
    val bookId: String,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank." }
        require(bookId.isNotBlank()) { "bookId must not be blank." }
    }

    val canonicalId: String = "${providerId.trim()}:${bookId.trim()}"
}

data class ChapterIdentity(
    val book: BookIdentity,
    val chapterId: String,
) {
    init {
        require(chapterId.isNotBlank()) { "chapterId must not be blank." }
    }

    val canonicalId: String = "${book.canonicalId}:${chapterId.trim()}"
}

data class ContentParagraph(
    val paragraphId: String,
    val ordinal: Int,
    val text: String,
) {
    init {
        require(paragraphId.isNotBlank()) { "paragraphId must not be blank." }
        require(ordinal >= 0) { "ordinal must not be negative." }
    }

    val sourceHash: String = StableContentHash.sha256(text)
}

data class ChapterContent(
    val identity: ChapterIdentity,
    val title: String,
    val sourceLanguage: String,
    val paragraphs: List<ContentParagraph>,
) {
    init {
        require(sourceLanguage.isNotBlank()) { "sourceLanguage must not be blank." }
        require(paragraphs.map(ContentParagraph::paragraphId).distinct().size == paragraphs.size) {
            "paragraphId values must be unique within a chapter."
        }
    }

    val sourceRevision: String = StableContentHash.sha256(
        paragraphs.joinToString(separator = "\n") { paragraph ->
            "${paragraph.paragraphId}:${paragraph.ordinal}:${paragraph.sourceHash}"
        },
    )
}

/** Device-independent reading position that each renderer maps to its own page index. */
data class ReadingAnchor(
    val chapter: ChapterIdentity,
    val paragraphId: String,
    val characterOffset: Int = 0,
) {
    init {
        require(paragraphId.isNotBlank()) { "paragraphId must not be blank." }
        require(characterOffset >= 0) { "characterOffset must not be negative." }
    }
}

object StableContentHash {
    fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
