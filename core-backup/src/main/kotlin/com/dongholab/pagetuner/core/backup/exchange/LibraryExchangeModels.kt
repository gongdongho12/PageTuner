package com.dongholab.pagetuner.core.backup.exchange

import java.security.MessageDigest

data class LibraryExchangePackage(
    val createdAt: String,
    val documents: List<ExchangeDocument>,
    val assets: List<ExchangeAsset> = emptyList(),
)

data class ExchangeAsset(val bytes: ByteArray, val mimeType: String) {
    val sha256: String get() = exchangeSha256(bytes)
    val path: String get() = "assets/$sha256"
}

data class ExchangeDocument(
    val id: String,
    val bookTitle: String,
    val chapterTitle: String,
    val language: String,
    val kind: String,
    val paragraphs: List<ExchangeParagraph>,
    val outline: List<ExchangeOutlineEntry> = emptyList(),
    val position: ExchangeAnchor? = null,
    val notes: List<ExchangeNote> = emptyList(),
    val organization: ExchangeOrganization = ExchangeOrganization(),
    val glossary: List<ExchangeGlossaryEntry> = emptyList(),
    val assets: List<ExchangeAssetReference> = emptyList(),
    /** JSON object containing passive native metadata, never credentials or executable content. */
    val extensionsJson: String? = null,
)

data class ExchangeParagraph(val paragraphId: String, val text: String)
data class ExchangeOutlineEntry(val title: String, val paragraphId: String)
data class ExchangeAnchor(val paragraphId: String, val characterOffset: Int)
data class ExchangeRange(val start: ExchangeAnchor, val end: ExchangeAnchor)
data class ExchangeNote(
    val id: String,
    val kind: String,
    val title: String,
    val text: String,
    val excerpt: String,
    val anchor: ExchangeAnchor,
    val createdAt: String,
    val range: ExchangeRange? = null,
)
data class ExchangeOrganization(
    val folder: String = "",
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
)
data class ExchangeGlossaryEntry(
    val source: String,
    val target: String,
    val kind: String? = null,
    val displayTerm: String? = null,
    val caseSensitive: Boolean = true,
    val enabled: Boolean = true,
)
data class ExchangeAssetReference(
    val path: String,
    val role: String,
    val paragraphId: String? = null,
    val alt: String? = null,
)

object LibraryExchangeLimits {
    const val ARCHIVE_BYTES = 32 * 1024 * 1024
    const val EXPANDED_BYTES = 64 * 1024 * 1024
    const val DOCUMENT_BYTES = 8 * 1024 * 1024
    const val MANIFEST_BYTES = 1024 * 1024
    const val EXTENSIONS_BYTES = 256 * 1024
    const val MAX_DOCUMENTS = 100
    const val MAX_ENTRIES = 512
    const val MAX_PARAGRAPHS = 50_000
    const val MAX_CHARACTERS = 5_000_000
    const val MAX_NOTES = 2_000
    const val MAX_GLOSSARY = 2_000
    val ASSET_MIME_TYPES = setOf("application/pdf", "image/png", "image/jpeg", "image/webp", "image/gif")
}

/** Canonical lowercase content digest shared by models and archive runtimes. */
fun exchangeSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
