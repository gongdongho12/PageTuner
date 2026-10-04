package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.core.translation.TranslationArtifact

data class TranslationBackupKey(
    val userId: String,
    val backupAccountId: String,
    val artifactId: String,
    val revision: String,
    val payloadHash: String,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank." }
        require(backupAccountId.isNotBlank()) { "backupAccountId must not be blank." }
        require(artifactId.isNotBlank()) { "artifactId must not be blank." }
        require(revision.isNotBlank()) { "revision must not be blank." }
        require(payloadHash.isNotBlank()) { "payloadHash must not be blank." }
    }

    val id: String = StableContentHash.sha256(
        listOf(userId, backupAccountId, artifactId, revision, payloadHash).joinToString("|"),
    )

    companion object {
        fun from(
            userId: String,
            backupAccountId: String,
            artifact: TranslationArtifact,
        ): TranslationBackupKey = TranslationBackupKey(
            userId = userId,
            backupAccountId = backupAccountId,
            artifactId = artifact.artifactId,
            revision = artifact.revision,
            payloadHash = artifact.payloadHash,
        )
    }
}

enum class BackupState {
    Queued,
    Uploading,
    Uploaded,
    Verified,
    Failed,
}

data class TranslationBackupRecord(
    val key: TranslationBackupKey,
    val state: BackupState,
    val remoteFileId: String? = null,
)

sealed interface BackupDecision {
    data class SkipAlreadyBackedUp(val record: TranslationBackupRecord) : BackupDecision
    data class ReuseActiveJob(val record: TranslationBackupRecord) : BackupDecision
    data object Enqueue : BackupDecision
}

object TranslationBackupPolicy {
    fun decide(
        desired: TranslationBackupKey,
        records: Collection<TranslationBackupRecord>,
    ): BackupDecision {
        val matching = records.filter { it.key.id == desired.id }
        matching.firstOrNull { it.state in setOf(BackupState.Uploaded, BackupState.Verified) }
            ?.let { return BackupDecision.SkipAlreadyBackedUp(it) }
        matching.firstOrNull { it.state in setOf(BackupState.Queued, BackupState.Uploading) }
            ?.let { return BackupDecision.ReuseActiveJob(it) }
        return BackupDecision.Enqueue
    }
}
