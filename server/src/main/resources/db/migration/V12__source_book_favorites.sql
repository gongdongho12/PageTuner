create table source_book_favorite_account (
    user_id varchar(120) primary key references reader_account(username) on delete cascade,
    revision bigint not null default 0 check (revision between 0 and 9007199254740991)
);

-- Retained immutable history permits a fixed committed watermark across multiple pages.
create table source_book_favorite_change (
    user_id varchar(120) not null references source_book_favorite_account(user_id) on delete cascade,
    change_revision bigint not null check (change_revision between 1 and 9007199254740991),
    provider_id varchar(100) not null,
    book_id varchar(500) not null,
    version bigint not null check (version between 1 and 9007199254740991),
    deleted boolean not null,
    book_json text,
    updated_at timestamptz not null,
    primary key (user_id, change_revision),
    unique (user_id, provider_id, book_id, version),
    check ((deleted and book_json is null) or (not deleted and book_json is not null))
);

create table source_book_favorite_current (
    user_id varchar(120) not null,
    provider_id varchar(100) not null,
    book_id varchar(500) not null,
    change_revision bigint not null,
    mutation_id uuid not null,
    request_json text not null,
    primary key (user_id, provider_id, book_id),
    foreign key (user_id, change_revision) references source_book_favorite_change(user_id, change_revision) on delete cascade
);
