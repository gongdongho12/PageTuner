package com.dongholab.pagetuner.server.translation

import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaRepository

interface TranslationArtifactRepository : JpaRepository<TranslationArtifactEntity, UUID> {
    @Query("""select a from TranslationArtifactEntity a where a.userId = :userId
        and (:providerBookId is null or a.providerBookId = :providerBookId)
        and (:chapterId is null or a.chapterId = :chapterId)
        and (:sourceRevision is null or a.sourceRevision = :sourceRevision)
        and (:targetLanguage is null or a.targetLanguage = :targetLanguage)""")
    fun search(userId: String, providerBookId: String?, chapterId: String?, sourceRevision: String?,
        targetLanguage: String?, pageable: Pageable): Page<TranslationArtifactEntity>

    fun findByUserIdAndArtifactIdAndRevision(
        userId: String,
        artifactId: String,
        revision: String,
    ): TranslationArtifactEntity?

    fun findByIdAndUserId(id: UUID, userId: String): TranslationArtifactEntity?
}

interface TranslationBackupRepository : JpaRepository<TranslationBackupEntity, UUID> {
    fun findAllByUserIdAndBackupAccountIdAndArtifactId(
        userId: String,
        backupAccountId: String,
        artifactId: String,
    ): List<TranslationBackupEntity>

    fun findByBackupKeyId(backupKeyId: String): TranslationBackupEntity?
}
