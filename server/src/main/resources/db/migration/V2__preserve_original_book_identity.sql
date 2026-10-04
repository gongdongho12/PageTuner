-- Keep the existing canonical ID and hashes unchanged. V1 did not preserve the
-- original component boundary or whitespace, so historical rows cannot be
-- backfilled reliably. A repeat upload supplies their missing components.
ALTER TABLE translation_artifact
    ADD COLUMN content_provider_id TEXT,
    ADD COLUMN book_id TEXT,
    ADD CONSTRAINT ck_translation_artifact_original_identity_pair
        CHECK ((content_provider_id IS NULL) = (book_id IS NULL));
