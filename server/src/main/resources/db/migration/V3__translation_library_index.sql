CREATE INDEX idx_translation_artifact_owner_library
    ON translation_artifact (user_id, created_at DESC, id DESC)
    WHERE content_provider_id IS NOT NULL AND book_id IS NOT NULL;
