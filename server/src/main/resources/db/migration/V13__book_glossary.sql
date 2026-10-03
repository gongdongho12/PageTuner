-- Full source IDs may exceed PostgreSQL btree tuple limits. The digest indexes the
-- exact tuple only; every lookup verifies the original fields before using a row.
create table book_glossary (
    user_id varchar(120) not null references reader_account(username) on delete cascade,
    identity_digest char(64) not null,
    provider_id varchar(100) not null,
    book_id varchar(2000) not null,
    target_language varchar(24) not null,
    version bigint not null check (version between 1 and 9007199254740991),
    entries_json text,
    mutation_id uuid not null,
    expected_version bigint not null check (expected_version between 0 and 9007199254740990),
    updated_at timestamptz not null,
    primary key (user_id, identity_digest)
);
