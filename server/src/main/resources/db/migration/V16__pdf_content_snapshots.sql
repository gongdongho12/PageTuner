-- Immutable content snapshots; these records do not establish source identity or sync bindings.
create table pdf_content_snapshot (
    id uuid primary key,
    user_id varchar(120) not null references reader_account(username) on delete cascade,
    upload_id uuid not null,
    request_hash char(64) not null,
    metadata_json text not null,
    proof_json text not null,
    created_at timestamptz not null,
    unique (user_id, upload_id)
);
create table pdf_content_payload (
    snapshot_id uuid not null references pdf_content_snapshot(id) on delete cascade,
    ordinal integer not null check (ordinal >= 0),
    path varchar(71) not null,
    mime_type varchar(64) not null,
    payload bytea not null,
    primary key (snapshot_id, ordinal),
    unique (snapshot_id, path)
);
