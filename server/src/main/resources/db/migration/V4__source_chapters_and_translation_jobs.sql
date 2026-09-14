create table source_chapter (
    id uuid primary key,
    user_id varchar(255) not null,
    identity_key varchar(64) not null,
    provider_id text not null,
    book_id text not null,
    book_title text not null,
    book_url text not null,
    chapter_id text not null,
    chapter_title text not null,
    chapter_url text not null,
    source_language varchar(32) not null,
    source_revision varchar(64) not null,
    paragraphs_json text not null,
    created_at timestamptz not null default now(),
    unique(user_id, identity_key, source_revision)
);
create index source_chapter_library on source_chapter(user_id, created_at desc, id desc);

create table translation_job (
    id uuid primary key,
    user_id varchar(255) not null,
    idempotency_key uuid not null,
    request_hash varchar(64) not null,
    chapter_record_id uuid not null references source_chapter(id),
    provider_kind varchar(40) not null,
    target_language varchar(32) not null,
    settings_json text not null,
    worker_id uuid not null,
    lease_until timestamptz not null,
    status varchar(20) not null check(status in ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','INTERRUPTED')),
    completed_paragraphs integer not null default 0,
    total_paragraphs integer not null,
    translation_record_id uuid references translation_artifact(id),
    error_code varchar(64),
    error_message text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(user_id, idempotency_key)
);
create index translation_job_library on translation_job(user_id, created_at desc, id desc);
create index translation_job_reuse on translation_job(user_id, request_hash, status);
create table translation_job_paragraph (
    job_id uuid not null references translation_job(id) on delete cascade,
    paragraph_id text not null,
    translated_text text not null,
    primary key(job_id, paragraph_id)
);
