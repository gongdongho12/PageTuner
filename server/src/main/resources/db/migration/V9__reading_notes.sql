create table reading_note_document (
    user_id varchar(120) not null,
    kind varchar(20) not null check (kind in ('ORIGINAL', 'TRANSLATION')),
    record_id uuid not null,
    original_record_id uuid references source_chapter(id) on delete cascade,
    translation_record_id uuid references translation_artifact(id) on delete cascade,
    revision bigint not null default 0 check (revision between 0 and 9007199254740991),
    primary key (user_id, kind, record_id),
    check ((kind = 'ORIGINAL' and original_record_id = record_id and original_record_id is not null and translation_record_id is null)
        or (kind = 'TRANSLATION' and translation_record_id = record_id and translation_record_id is not null and original_record_id is null))
);

-- Immutable committed changes make fixed-watermark pagination safe during concurrent edits.
create table reading_note_change (
    user_id varchar(120) not null,
    kind varchar(20) not null,
    record_id uuid not null,
    change_revision bigint not null check (change_revision between 1 and 9007199254740991),
    note_id uuid not null,
    version bigint not null check (version between 1 and 9007199254740991),
    deleted boolean not null,
    note_json text,
    updated_at timestamptz not null,
    primary key (user_id, kind, record_id, change_revision),
    unique (user_id, kind, record_id, note_id, version),
    foreign key (user_id, kind, record_id) references reading_note_document(user_id, kind, record_id) on delete cascade,
    check ((deleted and note_json is null) or (not deleted and note_json is not null))
);

create table reading_note_current (
    user_id varchar(120) not null,
    kind varchar(20) not null,
    record_id uuid not null,
    note_id uuid not null,
    change_revision bigint not null,
    mutation_id uuid not null,
    request_json text not null,
    primary key (user_id, kind, record_id, note_id),
    foreign key (user_id, kind, record_id, change_revision)
        references reading_note_change(user_id, kind, record_id, change_revision) on delete cascade
);
