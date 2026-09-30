create table reader_preferences (
    user_id varchar(120) primary key,
    version bigint not null check (version between 1 and 9007199254740991),
    font_size integer not null check (font_size between 14 and 36),
    line_height_percent integer not null check (line_height_percent between 110 and 240),
    page_margin integer not null check (page_margin between 0 and 48),
    touch_direction varchar(20) not null check (touch_direction in ('left-previous', 'left-next', 'buttons-only')),
    list_mode varchar(10) not null check (list_mode in ('paged', 'scroll')),
    mutation_id uuid not null,
    expected_version bigint not null check (expected_version >= 0 and expected_version = version - 1),
    updated_at timestamptz not null
);
