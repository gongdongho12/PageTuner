package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import com.dongholab.pagetuner.core.translation.TranslationSaveResult
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
import java.util.UUID

data class SaveTranslationRequest(
    @field:NotBlank val contentProviderId: String,
    @field:NotBlank val bookId: String,
    @field:NotBlank val chapterId: String,
    @field:NotBlank val sourceRevision: String,
    @field:NotBlank val sourceLanguage: String,
    @field:NotBlank val targetLanguage: String,
    @field:NotBlank val translationProviderId: String,
    val modelId: String = "",
    val promptRevision: String = "",
    val glossaryRevision: String = "",
    @field:NotEmpty @field:Valid val paragraphs: List<TranslatedParagraphRequest>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val bookTitle: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val chapterTitle: String? = null,
) {
    fun toArtifact(): TranslationArtifact = TranslationArtifact(
        chapter = ChapterIdentity(BookIdentity(contentProviderId, bookId), chapterId),
        sourceRevision = sourceRevision,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        providerId = translationProviderId,
        modelId = modelId,
        promptRevision = promptRevision,
        glossaryRevision = glossaryRevision,
        paragraphs = paragraphs.map { TranslatedParagraph(it.paragraphId, it.text) },
    )
}

data class TranslatedParagraphRequest(
    @field:NotBlank val paragraphId: String,
    @field:NotBlank val text: String,
)

/** One saved revision. Paragraph bodies are available only through the record endpoint. */
data class TranslationSummary(
    val recordId: UUID,
    val contentProviderId: String,
    val bookId: String,
    val chapterId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translationProviderId: String,
    val modelId: String,
    val promptRevision: String,
    val glossaryRevision: String,
    val sourceRevision: String,
    val artifactId: String,
    val revision: String,
    val payloadHash: String,
    val createdAt: Instant,
    val paragraphCount: Int,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val bookTitle: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val chapterTitle: String? = null,
)

data class TranslationListResponse(
    val items: List<TranslationSummary>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)

data class TranslationResponse(
    val recordId: UUID,
    val artifactId: String,
    val revision: String,
    val payloadHash: String,
    val created: Boolean,
    val createdAt: Instant,
    val paragraphs: List<TranslatedParagraphRequest>,
    val contentProviderId: String,
    val bookId: String,
    val chapterId: String,
    val sourceRevision: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translationProviderId: String,
    val modelId: String,
    val promptRevision: String,
    val glossaryRevision: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val bookTitle: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val chapterTitle: String? = null,
) {
    companion object {
        fun from(result: TranslationSaveResult): TranslationResponse {
            val stored = result.translation
            val artifact = stored.artifact
            return TranslationResponse(
                recordId = UUID.fromString(stored.recordId),
                artifactId = artifact.artifactId,
                revision = artifact.revision,
                payloadHash = artifact.payloadHash,
                created = result.created,
                createdAt = Instant.parse(stored.createdAt),
                paragraphs = artifact.paragraphs.map { TranslatedParagraphRequest(it.paragraphId, it.text) },
                contentProviderId = artifact.chapter.book.providerId,
                bookId = artifact.chapter.book.bookId,
                chapterId = artifact.chapter.chapterId,
                sourceRevision = artifact.sourceRevision,
                sourceLanguage = artifact.sourceLanguage,
                targetLanguage = artifact.targetLanguage,
                translationProviderId = artifact.providerId,
                modelId = artifact.modelId,
                promptRevision = artifact.promptRevision,
                glossaryRevision = artifact.glossaryRevision,
            )
        }
    }
}

data class PlanBackupRequest(
    @field:NotBlank val backupAccountId: String,
)

enum class BackupPlanStatus {
    Enqueued,
    ActiveJobReused,
    AlreadyBackedUp,
}

data class BackupPlanResponse(
    val backupRecordId: UUID,
    val backupKeyId: String,
    val status: BackupPlanStatus,
)
