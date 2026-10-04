package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.content.*
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import java.security.MessageDigest

enum class DocumentIdentityKind { ORIGINAL, TRANSLATION }

/** Portable provenance. Neither a server UUID nor a synchronization authorization. */
data class DocumentIdentity(
    val version: Int = 1,
    val kind: DocumentIdentityKind,
    val contentProviderId: String,
    val bookId: String,
    val chapterId: String,
    val sourceRevision: String,
    val sourceLanguage: String,
    val paragraphHash: String,
    val targetLanguage: String? = null,
    val translationProviderId: String? = null,
    val modelId: String? = null,
    val promptRevision: String? = null,
    val glossaryRevision: String? = null,
    val artifactId: String? = null,
    val revision: String? = null,
    val payloadHash: String? = null,
)

object DocumentIdentities {
    private val hash = Regex("[a-f0-9]{64}")
    private val language = Regex("[A-Za-z][A-Za-z0-9-]{0,34}")

    fun validate(identity: DocumentIdentity) {
        require(identity.version == 1)
        listOf(identity.contentProviderId, identity.bookId, identity.chapterId).forEach { text(it, 1, 2000, controls = false) }
        text(identity.sourceRevision, 1, 64, controls = false)
        require(language.matches(identity.sourceLanguage))
        require(hash.matches(identity.paragraphHash))
        val translation = listOf(identity.targetLanguage, identity.translationProviderId, identity.modelId,
            identity.promptRevision, identity.glossaryRevision, identity.artifactId, identity.revision, identity.payloadHash)
        if (identity.kind == DocumentIdentityKind.ORIGINAL) {
            require(translation.all { it == null } && hash.matches(identity.sourceRevision))
        } else {
            require(translation.all { it != null })
            require(language.matches(requireNotNull(identity.targetLanguage)))
            text(requireNotNull(identity.translationProviderId), 1, 2000, controls = false)
            listOf(identity.modelId, identity.promptRevision, identity.glossaryRevision).forEach { text(requireNotNull(it), 0, 2000, controls = false) }
            listOf(identity.artifactId, identity.revision, identity.payloadHash).forEach { require(hash.matches(requireNotNull(it))) }
        }
    }

    /** UTF-8 length-prefixed frames make paragraph boundaries, IDs and text unambiguous. */
    fun paragraphHash(paragraphs: List<ExchangeParagraph>): String {
        require(paragraphs.isNotEmpty() && paragraphs.size <= LibraryExchangeLimits.MAX_PARAGRAPHS)
        require(paragraphs.map { it.paragraphId }.distinct().size == paragraphs.size)
        require(paragraphs.sumOf { it.text.length.toLong() } <= LibraryExchangeLimits.MAX_CHARACTERS)
        val digest = MessageDigest.getInstance("SHA-256")
        fun frame(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(':'.code.toByte()); digest.update(bytes)
        }
        frame("pageturner.document-paragraphs.v1"); frame(paragraphs.size.toString())
        paragraphs.forEach { paragraph ->
            text(paragraph.paragraphId, 1, 500, controls = true)
            text(paragraph.text, 0, LibraryExchangeLimits.MAX_CHARACTERS, controls = true)
            frame(paragraph.paragraphId); frame(paragraph.text)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun original(content: ChapterContent): DocumentIdentity {
        require(content.paragraphs.map { it.ordinal } == content.paragraphs.indices.toList())
        return DocumentIdentity(kind = DocumentIdentityKind.ORIGINAL, contentProviderId = content.identity.book.providerId,
            bookId = content.identity.book.bookId, chapterId = content.identity.chapterId, sourceRevision = content.sourceRevision,
            sourceLanguage = content.sourceLanguage, paragraphHash = paragraphHash(content.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) }))
            .also(::validate)
    }

    fun translation(artifact: TranslationArtifact): DocumentIdentity = DocumentIdentity(kind = DocumentIdentityKind.TRANSLATION,
        contentProviderId = artifact.chapter.book.providerId, bookId = artifact.chapter.book.bookId, chapterId = artifact.chapter.chapterId,
        sourceRevision = artifact.sourceRevision, sourceLanguage = artifact.sourceLanguage,
        paragraphHash = paragraphHash(artifact.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) }),
        targetLanguage = artifact.targetLanguage, translationProviderId = artifact.providerId, modelId = artifact.modelId,
        promptRevision = artifact.promptRevision, glossaryRevision = artifact.glossaryRevision, artifactId = artifact.artifactId,
        revision = artifact.revision, payloadHash = artifact.payloadHash).also(::validate)

    /** Only exact text representations are supported; page/EPUB/PDF anchors cannot be inferred. */
    fun validateDocument(document: ExchangeDocument, identity: DocumentIdentity) {
        validate(identity)
        require(document.assets.isEmpty()) { "Asset-backed documents are not supported by identity verification v1." }
        require(document.kind == if (identity.kind == DocumentIdentityKind.ORIGINAL) "original" else "translation")
        require(document.language == (identity.targetLanguage ?: identity.sourceLanguage))
        require(paragraphHash(document.paragraphs) == identity.paragraphHash)
        val chapter = ChapterIdentity(BookIdentity(identity.contentProviderId, identity.bookId), identity.chapterId)
        val computed = if (identity.kind == DocumentIdentityKind.ORIGINAL) original(ChapterContent(chapter, document.chapterTitle,
            identity.sourceLanguage, document.paragraphs.mapIndexed { index, p -> ContentParagraph(p.paragraphId, index, p.text) }))
        else translation(TranslationArtifact(chapter, identity.sourceRevision, identity.sourceLanguage,
            requireNotNull(identity.targetLanguage), requireNotNull(identity.translationProviderId), requireNotNull(identity.modelId),
            requireNotNull(identity.promptRevision), requireNotNull(identity.glossaryRevision), document.paragraphs.map { TranslatedParagraph(it.paragraphId, it.text) }))
        require(computed == identity) { "Document paragraphs or provenance do not match their declared identity." }
    }

    private fun text(value: String, minimum: Int, maximum: Int, controls: Boolean) {
        require(value.length in minimum..maximum && (minimum == 0 || value.isNotBlank()))
        require(controls || value.none(Char::isISOControl))
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate())
            else require(!c.isLowSurrogate())
        }
    }
}
