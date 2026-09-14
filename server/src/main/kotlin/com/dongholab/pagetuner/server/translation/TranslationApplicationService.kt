package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.core.backup.BackupDecision
import com.dongholab.pagetuner.core.backup.BackupState
import com.dongholab.pagetuner.core.backup.TranslationBackupKey
import com.dongholab.pagetuner.core.backup.TranslationBackupPolicy
import com.dongholab.pagetuner.core.backup.TranslationBackupRecord
import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.translation.StoredTranslation
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import com.dongholab.pagetuner.core.translation.TranslationSaveResult
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation

@Service
class TranslationApplicationService(
    private val artifacts: TranslationArtifactRepository,
    private val backups: TranslationBackupRepository,
    private val objectMapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val library: TranslationLibraryRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(userId: String, page: Int, size: Int): TranslationListResponse {
        require(userId.isNotBlank()) { "userId must not be blank." }
        require(page >= 0) { "page must be zero or greater." }
        require(size in 1..50) { "size must be between 1 and 50." }
        val offset = page.toLong() * size
        require(offset <= Int.MAX_VALUE) { "The requested page offset is too large." }
        val totalItems = library.count(userId)
        val totalPages = totalItems / size + if (totalItems % size == 0L) 0 else 1
        check(totalPages <= Int.MAX_VALUE) { "The translation library exceeds the supported page count." }
        return TranslationListResponse(
            items = library.list(userId, size, offset),
            page = page,
            size = size,
            totalItems = totalItems,
            totalPages = totalPages.toInt(),
            hasNext = page.toLong() + 1 < totalPages,
        )
    }

    @Transactional
    fun save(userId: String, request: SaveTranslationRequest): TranslationResponse {
        require(userId.isNotBlank()) { "userId must not be blank." }
        val artifact = request.toArtifact()
        require(listOfNotNull(request.bookTitle, request.chapterTitle).all { it.length <= 2000 && it.isNotBlank() }) { "Invalid translation display title." }
        lockKey("artifact:$userId:${artifact.artifactId}:${artifact.revision}")
        val existing = artifacts.findByUserIdAndArtifactIdAndRevision(
            userId,
            artifact.artifactId,
            artifact.revision,
        )
        if (existing != null) {
            existing.verifyMetadata(artifact)
            if (request.bookTitle != null) existing.bookTitle = request.bookTitle
            if (request.chapterTitle != null) existing.chapterTitle = request.chapterTitle
            if (existing.contentProviderId == null) {
                existing.contentProviderId = artifact.chapter.book.providerId
                existing.bookId = artifact.chapter.book.bookId
                existing.updatedAt = Instant.now()
                artifacts.saveAndFlush(existing)
            }
            return existing.toResponse(created = false)
        }

        val entity = artifact.toEntity(userId).apply {
            bookTitle = request.bookTitle
            chapterTitle = request.chapterTitle
        }
        return artifacts.saveAndFlush(entity).toResponse(created = true)
    }

    @Transactional(readOnly = true)
    fun get(userId: String, recordId: UUID): TranslationResponse =
        (artifacts.findByIdAndUserId(recordId, userId) ?: throw TranslationNotFound())
            .toResponse(created = false)

    @Transactional
    fun planBackup(
        userId: String,
        recordId: UUID,
        request: PlanBackupRequest,
    ): BackupPlanResponse {
        val artifactEntity = artifacts.findByIdAndUserId(recordId, userId) ?: throw TranslationNotFound()
        // Stored hashes are authoritative, including for V1 rows whose original
        // provider/book components are unavailable. Never recompute them by splitting.
        val desired = TranslationBackupKey(
            userId,
            request.backupAccountId,
            artifactEntity.artifactId,
            artifactEntity.revision,
            artifactEntity.payloadHash,
        )
        lockKey("backup:${desired.id}")
        val existing = backups.findAllByUserIdAndBackupAccountIdAndArtifactId(
            userId,
            request.backupAccountId,
            artifactEntity.artifactId,
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
        contentProviderId = chapter.book.providerId,
        bookId = chapter.book.bookId,
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

    private fun TranslationArtifactEntity.verifyMetadata(artifact: TranslationArtifact) {
        // The legacy core identity format is intentionally unchanged. Reject an
        // ambiguous canonical/hash match instead of returning another raw identity.
        if (providerBookId != artifact.chapter.book.canonicalId ||
            chapterId != artifact.chapter.chapterId ||
            sourceRevision != artifact.sourceRevision ||
            sourceLanguage != artifact.sourceLanguage ||
            targetLanguage != artifact.targetLanguage ||
            translationProviderId != artifact.providerId ||
            modelId != artifact.modelId ||
            promptRevision != artifact.promptRevision ||
            glossaryRevision != artifact.glossaryRevision ||
            payloadHash != artifact.payloadHash ||
            readParagraphs() != artifact.paragraphs ||
            (contentProviderId != null && contentProviderId != artifact.chapter.book.providerId) ||
            (bookId != null && bookId != artifact.chapter.book.bookId)
        ) {
            throw TranslationIdentityConflict()
        }
    }

    private fun TranslationArtifactEntity.readParagraphs(): List<TranslatedParagraph> =
        objectMapper.readValue(paragraphsJson, object : TypeReference<List<TranslatedParagraph>>() {})

    private fun TranslationArtifactEntity.toResponse(created: Boolean): TranslationResponse {
        val artifact = TranslationArtifact(
            chapter = ChapterIdentity(
                BookIdentity(
                    contentProviderId ?: throw TranslationMetadataUnavailable(),
                    bookId ?: throw TranslationMetadataUnavailable(),
                ),
                chapterId,
            ),
            sourceRevision = sourceRevision,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            providerId = translationProviderId,
            modelId = modelId,
            promptRevision = promptRevision,
            glossaryRevision = glossaryRevision,
            paragraphs = readParagraphs(),
        )
        check(artifact.artifactId == artifactId && artifact.revision == revision && artifact.payloadHash == payloadHash) {
            "Stored translation metadata does not match its persisted identity."
        }
        return TranslationResponse.from(
            TranslationSaveResult(
                StoredTranslation(requireNotNull(id).toString(), artifact, createdAt.toString()),
                created,
            ),
        ).copy(bookTitle = bookTitle, chapterTitle = chapterTitle)
    }

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
