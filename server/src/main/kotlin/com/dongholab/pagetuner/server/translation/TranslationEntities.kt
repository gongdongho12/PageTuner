package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.core.backup.BackupState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "translation_artifact",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_translation_artifact_owner_variant_revision",
            columnNames = ["user_id", "artifact_id", "revision"],
        ),
    ],
)
class TranslationArtifactEntity(
    @Column(name = "user_id", nullable = false, length = 120)
    var userId: String,
    @Column(name = "provider_book_id", nullable = false, length = 160)
    var providerBookId: String,
    @Column(name = "chapter_id", nullable = false, length = 240)
    var chapterId: String,
    @Column(name = "source_revision", nullable = false, length = 64)
    var sourceRevision: String,
    @Column(name = "source_language", nullable = false, length = 24)
    var sourceLanguage: String,
    @Column(name = "target_language", nullable = false, length = 24)
    var targetLanguage: String,
    @Column(name = "translation_provider_id", nullable = false, length = 80)
    var translationProviderId: String,
    @Column(name = "model_id", nullable = false, length = 160)
    var modelId: String,
    @Column(name = "prompt_revision", nullable = false, length = 80)
    var promptRevision: String,
    @Column(name = "glossary_revision", nullable = false, length = 80)
    var glossaryRevision: String,
    @Column(name = "artifact_id", nullable = false, length = 64)
    var artifactId: String,
    @Column(name = "revision", nullable = false, length = 64)
    var revision: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    var payloadHash: String,
    @Column(name = "paragraphs_json", nullable = false, columnDefinition = "text")
    var paragraphsJson: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
)

@Entity
@Table(
    name = "translation_backup",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_translation_backup_key", columnNames = ["backup_key_id"]),
    ],
)
class TranslationBackupEntity(
    @Column(name = "backup_key_id", nullable = false, length = 64)
    var backupKeyId: String,
    @Column(name = "user_id", nullable = false, length = 120)
    var userId: String,
    @Column(name = "backup_account_id", nullable = false, length = 160)
    var backupAccountId: String,
    @Column(name = "artifact_id", nullable = false, length = 64)
    var artifactId: String,
    @Column(name = "revision", nullable = false, length = 64)
    var revision: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    var payloadHash: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    var state: BackupState,
    @Column(name = "remote_file_id", length = 240)
    var remoteFileId: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
)
