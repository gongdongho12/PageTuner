package com.dongholab.pagetuner.server.translation

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface TranslationArtifactRepository : JpaRepository<TranslationArtifactEntity, UUID> {
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
