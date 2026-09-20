package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.core.backup.BackupDecision
import com.dongholab.pagetuner.core.backup.BackupState
import com.dongholab.pagetuner.core.backup.TranslationBackupKey
import com.dongholab.pagetuner.core.backup.TranslationBackupPolicy
import com.dongholab.pagetuner.core.backup.TranslationBackupRecord
import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import com.dongholab.pagetuner.server.library.PageResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TranslationApplicationService(
    private val artifacts: TranslationArtifactRepository,
    private val backups: TranslationBackupRepository,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
) {
    @Transactional
    fun save(userId: String, request: SaveTranslationRequest): TranslationResponse {
        require(userId.isNotBlank()) { "userId must not be blank." }
        val artifact = request.toArtifact()
        lockKey("artifact:$userId:${artifact.artifactId}:${artifact.revision}")
        val existing = artifacts.findByUserIdAndArtifactIdAndRevision(
            userId,
            artifact.artifactId,
            artifact.revision,
        )
        if (existing != null) return existing.toResponse(created = false)

        val entity = artifact.toEntity(userId)
        return artifacts.saveAndFlush(entity).toResponse(created = true)
    }

    @Transactional(readOnly = true)
    fun list(userId: String, page: Int, size: Int, contentProviderId: String?, bookId: String?,
        chapterId: String?, sourceRevision: String?, targetLanguage: String?): PageResponse<TranslationSummary> {
        require(page >= 0 && size in 1..100) { "page must be nonnegative and size must be between 1 and 100." }
        require((contentProviderId == null) == (bookId == null)) { "contentProviderId and bookId must be supplied together." }
        val providerBookId = if (contentProviderId != null && bookId != null) BookIdentity(contentProviderId, bookId).canonicalId else null
        val result = artifacts.search(userId, providerBookId, chapterId, sourceRevision, targetLanguage,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
        return PageResponse(result.content.map { a -> TranslationSummary(requireNotNull(a.id),
            a.providerBookId.substringBefore(':'), a.providerBookId.substringAfter(':'), a.chapterId,
            a.sourceRevision, a.sourceLanguage, a.targetLanguage, a.translationProviderId, a.modelId,
            a.promptRevision, a.glossaryRevision, a.artifactId, a.revision, a.payloadHash, a.createdAt) }, page, size, result.totalElements)
    }

    @Transactional(readOnly = true)
    fun get(userId: String, recordId: UUID): TranslationResponse =
        (artifacts.findByIdAndUserId(recordId, userId) ?: throw TranslationNotFound())
            .toResponse(created = false)

    @Transactional(readOnly = true)
    fun exportBackup(userId: String, recordId: UUID): TranslationBackupDocument {
        val entity = artifacts.findByIdAndUserId(recordId, userId) ?: throw TranslationNotFound()
        return TranslationBackupDocument(
            schemaVersion = 1,
            artifactId = entity.artifactId,
            revision = entity.revision,
            payloadHash = entity.payloadHash,
            translation = SaveTranslationRequest(
                contentProviderId = entity.providerBookId.substringBefore(':'),
                bookId = entity.providerBookId.substringAfter(':'),
                chapterId = entity.chapterId,
                sourceRevision = entity.sourceRevision,
                sourceLanguage = entity.sourceLanguage,
                targetLanguage = entity.targetLanguage,
                translationProviderId = entity.translationProviderId,
                modelId = entity.modelId,
                promptRevision = entity.promptRevision,
                glossaryRevision = entity.glossaryRevision,
                paragraphs = entity.readParagraphs().map { TranslatedParagraphRequest(it.paragraphId, it.text) },
            ),
        ).also { it.verifiedRequest() }
    }

    @Transactional
    fun planBackup(
        userId: String,
        recordId: UUID,
        request: PlanBackupRequest,
    ): BackupPlanResponse {
        val artifactEntity = artifacts.findByIdAndUserId(recordId, userId) ?: throw TranslationNotFound()
        val artifact = artifactEntity.toArtifact()
        val desired = TranslationBackupKey.from(userId, request.backupAccountId, artifact)
        lockKey("backup:${desired.id}")
        val existing = backups.findAllByUserIdAndBackupAccountIdAndArtifactId(
            userId,
            request.backupAccountId,
            artifact.artifactId,
        )
        val recordsByKey = existing.associateBy(TranslationBackupEntity::backupKeyId)
        return when (val decision = TranslationBackupPolicy.decide(desired, existing.map { it.toCoreRecord() })) {
            BackupDecision.Enqueue -> enqueueBackup(desired)
            is BackupDecision.ReuseActiveJob -> recordsByKey.getValue(decision.record.key.id)
                .toPlanResponse(BackupPlanStatus.ActiveJobReused)
            is BackupDecision.SkipAlreadyBackedUp -> recordsByKey.getValue(decision.record.key.id)
                .toPlanResponse(BackupPlanStatus.AlreadyBackedUp)
        }
    }

    private fun enqueueBackup(key: TranslationBackupKey): BackupPlanResponse {
        backups.findByBackupKeyId(key.id)?.let { failed ->
            check(failed.state == BackupState.Failed)
            failed.state = BackupState.Queued
            failed.updatedAt = Instant.now()
            return backups.saveAndFlush(failed).toPlanResponse(BackupPlanStatus.Enqueued)
        }
        val entity = TranslationBackupEntity(
            backupKeyId = key.id,
            userId = key.userId,
            backupAccountId = key.backupAccountId,
            artifactId = key.artifactId,
            revision = key.revision,
            payloadHash = key.payloadHash,
            state = BackupState.Queued,
        )
        return backups.saveAndFlush(entity).toPlanResponse(BackupPlanStatus.Enqueued)
    }

    /** Transaction-scoped lock shared by every server process using this database. */
    private fun lockKey(key: String) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { rs ->
            while (rs.next()) { /* acquire and drain the PostgreSQL void result */ }
        }, key)
    }

    private fun TranslationArtifact.toEntity(userId: String) = TranslationArtifactEntity(
        userId = userId,
        providerBookId = chapter.book.canonicalId,
        chapterId = chapter.chapterId,
        sourceRevision = sourceRevision,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        translationProviderId = providerId,
        modelId = modelId,
        promptRevision = promptRevision,
        glossaryRevision = glossaryRevision,
        artifactId = artifactId,
        revision = revision,
        payloadHash = payloadHash,
        paragraphsJson = objectMapper.writeValueAsString(paragraphs),
    )

    private fun TranslationArtifactEntity.toArtifact(): TranslationArtifact {
        val providerId = providerBookId.substringBefore(':')
        val bookId = providerBookId.substringAfter(':')
        return TranslationArtifact(
            chapter = ChapterIdentity(BookIdentity(providerId, bookId), chapterId),
            sourceRevision = sourceRevision,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            providerId = translationProviderId,
            modelId = modelId,
            promptRevision = promptRevision,
            glossaryRevision = glossaryRevision,
            paragraphs = readParagraphs(),
        )
    }

    private fun TranslationArtifactEntity.readParagraphs(): List<TranslatedParagraph> =
        objectMapper.readValue(paragraphsJson, object : TypeReference<List<TranslatedParagraph>>() {})

    private fun TranslationArtifactEntity.toResponse(created: Boolean) = TranslationResponse(
        recordId = requireNotNull(id),
        artifactId = artifactId,
        revision = revision,
        payloadHash = payloadHash,
        created = created,
        createdAt = createdAt,
        paragraphs = readParagraphs().map { TranslatedParagraphRequest(it.paragraphId, it.text) },
    )

    private fun TranslationBackupEntity.toCoreRecord() = TranslationBackupRecord(
        key = TranslationBackupKey(userId, backupAccountId, artifactId, revision, payloadHash),
        state = state,
        remoteFileId = remoteFileId,
    )

    private fun TranslationBackupEntity.toPlanResponse(status: BackupPlanStatus) = BackupPlanResponse(
        backupRecordId = requireNotNull(id),
        backupKeyId = backupKeyId,
        status = status,
    )
}
