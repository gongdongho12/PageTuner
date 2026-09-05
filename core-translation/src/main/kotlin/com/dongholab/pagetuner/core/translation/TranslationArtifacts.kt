package com.dongholab.pagetuner.core.translation

import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.StableContentHash

/** Stable identity used by Android, web, and server translation stores. */
data class TranslationSegmentIdentity(
    val documentId: String,
    val segmentId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val providerId: String,
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank." }
        require(segmentId.isNotBlank()) { "segmentId must not be blank." }
        require(sourceLanguage.isNotBlank()) { "sourceLanguage must not be blank." }
        require(targetLanguage.isNotBlank()) { "targetLanguage must not be blank." }
        require(providerId.isNotBlank()) { "providerId must not be blank." }
    }

    /** Keeps compatibility with the existing Android translation cache key. */
    val id: String = StableContentHash.sha256(
        listOf(documentId, segmentId, sourceLanguage, targetLanguage, providerId).joinToString("|"),
    )
}

data class TranslatedParagraph(
    val paragraphId: String,
    val text: String,
) {
    init {
        require(paragraphId.isNotBlank()) { "paragraphId must not be blank." }
    }
}

data class TranslationArtifact(
    val chapter: ChapterIdentity,
    val sourceRevision: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val providerId: String,
    val modelId: String,
    val promptRevision: String,
    val glossaryRevision: String,
    val paragraphs: List<TranslatedParagraph>,
) {
    init {
        require(sourceRevision.isNotBlank()) { "sourceRevision must not be blank." }
        require(sourceLanguage.isNotBlank()) { "sourceLanguage must not be blank." }
        require(targetLanguage.isNotBlank()) { "targetLanguage must not be blank." }
        require(providerId.isNotBlank()) { "providerId must not be blank." }
        require(paragraphs.map(TranslatedParagraph::paragraphId).distinct().size == paragraphs.size) {
            "paragraphId values must be unique within a translation artifact."
        }
    }

    val artifactId: String = StableContentHash.sha256(
        listOf(
            chapter.canonicalId,
            sourceRevision,
            sourceLanguage,
            targetLanguage,
            providerId,
            modelId,
            promptRevision,
            glossaryRevision,
        ).joinToString("|"),
    )

    val payloadHash: String = StableContentHash.sha256(
        paragraphs.joinToString(separator = "\n") { "${it.paragraphId}:${it.text}" },
    )

    /** Changes when translated content changes, while artifactId identifies the translation variant. */
    val revision: String = StableContentHash.sha256("$artifactId|$payloadHash")
}
