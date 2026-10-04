create table reading_progress (
    user_id varchar(120) not null,
    kind varchar(20) not null check (kind in ('ORIGINAL', 'TRANSLATION')),
    record_id uuid not null,
    original_record_id uuid references source_chapter(id) on delete cascade,
    translation_record_id uuid references translation_artifact(id) on delete cascade,
    version bigint not null check (version between 1 and 9007199254740991),
    paragraph_id varchar(200) not null,
    character_offset integer not null check (character_offset >= 0),
    mutation_id uuid not null,
    expected_version bigint not null check (expected_version >= 0 and expected_version = version - 1),
    updated_at timestamptz not null,
    primary key (user_id, kind, record_id),
    check ((kind = 'ORIGINAL' and original_record_id = record_id and original_record_id is not null and translation_record_id is null)
        or (kind = 'TRANSLATION' and translation_record_id = record_id and translation_record_id is not null and original_record_id is null))
);
