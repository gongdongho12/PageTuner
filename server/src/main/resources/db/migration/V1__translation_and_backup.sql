CREATE TABLE translation_artifact (
    id UUID PRIMARY KEY,
    user_id VARCHAR(120) NOT NULL,
    provider_book_id VARCHAR(160) NOT NULL,
    chapter_id VARCHAR(240) NOT NULL,
    source_revision VARCHAR(64) NOT NULL,
    source_language VARCHAR(24) NOT NULL,
    target_language VARCHAR(24) NOT NULL,
    translation_provider_id VARCHAR(80) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    prompt_revision VARCHAR(80) NOT NULL,
    glossary_revision VARCHAR(80) NOT NULL,
    artifact_id VARCHAR(64) NOT NULL,
    revision VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    paragraphs_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_translation_artifact_owner_variant_revision
        UNIQUE (user_id, artifact_id, revision)
);

CREATE INDEX idx_translation_artifact_owner_chapter
    ON translation_artifact (user_id, provider_book_id, chapter_id);

CREATE TABLE translation_backup (
    id UUID PRIMARY KEY,
    backup_key_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(120) NOT NULL,
    backup_account_id VARCHAR(160) NOT NULL,
    artifact_id VARCHAR(64) NOT NULL,
    revision VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    remote_file_id VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_translation_backup_key UNIQUE (backup_key_id)
);

CREATE INDEX idx_translation_backup_owner_artifact
    ON translation_backup (user_id, backup_account_id, artifact_id);
