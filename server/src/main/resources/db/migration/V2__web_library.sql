CREATE TABLE library_book (
    id UUID PRIMARY KEY,
    user_id VARCHAR(120) NOT NULL,
    title VARCHAR(500) NOT NULL,
    author VARCHAR(500) NOT NULL,
    source_language VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_library_book_owner ON library_book (user_id, created_at DESC, id);
CREATE TABLE library_chapter (
    id UUID PRIMARY KEY,
    book_id UUID NOT NULL REFERENCES library_book(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    title VARCHAR(500) NOT NULL,
    source_revision VARCHAR(64) NOT NULL,
    paragraphs_json TEXT NOT NULL,
    UNIQUE (book_id, ordinal)
);
CREATE TABLE library_progress (
    book_id UUID PRIMARY KEY REFERENCES library_book(id) ON DELETE CASCADE,
    chapter_id UUID NOT NULL REFERENCES library_chapter(id),
    paragraph_id VARCHAR(240) NOT NULL,
    character_offset INTEGER NOT NULL CHECK (character_offset >= 0),
    version BIGINT NOT NULL CHECK (version > 0),
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE library_bookmark (
    id UUID PRIMARY KEY,
    book_id UUID NOT NULL REFERENCES library_book(id) ON DELETE CASCADE,
    chapter_id UUID NOT NULL REFERENCES library_chapter(id),
    paragraph_id VARCHAR(240) NOT NULL,
    character_offset INTEGER NOT NULL CHECK (character_offset >= 0),
    note VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_library_bookmark_book ON library_bookmark (book_id, created_at, id);
