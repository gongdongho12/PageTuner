create table reader_account (
    id uuid primary key,
    username varchar(120) not null unique,
    password_hash varchar(100) not null,
    display_name varchar(80) not null,
    locale varchar(35) not null default 'ko',
    target_language varchar(24) not null default 'ko',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
