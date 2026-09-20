package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
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

data class TranslationResponse(
    val recordId: UUID,
    val artifactId: String,
    val revision: String,
    val payloadHash: String,
    val created: Boolean,
    val createdAt: Instant,
    val paragraphs: List<TranslatedParagraphRequest>,
)

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

/** Portable backup; ownership is always taken from the authenticated session on restore. */
data class TranslationBackupDocument(
    val schemaVersion: Int,
    val artifactId: String,
    val revision: String,
    val payloadHash: String,
    @field:Valid val translation: SaveTranslationRequest,
) {
    fun verifiedRequest(): SaveTranslationRequest {
        require(schemaVersion == 1) { "Unsupported backup schema version." }
        val artifact = translation.toArtifact()
        require(artifact.artifactId == artifactId && artifact.revision == revision &&
            artifact.payloadHash == payloadHash) { "Backup content failed integrity validation." }
        return translation
    }
}


data class TranslationSummary(
    val recordId: UUID, val contentProviderId: String, val bookId: String, val chapterId: String,
    val sourceRevision: String, val sourceLanguage: String, val targetLanguage: String,
    val translationProviderId: String, val modelId: String, val promptRevision: String,
    val glossaryRevision: String, val artifactId: String, val revision: String,
    val payloadHash: String, val createdAt: Instant,
)
