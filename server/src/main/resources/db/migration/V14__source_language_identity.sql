-- Language is part of an original snapshot's identity, even when its paragraph
-- digest is identical. Keep every existing UUID, source revision and foreign key.
alter table source_chapter
    drop constraint source_chapter_user_id_identity_key_source_revision_key,
    add constraint source_chapter_identity_language_unique
        unique(user_id, identity_key, source_revision, source_language);
